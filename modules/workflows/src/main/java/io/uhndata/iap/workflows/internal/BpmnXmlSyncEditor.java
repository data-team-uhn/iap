/*
 * Copyright 2026 DATA @ UHN. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.uhndata.iap.workflows.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.stream.StreamSupport;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.jackrabbit.oak.api.Blob;
import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.spi.commit.DefaultEditor;
import org.apache.jackrabbit.oak.spi.commit.Editor;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.workflows.WorkflowDefinitionUtils;

/**
 * A commit editor that keeps a {@code wf:WorkflowVersion}'s {@code wf:FlowNode}/{@code wf:SequenceFlow} children in
 * sync with its {@code bpmn.xml} file, so that saving a diagram is all it takes to update the graph the engine runs.
 *
 * <p>
 * Deriving the children inside the same commit, rather than from a listener reacting to it afterwards, is what makes
 * the two impossible to observe out of step: no reader ever sees a version whose {@code bpmn.xml} and flow nodes
 * disagree, and no second session has to be opened to write them.
 * </p>
 *
 * <p>
 * {@code wf:WorkflowVersion} extends {@code data:Entity}, which is {@code mix:versionable}, and neither the
 * {@code bpmn.xml} nor the {@code wf:FlowNode} child item definitions override the default on-parent-version
 * behavior in {@code workflowDefinitions.cnd}. Oak's version enforcement runs as its own commit hook, ahead of the
 * hook this editor is composed into, so it has already accepted the {@code bpmn.xml} change by the time this editor
 * runs — meaning the commit is, by construction, happening while the node is checked out. This editor can therefore
 * add/replace the derived children directly on the same {@link NodeBuilder}, in the same commit, without ever
 * calling checkout/checkin itself.
 * </p>
 *
 * <p>
 * The same ordering has a consequence worth knowing: Oak drives every editor in one hook from a single diff of the
 * commit's before/after states, so nodes this editor writes into the builder are not seen by the validators sharing
 * that hook. The derived children are therefore not node-type or name validated, which is why
 * {@link WorkflowDefinitionUtils} checks the names and configured types it writes itself.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public class BpmnXmlSyncEditor extends DefaultEditor
{
    private static final Logger LOGGER = LoggerFactory.getLogger(BpmnXmlSyncEditor.class);

    private static final String WORKFLOWS_ROOT_NAME = "Workflows";

    private static final String WORKFLOW_TYPES_ROOT_NAME = "WorkflowTypes";

    private static final String JCR_SYSTEM_NODE_NAME = "jcr:system";

    private static final String JCR_NODE_TYPES_NODE_NAME = "jcr:nodeTypes";

    private static final String WORKFLOW_VERSION_TYPE = "wf:WorkflowVersion";

    private static final String SUPERTYPES_PROPERTY = "rep:supertypes";

    private static final String BPMN_XML_FILE_NAME = "bpmn.xml";

    private static final String JCR_CONTENT_NODE_NAME = "jcr:content";

    private static final String JCR_DATA_PROPERTY = "jcr:data";

    private static final String JCR_PRIMARY_TYPE_PROPERTY = "jcr:primaryType";

    private static final String PARSED_HASH_PROPERTY = "bpmnXmlParsedHash";

    private static final String BPMN_AUTHORITATIVE_PROPERTY = "bpmnAuthoritative";

    /**
     * What kind of node this editor instance is looking at, and therefore what its children may be.
     *
     * @since 0.1.0
     */
    private enum Stage
    {
        /** The repository root; only descends into {@code /Workflows}. */
        ROOT,
        /** Somewhere under {@code /Workflows}, not yet known to be a {@code wf:WorkflowVersion}. */
        WORKFLOWS_SUBTREE,
        /** A {@code wf:WorkflowVersion} node; only descends into its {@code bpmn.xml} child. */
        WORKFLOW_VERSION,
        /** The {@code bpmn.xml} file node; only descends into its {@code jcr:content} child. */
        BPMN_XML_FILE,
        /** The {@code jcr:content} node whose {@code jcr:data} is the actual BPMN XML source. */
        JCR_CONTENT
    }

    /**
     * The parts of this editor's state that stay the same for every instance in the same commit, regardless of
     * which node it descends into, bundled together so the per-instance constructor stays within the checkstyle
     * parameter limit.
     *
     * <p>The two roots are derived from the committed state on demand rather than passed in already resolved: this
     * editor is created for every commit in the repository, and only the few that reach a workflow version ever need
     * them.</p>
     *
     * @since 0.1.0
     */
    private record CommitContext(NodeState after, String author)
    {
        NodeState workflowTypesRoot()
        {
            return this.after.getChildNode(WORKFLOW_TYPES_ROOT_NAME);
        }

        NodeState nodeTypesRoot()
        {
            return this.after.getChildNode(JCR_SYSTEM_NODE_NAME).getChildNode(JCR_NODE_TYPES_NODE_NAME);
        }
    }

    private final Stage stage;

    private final NodeBuilder node;

    private final String path;

    private final CommitContext context;

    private final NodeBuilder workflowVersion;

    private final String workflowVersionPath;

    /**
     * Constructor for the repository root, receiving the whole commit.
     *
     * @param root the root node builder
     * @param after the committed root node state, which {@code /WorkflowTypes} (the {@code wf:FlowNodeType}
     *            vocabulary) and {@code /jcr:system/jcr:nodeTypes} (used to recognize workflow versions and
     *            previously parsed flow nodes, including subtypes of either) are read from
     * @param author the id of the user who made this commit, recorded as {@code jcr:createdBy} on newly parsed
     *            flow nodes/sequence flows
     */
    public BpmnXmlSyncEditor(final NodeBuilder root, final NodeState after, final String author)
    {
        this(Stage.ROOT, root, "/", new CommitContext(after, author), null, null);
    }

    private BpmnXmlSyncEditor(final Stage stage, final NodeBuilder node, final String path,
        final CommitContext context, final NodeBuilder workflowVersion, final String workflowVersionPath)
    {
        this.stage = stage;
        this.node = node;
        this.path = path;
        this.context = context;
        this.workflowVersion = workflowVersion;
        this.workflowVersionPath = workflowVersionPath;
    }

    @Override
    public Editor childNodeAdded(final String name, final NodeState after)
    {
        return childEditor(name);
    }

    @Override
    public Editor childNodeChanged(final String name, final NodeState before, final NodeState after)
    {
        return childEditor(name);
    }

    @Override
    public Editor childNodeDeleted(final String name, final NodeState before)
    {
        // The source can go away either as the whole file or as just the content node holding its bytes; both leave
        // the derived children describing something that no longer exists.
        final boolean sourceGone = this.stage == Stage.WORKFLOW_VERSION && BPMN_XML_FILE_NAME.equals(name)
            || this.stage == Stage.BPMN_XML_FILE && JCR_CONTENT_NODE_NAME.equals(name);
        if (sourceGone) {
            clearFlowNodes("bpmn.xml was deleted");
        }
        return null;
    }

    private Editor childEditor(final String name)
    {
        return switch (this.stage) {
            case ROOT -> WORKFLOWS_ROOT_NAME.equals(name) ? descend(name, Stage.WORKFLOWS_SUBTREE, null, null) : null;
            case WORKFLOWS_SUBTREE -> {
                final NodeBuilder child = this.node.getChildNode(name);
                yield isWorkflowVersion(child) ? descend(name, Stage.WORKFLOW_VERSION, child, childPath(name))
                    : descend(name, Stage.WORKFLOWS_SUBTREE, null, null);
            }
            case WORKFLOW_VERSION -> BPMN_XML_FILE_NAME.equals(name)
                ? descend(name, Stage.BPMN_XML_FILE, this.workflowVersion, this.workflowVersionPath) : null;
            case BPMN_XML_FILE -> JCR_CONTENT_NODE_NAME.equals(name)
                ? descend(name, Stage.JCR_CONTENT, this.workflowVersion, this.workflowVersionPath) : null;
            // Enumerated rather than defaulted, so that a new stage has to say what its children are.
            case JCR_CONTENT -> null;
        };
    }

    private BpmnXmlSyncEditor descend(final String name, final Stage childStage, final NodeBuilder workflowVersion,
        final String workflowVersionPath)
    {
        return new BpmnXmlSyncEditor(childStage, this.node.getChildNode(name), childPath(name), this.context,
            workflowVersion, workflowVersionPath);
    }

    private String childPath(final String name)
    {
        return "/".equals(this.path) ? "/" + name : this.path + "/" + name;
    }

    @Override
    public void propertyAdded(final PropertyState after)
    {
        handleProperty(after);
    }

    @Override
    public void propertyChanged(final PropertyState before, final PropertyState after)
    {
        handleProperty(after);
    }

    @Override
    public void propertyDeleted(final PropertyState before)
    {
        // Losing the bytes is losing the source, even though the file node itself survives.
        if (this.stage == Stage.JCR_CONTENT && JCR_DATA_PROPERTY.equals(before.getName())) {
            clearFlowNodes("its bpmn.xml jcr:data was deleted");
        }
    }

    private void handleProperty(final PropertyState property)
    {
        if (this.stage == Stage.JCR_CONTENT && JCR_DATA_PROPERTY.equals(property.getName()) && !property.isArray()) {
            syncIfNeeded(property.getValue(Type.BINARY));
        }
    }

    private void clearFlowNodes(final String reason)
    {
        if (!bpmnIsAuthoritative()) {
            LOGGER.debug("Left the flow nodes of WorkflowVersion {} alone ({}): its diagram is not authoritative",
                this.workflowVersionPath, reason);
            return;
        }
        WorkflowDefinitionUtils.clear(this.workflowVersion, this.context.nodeTypesRoot());
        this.workflowVersion.removeProperty(PARSED_HASH_PROPERTY);
        LOGGER.debug("Cleared flow nodes for WorkflowVersion {}: {}", this.workflowVersionPath, reason);
    }

    /**
     * Whether this version's diagram is the source of its flow nodes, which is the only condition under which this
     * editor writes anything.
     *
     * <p>Asked per version, and answered by the version itself, because the alternatives are both unsound. Deriving
     * every version would drop whatever the translation cannot yet express — a multi-valued {@code performers}, a
     * timer's duration, a condition subtree — turning a working workflow into a shape of one. Merging the derived
     * nodes into the authored ones would be worse: nothing in a diagram distinguishes a property that is absent
     * because it is inexpressible from one absent because it was deleted, and a node reappearing under the same
     * BPMN id may have been redrawn as a different kind of node in a different place, so keeping properties across
     * it is identity by coincidence.</p>
     *
     * @return {@code true} if the diagram owns this version's flow nodes
     */
    private boolean bpmnIsAuthoritative()
    {
        return this.workflowVersion != null && this.workflowVersion.getBoolean(BPMN_AUTHORITATIVE_PROPERTY);
    }

    /**
     * Reparses the diagram unless the stored hash says the stored graph already came from these exact bytes. The
     * hash is taken from the blob's stream rather than a decoded string so that a multi-megabyte diagram is never
     * held in memory whole just to find out nothing changed.
     */
    private void syncIfNeeded(final Blob bpmnXml)
    {
        if (bpmnXml.length() == 0) {
            return;
        }
        // RuntimeException is caught alongside the parser's own failures on purpose: a commit editor that throws
        // aborts the entire commit, losing changes that have nothing to do with workflows, so a diagram or a
        // vocabulary entry we cannot make sense of must never cost the user their save.
        try (InputStream contents = bpmnXml.getNewStream()) {
            final String hash = DigestUtils.sha256Hex(contents);
            if (hash.equals(this.workflowVersion.getString(PARSED_HASH_PROPERTY))) {
                return;
            }
            if (!bpmnIsAuthoritative()) {
                // Said out loud, and only when the bytes really changed: a diagram that was edited and had no
                // effect is exactly the kind of nothing-happened that is impossible to guess at. Not a warning,
                // because for a hand-authored version this is the correct outcome rather than a problem.
                LOGGER.info("The diagram of WorkflowVersion {} changed but its flow nodes were left as authored:"
                    + " set {} to true to have the diagram own them", this.workflowVersionPath,
                    BPMN_AUTHORITATIVE_PROPERTY);
                return;
            }
            try (InputStream reread = bpmnXml.getNewStream()) {
                WorkflowDefinitionUtils.parse(reread, this.workflowVersion, this.context.workflowTypesRoot(),
                    this.context.nodeTypesRoot(), this.context.author(), this.workflowVersionPath);
            }
            this.workflowVersion.setProperty(PARSED_HASH_PROPERTY, hash);
            LOGGER.debug("Synced flow nodes for WorkflowVersion {}", this.workflowVersionPath);
        } catch (final ParserConfigurationException | SAXException | IOException | RuntimeException e) {
            LOGGER.error("Failed to parse bpmnXml at {}: {}", this.workflowVersionPath, e.getMessage(), e);
            // A failure rather than a problem: something threw where the translation expected to parse. The commit
            // still succeeds, so the author is told nothing and the version keeps whatever flow nodes it had —
            // which, for a first upload, is none at all
            ErrorLogger.logError(e, ErrorContext.of(BpmnXmlSyncEditor.class, "syncFlowNodes")
                .about(this.workflowVersionPath));
        }
    }

    /**
     * Whether this node is a workflow version, accepting subtypes: the node types in
     * {@code workflowDefinitions.cnd} are meant to be extended, and a subtype whose diagram is silently never parsed
     * would be the least visible way to fail.
     */
    private boolean isWorkflowVersion(final NodeBuilder node)
    {
        final String type = node.getName(JCR_PRIMARY_TYPE_PROPERTY);
        if (type == null) {
            return false;
        }
        if (WORKFLOW_VERSION_TYPE.equals(type)) {
            return true;
        }
        return StreamSupport.stream(this.context.nodeTypesRoot().getChildNode(type)
            .getNames(SUPERTYPES_PROPERTY).spliterator(), false)
            .anyMatch(WORKFLOW_VERSION_TYPE::equals);
    }
}

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
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;

import io.uhndata.iap.workflows.api.EventAttachment;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.WorkflowDefinition;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * What the handlers that author workflow versions have in common: finding the definition a version belongs to,
 * naming a new one, and moving a diagram from one place to another. Gathered here rather than repeated, because
 * these are the operations where two handlers disagreeing would show up as a version that is nearly right.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class VersionEdits
{
    /** The name of the {@code nt:file} child holding the BPMN source. */
    static final String BPMN_FILE = "bpmn.xml";

    /** The property holding a version's label, e.g. {@code 1.0}. */
    static final String VERSION = "version";

    static final String DESCRIPTION = "description";

    static final String PRIMARY_TYPE = "jcr:primaryType";

    static final String WORKFLOW_VERSION_TYPE = "wf:WorkflowVersion";

    /** Whether a version's diagram owns its flow nodes; see {@link WorkflowVersion#isBpmnAuthoritative()}. */
    static final String BPMN_AUTHORITATIVE = "bpmnAuthoritative";

    static final String TARGET_RESOURCE_TYPE = "targetResourceType";

    /** The node type a homepage holding workflows names as the type of its children. */
    private static final String WORKFLOW_DEFINITION_TYPE = "wf:WorkflowDefinition";

    /** The property a homepage uses to name the node type it stores. */
    private static final String CHILD_NODE_TYPE = "childNodeType";

    private static final String JCR_CONTENT = "jcr:content";

    private static final String JCR_DATA = "jcr:data";

    private static final String JCR_MIME_TYPE = "jcr:mimeType";

    /** What a BPMN diagram is, for a file stored without a content type of its own. */
    private static final String DEFAULT_MIME_TYPE = "application/xml";

    private VersionEdits()
    {
    }

    /**
     * The version a handler is acting on, taken from the event's target.
     *
     * @param context the handler's context, whose target the workflow declared as a {@code wf/WorkflowVersion}
     * @return the target as a version
     * @throws WorkflowDefinitionException when the target is not a workflow version after all, which means the
     *             definition that reached this handler declares the wrong {@code targetResourceType}
     */
    static WorkflowVersion targetVersion(final WorkflowTaskContext context) throws WorkflowException
    {
        final WorkflowVersion version = context.getTarget().adaptTo(WorkflowVersion.class);
        if (version == null) {
            throw new WorkflowDefinitionException("The activity " + context.getActivity().getPath()
                + " acts on workflow versions, but " + context.getTarget().getPath() + " is not one");
        }
        // Verify the target is getting saved in the correct node structure: errors out if the location is invalid.
        homepageOf(definitionOf(context.getTarget()));
        return version;
    }

    /**
     * The workflow definition a version belongs to.
     *
     * @param version a workflow version resource
     * @return its parent definition
     * @throws WorkflowDefinitionException when it is stored somewhere other than under a definition, so that
     *             there is no set of sibling versions for it to belong to
     */
    static Resource definitionOf(final Resource version) throws WorkflowException
    {
        final Resource parent = version.getParent();
        if (parent == null || !parent.isResourceType(WorkflowDefinition.RESOURCE_TYPE)) {
            throw new WorkflowDefinitionException(
                version.getPath() + " is not stored under a workflow definition");
        }
        return parent;
    }

    /**
     * Retrieve the homepage a workflow definition is stored in.
     * Verifies the parent is a properly defined homepage before returning.
     *
     * @param definition a workflow definition resource
     * @return the homepage holding it
     * @throws WorkflowDefinitionException when it is stored outside any homepage that holds workflows, leaving it
     *             somewhere nothing listing workflows would look
     */
    static Resource homepageOf(final Resource definition) throws WorkflowException
    {
        final Resource parent = definition.getParent();
        if (parent == null
            || !WORKFLOW_DEFINITION_TYPE.equals(parent.getValueMap().get(CHILD_NODE_TYPE, String.class))) {
            throw new WorkflowDefinitionException(
                definition.getPath() + " is not stored in a homepage that holds workflows");
        }
        return parent;
    }

    /**
     * Whether a definition already has a version carrying the given label. Two versions of one workflow carrying
     * the same label would be indistinguishable to everyone reading them.
     *
     * @param definition the workflow definition to look through
     * @param label the version label to look for
     * @return {@code true} if a version already carries that label
     */
    static boolean hasVersionLabelled(final Resource definition, final String label)
    {
        for (final Resource child : definition.getChildren()) {
            if (child.isResourceType(WorkflowVersion.RESOURCE_TYPE)
                && label.equals(child.getValueMap().get(VERSION, String.class))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A node name for a new version: {@code v} and its position among the definition's versions, skipping any name
     * already taken. The label is what identifies a version to a reader, so the name is kept independent of it: a
     * label may say anything, dots included, without the node's path or its console URL having to carry it.
     *
     * @param definition the workflow definition the version will be created under
     * @return an unused child name, e.g. {@code v3}
     */
    static String availableName(final Resource definition)
    {
        int number = versionsOf(definition) + 1;
        while (definition.getChild("v" + number) != null) {
            ++number;
        }
        return "v" + number;
    }

    /**
     * The label a new version is created with: the one the event asks for, or when it names none, the whole number
     * after the highest numeric label already there ({@code 3.0} after {@code 2.1}), or after the number of versions
     * when none of them is numeric. A label that is sent but blank, or not text, is refused rather than defaulted.
     *
     * @param context the handler's context
     * @param definition the workflow definition the version will be created under
     * @return the label, trimmed
     * @throws InvalidPayloadException when the label sent is not usable
     */
    static String newLabel(final WorkflowTaskContext context, final Resource definition)
        throws InvalidPayloadException
    {
        if (context.getEvent().get(VERSION) == null) {
            return nextLabel(definition);
        }
        return Payloads.requireText(context.getEvent(), VERSION, "A version label cannot be blank");
    }

    /**
     * The whole-number label that follows a definition's versions.
     *
     * @param definition the workflow definition to look through
     * @return e.g. {@code 3.0}
     */
    private static String nextLabel(final Resource definition)
    {
        double highest = Double.NaN;
        for (final Resource child : definition.getChildren()) {
            if (child.isResourceType(WorkflowVersion.RESOURCE_TYPE)) {
                try {
                    final double number = Double.parseDouble(child.getValueMap().get(VERSION, ""));
                    if (Double.isFinite(number)) {
                        highest = Double.isNaN(highest) ? number : Math.max(highest, number);
                    }
                } catch (final NumberFormatException e) {
                    // A label that is not a number takes no part in which number comes next
                }
            }
        }
        final long next = Double.isNaN(highest) ? versionsOf(definition) + 1 : (long) Math.floor(highest) + 1;
        return next + ".0";
    }

    /**
     * How many versions a definition holds.
     *
     * @param definition the workflow definition to look through
     * @return the number of its version children
     */
    private static int versionsOf(final Resource definition)
    {
        int count = 0;
        for (final Resource child : definition.getChildren()) {
            if (child.isResourceType(WorkflowVersion.RESOURCE_TYPE)) {
                ++count;
            }
        }
        return count;
    }

    /**
     * Stores an uploaded diagram on a version, replacing whatever it held. The file node is reused when there is
     * one, so that a version's diagram keeps its identity across a save rather than becoming a new node each time.
     *
     * @param version the version to store it on
     * @param diagram the uploaded document
     * @param resolver the engine's session to write through
     * @throws PersistenceException if the diagram cannot be written, or cannot be read to be written
     */
    static void storeDiagram(final Resource version, final EventAttachment diagram, final ResourceResolver resolver)
        throws PersistenceException
    {
        try (InputStream data = diagram.openStream()) {
            final String mimeType = diagram.getMimeType() == null ? DEFAULT_MIME_TYPE : diagram.getMimeType();
            final Resource existing = version.getChild(BPMN_FILE);
            if (existing == null) {
                createFile(version, data, mimeType, resolver);
                return;
            }
            final Resource content = Objects.requireNonNull(existing.getChild(JCR_CONTENT),
                "A stored diagram always has a jcr:content");
            final ModifiableValueMap properties = Objects.requireNonNull(content.adaptTo(ModifiableValueMap.class),
                "A stored diagram the engine is replacing should always be modifiable");
            properties.put(JCR_DATA, data);
            properties.put(JCR_MIME_TYPE, mimeType);
        } catch (final IOException e) {
            throw new PersistenceException("The diagram could not be read: " + e.getMessage(), e);
        }
    }

    /**
     * Copies a version's BPMN source onto a new version, as the {@code nt:file}/{@code nt:resource} pair a diagram
     * is stored as. The bytes are copied rather than shared: two versions pointing at one binary would mean
     * editing either changes both.
     *
     * @param sourceFile the source's {@code bpmn.xml} file, or {@code null} if it has none yet, in which case the
     *            draft starts without one too
     * @param draft the version to copy it onto
     * @param resolver the resolver to create through
     * @throws PersistenceException if the copy cannot be created, or the source cannot be read
     */
    static void copyDiagram(final Resource sourceFile, final Resource draft, final ResourceResolver resolver)
        throws PersistenceException
    {
        if (sourceFile == null) {
            return;
        }
        // Adapting the nt:file directly to a stream is the documented way to reach its contents, and what every
        // other diagram reader here does.
        try (InputStream data = sourceFile.adaptTo(InputStream.class)) {
            if (data != null) {
                // ResourceUtil.getValueMap tolerates a missing jcr:content, answering with an empty map rather
                // than throwing.
                createFile(draft, data, ResourceUtil.getValueMap(sourceFile.getChild(JCR_CONTENT))
                    .get(JCR_MIME_TYPE, DEFAULT_MIME_TYPE), resolver);
            }
        } catch (final IOException e) {
            throw new PersistenceException("The diagram could not be read: " + e.getMessage(), e);
        }
    }

    /**
     * Creates the {@code nt:file}/{@code nt:resource} pair a diagram is stored as.
     *
     * @param version the version to create it under
     * @param data the document's bytes
     * @param mimeType the content type to record
     * @param resolver the resolver to create through
     * @throws PersistenceException if the file cannot be created
     */
    private static void createFile(final Resource version, final InputStream data, final String mimeType,
        final ResourceResolver resolver) throws PersistenceException
    {
        final Resource file = resolver.create(version, BPMN_FILE, Map.of(PRIMARY_TYPE, "nt:file"));
        final Map<String, Object> content = new HashMap<>();
        content.put(PRIMARY_TYPE, "nt:resource");
        content.put(JCR_DATA, data);
        content.put(JCR_MIME_TYPE, mimeType);
        resolver.create(file, JCR_CONTENT, content);
    }
}

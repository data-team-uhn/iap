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
package io.uhndata.iap.tags.internal;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.StreamSupport;

import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.spi.commit.DefaultEditor;
import org.apache.jackrabbit.oak.spi.commit.Editor;
import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.tags.api.TagManager;
import io.uhndata.iap.tags.spi.TagContext;
import io.uhndata.iap.tags.spi.TagDefinitions;
import io.uhndata.iap.tags.spi.TagProcessor;
import io.uhndata.iap.tags.spi.TagProcessor.Phase;
import io.uhndata.iap.tags.spi.TagProcessor.Scope;

/**
 * Keeps the derived tag properties up to date as content changes, by running the {@link TagProcessor} services at
 * commit time. Each node the commit changes is processed in {@link Phase phase} order. Each phase stores the union of
 * what its processors computed in the one property it owns. A property is written only when its value changes, so
 * propagation stops as soon as the values stop changing.
 *
 * <p>
 * The traversal visits only the nodes the commit changed. When what flows down out of a node changes, the new values
 * are pushed into its whole subtree. When a processor needs a whole {@link Scope#ENTITY entity}, the entity is
 * recomputed whenever anything inside it changes.
 * </p>
 *
 * <p>
 * A failing processor never fails the commit, since a user's write must not be lost over a tag. The phase it failed
 * in keeps its last values, and the node is marked with {@link TagManager#COMPUTATION_STATE_PROPERTY}.
 * </p>
 *
 * <p>
 * The next commit that reaches a marked node recomputes it in full, every phase over its whole subtree, and removes
 * the mark. This is also how a repair works: it marks the nodes whose tags went wrong without any change to them.
 * </p>
 *
 * <p>
 * The {@link NodeTypeInspector} decides where the editor writes. It writes only on a node whose types declare the
 * tag properties, as {@code tag:Taggable} does. Aggregated tags climb no further than a {@code tag:Boundary}. Oak
 * checks the editor's writes against the committing session, so a copy written where the committer may not write
 * fails the commit.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public class TagPropagationEditor extends DefaultEditor
{
    private static final Logger LOGGER = LoggerFactory.getLogger(TagPropagationEditor.class);

    /** The subtrees left out of tag propagation: the system and index subtrees, and access control policies. */
    private static final Set<String> SKIPPED_NODES =
        Set.of("jcr:system", "oak:index", "rep:policy", "rep:repoPolicy");

    private final TagPropagationEditor parent;

    private final NodeBuilder node;

    private final String path;

    private final TagPropagationConfig config;

    /** Whether this node was added by the commit being processed. */
    private final boolean added;

    /**
     * The editor of the nearest {@code data:Entity} ancestor, or this editor when its own node is one. It is
     * {@code null} outside any entity, or when no processor is entity-scoped.
     */
    private final TagPropagationEditor entity;

    /** Whether the commit changed this node's own explicit {@code tags} property. */
    private boolean explicitChanged;

    /** Whether the tags belonging to this node, explicit or computed, may have changed. */
    private boolean ownTagsChanged;

    /** Whether the tags flowing up from one of this node's children may have changed. */
    private boolean childContributionChanged;

    /** Whether anything inside this entity changed. Set only on the editor of an entity node. */
    private boolean entityContentChanged;

    /**
     * Constructor for the editor of the repository root.
     *
     * @param builder the root node builder
     * @param config the processors, definitions and node type verdicts of this commit
     */
    public TagPropagationEditor(final NodeBuilder builder, final TagPropagationConfig config)
    {
        this(null, builder, "/", config, false);
    }

    private TagPropagationEditor(final TagPropagationEditor parent, final NodeBuilder node, final String path,
        final TagPropagationConfig config, final boolean added)
    {
        this.parent = parent;
        this.node = node;
        this.path = path;
        this.config = config;
        this.added = added;
        this.entity = findEntity();
    }

    @Override
    public void enter(final NodeState before, final NodeState after)
    {
        this.explicitChanged =
            !Objects.equals(before.getProperty(TagManager.TAGS_PROPERTY), after.getProperty(TagManager.TAGS_PROPERTY));
        if (this.entity != null) {
            // An editor exists only for a node the commit changed, so this entity's content changed
            this.entity.entityContentChanged = true;
        }
        // A node marked stale is recomputed in full. This commit's changes do not say what went wrong with it.
        final boolean stale = isStale(before) || isStale(after);
        clearFailureFlag(this.node);
        if (stale) {
            this.ownTagsChanged = recompute(this.node, parentState(), this.path, scopeRoot());
            return;
        }
        if (this.added) {
            // A new node inherits for the first time. The traversal visits its children, which are new too.
            runPhase(Phase.TOP_DOWN, this.node, parentState(), this.path, scopeRoot());
        }
        // Local tags are computed from the node's own content, which any commit reaching it may have changed
        final boolean localChanged = runPhase(Phase.LOCAL, this.node, parentState(), this.path, scopeRoot());
        this.ownTagsChanged = this.explicitChanged || localChanged;
        if (!this.added && this.ownTagsChanged) {
            // The tags flowing down changed, and the traversal visits only the nodes the commit changed, so the new
            // values are pushed into every descendant here
            sweepDown(this.node, this.path, scopeRoot());
        }
    }

    @Override
    public void leave(final NodeState before, final NodeState after)
    {
        if (this.entity == this && this.entityContentChanged) {
            // Something inside this entity changed, so any entity-scoped computation in it may have a new answer
            this.ownTagsChanged |= recompute(this.node, parentState(), this.path, this.node.getNodeState());
        }
        boolean upChanged = false;
        if (this.added || this.childContributionChanged) {
            upChanged = runPhase(Phase.BOTTOM_UP, this.node, parentState(), this.path, scopeRoot());
        }
        final boolean contributes = this.ownTagsChanged || upChanged || this.added;
        // A boundary contributes nothing to its parent, so the parent need not recompute. What keeps its tags out of
        // the parent is that the aggregation skips boundary children.
        if (this.parent != null && contributes && !isBoundary()) {
            this.parent.childContributionChanged = true;
        }
    }

    @Override
    public Editor childNodeAdded(final String name, final NodeState after)
    {
        return childEditor(name, true);
    }

    @Override
    public Editor childNodeChanged(final String name, final NodeState before, final NodeState after)
    {
        return childEditor(name, false);
    }

    @Override
    public Editor childNodeDeleted(final String name, final NodeState before)
    {
        if (!skip(name)) {
            // The deleted subtree may have contributed tags flowing up, so this node aggregates again on leave
            this.childContributionChanged = true;
            if (this.entity != null) {
                this.entity.entityContentChanged = true;
            }
        }
        return null;
    }

    private Editor childEditor(final String name, final boolean isNew)
    {
        if (skip(name)) {
            return null;
        }
        return new TagPropagationEditor(this, this.node.getChildNode(name), childPath(this.path, name), this.config,
            isNew);
    }

    /**
     * Recomputes the tags flowing down into a node's descendants, going deeper as long as the stored values keep
     * changing.
     *
     * @param current the node whose children are recomputed
     * @param currentPath the path of that node
     * @param currentScope the entity enclosing that node, {@code null} if there is none
     */
    private void sweepDown(final NodeBuilder current, final String currentPath, final NodeState currentScope)
    {
        for (final String name : current.getChildNodeNames()) {
            if (skip(name)) {
                continue;
            }
            final NodeBuilder child = current.getChildNode(name);
            final String subPath = childPath(currentPath, name);
            final NodeState scope = childScope(currentScope, child);
            clearFailureFlag(child);
            boolean changed = runPhase(Phase.TOP_DOWN, child, current.getNodeState(), subPath, scope);
            // A local computation may read what its node inherited
            changed |= runPhase(Phase.LOCAL, child, current.getNodeState(), subPath, scope);
            if (changed) {
                sweepDown(child, subPath, scope);
            }
        }
    }

    /**
     * Runs every phase on a whole subtree, in the order the traversal would. It recomputes an entity whose content
     * changed, and a node marked stale.
     *
     * @param current the node to recompute
     * @param currentParent the state of that node's parent, {@code null} for the repository root
     * @param currentPath the path of that node
     * @param scope the state of the entity enclosing the node, {@code null} if there is none
     * @return {@code true} if any of the node's own stored properties changed
     */
    private boolean recompute(final NodeBuilder current, final NodeState currentParent, final String currentPath,
        final NodeState scope)
    {
        clearFailureFlag(current);
        boolean changed = runPhase(Phase.TOP_DOWN, current, currentParent, currentPath, scope);
        changed |= runPhase(Phase.LOCAL, current, currentParent, currentPath, scope);
        for (final String name : current.getChildNodeNames()) {
            if (skip(name)) {
                continue;
            }
            final NodeBuilder child = current.getChildNode(name);
            recompute(child, current.getNodeState(), childPath(currentPath, name), childScope(scope, child));
        }
        changed |= runPhase(Phase.BOTTOM_UP, current, currentParent, currentPath, scope);
        return changed;
    }

    /**
     * Invokes all the processors of one phase on a node and stores the union of what they computed in the property
     * that phase owns. If a processor throws, the stored value is left as it was, since a union missing one
     * contributor can drop a tag that still applies. The node is marked stale instead.
     *
     * @param phase the phase to run
     * @param target the node to process
     * @param targetParent the state of the node's parent, {@code null} for the repository root
     * @param targetPath the path of the node
     * @param scope the entity enclosing the node, {@code null} if there is none
     * @return {@code true} if the stored property changed
     */
    private boolean runPhase(final Phase phase, final NodeBuilder target, final NodeState targetParent,
        final String targetPath, final NodeState scope)
    {
        final List<TagProcessor> processors = this.config.getProcessors(phase);
        if (processors.isEmpty()) {
            return false;
        }
        final Set<String> computed = new LinkedHashSet<>();
        boolean failed = false;
        for (final TagProcessor processor : processors) {
            final NodeState processorScope = processor.getScope() == Scope.ENTITY ? scope : null;
            try {
                computed.addAll(processor.computeTags(new NodeTagContext(target.getNodeState(), targetParent,
                    targetPath, processorScope, this.config)));
            } catch (final RuntimeException e) {
                failed = true;
                LOGGER.error("The tag processor {} failed on {}, the {} of that node are left as they were: {}",
                    processor.getClass().getName(), targetPath, phase.getPropertyName(), e.getMessage(), e);
                // Recorded against the processor's class, which is what needs fixing, and once per commit
                if (this.config.isFirstFailure(processor, phase)) {
                    ErrorLogger.logError(e, ErrorContext.of(processor.getClass(), "computeTags")
                        .about(targetPath).with("phase", phase.getPropertyName()));
                }
            }
        }
        if (failed) {
            setFailureFlag(target);
            return false;
        }
        return write(target, phase.getPropertyName(), computed);
    }

    /**
     * Stores a derived tag set in a node's property, if it differs from the stored value. An empty set removes the
     * property. A node whose types do not declare the tag properties is never written to, and the copies stop there.
     *
     * @param target the node to write to
     * @param property the property name
     * @param tags the tags to store
     * @return {@code true} if the stored value changed
     */
    private boolean write(final NodeBuilder target, final String property, final Set<String> tags)
    {
        if (!this.config.getNodeTypes().canStoreTags(target.getNodeState())) {
            return false;
        }
        final Set<String> current = TagProcessor.readTags(target.getNodeState(), property);
        if (current.equals(tags)) {
            return false;
        }
        if (tags.isEmpty()) {
            target.removeProperty(property);
        } else {
            target.setProperty(property, tags, Type.STRINGS);
        }
        return true;
    }

    /**
     * Checks whether a node is marked as having stale derived tags, because a processor failed on it or a repair
     * asked. Either way only a full recomputation can fix the values, so this reads only whether the property is
     * there. Its value tells a person which of the two it was.
     *
     * @param state the node state to check, before or after this commit
     * @return {@code true} if the node carries the marker
     */
    private static boolean isStale(final NodeState state)
    {
        return state.hasProperty(TagManager.COMPUTATION_STATE_PROPERTY);
    }

    private void setFailureFlag(final NodeBuilder target)
    {
        // Every commit reaching a flagged node removes the flag, so a flag on the repository root would make every
        // commit write the root
        if (this.config.getNodeTypes().canStoreTags(target.getNodeState())) {
            target.setProperty(TagManager.COMPUTATION_STATE_PROPERTY, TagManager.STATE_FAILED);
        }
    }

    /**
     * Clears the failure flag of a node about to be recomputed. A phase that fails during the recomputation sets it
     * again, so the flag stays as long as the node's tags may be out of date.
     *
     * @param target the node about to be recomputed
     */
    private void clearFailureFlag(final NodeBuilder target)
    {
        if (target.hasProperty(TagManager.COMPUTATION_STATE_PROPERTY)) {
            target.removeProperty(TagManager.COMPUTATION_STATE_PROPERTY);
        }
    }

    /**
     * The editor of the entity this node belongs to: this editor if its node is an entity, else the parent's.
     *
     * @return an editor, {@code null} outside any entity or when no processor is entity-scoped
     */
    private TagPropagationEditor findEntity()
    {
        if (!this.config.hasEntityScoped()) {
            return null;
        }
        if (this.config.getNodeTypes().isEntity(this.node.getNodeState())) {
            return this;
        }
        return this.parent == null ? null : this.parent.entity;
    }

    /**
     * Whether aggregated tags stop at this node rather than flowing up to its parent.
     *
     * @return {@code true} if this node is a {@code tag:Boundary}
     */
    private boolean isBoundary()
    {
        return this.config.getNodeTypes().isTagBoundary(this.node.getNodeState());
    }

    private NodeState scopeRoot()
    {
        return this.entity == null ? null : this.entity.node.getNodeState();
    }

    private NodeState childScope(final NodeState currentScope, final NodeBuilder child)
    {
        if (!this.config.hasEntityScoped()) {
            return null;
        }
        final NodeState state = child.getNodeState();
        return this.config.getNodeTypes().isEntity(state) ? state : currentScope;
    }

    private NodeState parentState()
    {
        return this.parent == null ? null : this.parent.node.getNodeState();
    }

    private static String childPath(final String parentPath, final String name)
    {
        return "/".equals(parentPath) ? "/" + name : parentPath + "/" + name;
    }

    /**
     * Checks whether a child node is left out of tag processing: hidden Oak nodes, the system and index subtrees, and
     * access control policies.
     *
     * @param name a child node name
     * @return {@code true} if the child must not be processed
     */
    private static boolean skip(final String name)
    {
        return name.charAt(0) == ':' || SKIPPED_NODES.contains(name);
    }

    /**
     * The {@link TagContext} handed to one processor for one node.
     *
     * @since 0.1.0
     */
    private static final class NodeTagContext implements TagContext
    {
        private final NodeState node;

        private final NodeState parent;

        private final String path;

        private final NodeState scopeRoot;

        private final TagPropagationConfig config;

        NodeTagContext(final NodeState node, final NodeState parent, final String path, final NodeState scopeRoot,
            final TagPropagationConfig config)
        {
            this.node = node;
            this.parent = parent;
            this.path = path;
            this.scopeRoot = scopeRoot;
            this.config = config;
        }

        @Override
        public NodeState getNode()
        {
            return this.node;
        }

        @Override
        public NodeState getParent()
        {
            return this.parent;
        }

        @Override
        public String getPath()
        {
            return this.path;
        }

        @Override
        public NodeState getScopeRoot()
        {
            return this.scopeRoot;
        }

        @Override
        public Iterable<? extends ChildNodeEntry> getAggregationSources()
        {
            return StreamSupport.stream(this.node.getChildNodeEntries().spliterator(), false)
                .filter(child -> !skip(child.getName()))
                .filter(child -> !this.config.getNodeTypes().isTagBoundary(child.getNodeState()))
                .toList();
        }

        @Override
        public TagDefinitions getDefinitions()
        {
            return this.config.getDefinitions();
        }
    }
}

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
 * A commit editor keeping the derived tag properties computed by the {@link TagProcessor} services up to date. Each
 * node the commit touches is processed in phase order — the ancestors' tags flow in, the node's own content is
 * examined, then the descendants' tags flow up — and each phase stores the union of what its processors computed in
 * the single property that phase owns. All writes are compare-and-set, so recomputing an unaffected node is harmless
 * and propagation stops as soon as the values stop changing.
 *
 * <p>
 * The traversal only visits the nodes a commit changed, which is not always enough: when what flows down out of a
 * node changes, the new values are pushed into its whole subtree, and when a processor needs a whole
 * {@link Scope#ENTITY entity}, that entity is recomputed as soon as anything inside it changes.
 * </p>
 *
 * <p>
 * A failing processor never fails the commit: losing a user's data because a tag could not be computed would be a
 * far worse outcome than carrying a stale tag, and the editor runs on every commit, including those that have
 * nothing to do with tags. The phase whose processor failed keeps the values it last computed successfully — storing
 * a union that is knowingly missing a contributor would replace good values with worse ones — and the node is
 * marked with {@link TagManager#COMPUTATION_STATE_PROPERTY} so that the tags can be recomputed later, and so that
 * code which must not act on stale tags can tell.
 * </p>
 *
 * <p>
 * That flag is also how a node gets repaired. Any node carrying it is recomputed in full — every phase, over its
 * whole subtree — by the next commit that reaches it, and the flag is cleared once the values are trustworthy again.
 * The incremental paths above all derive what to redo from the commit's diff, which is exactly what a stale node
 * cannot offer: its values went wrong in an earlier commit, or without any commit at all, when a tag definition
 * changed underneath it. Marking a node and letting it heal is therefore the whole of the repair protocol, and
 * anything that knows a node's tags are suspect — a failing processor here, a repair elsewhere — uses the same one.
 * </p>
 *
 * <p>
 * Two rules bound where the writing reaches, both decided by the {@link NodeTypeInspector}. A node is written to only
 * if one of its types <em>declares</em> the tag properties, which is what {@code iap:Taggable} does: types that
 * merely tolerate residual properties have not opted into tags, and strict types that would reject them (file
 * contents, access control entries...) never could. And aggregated tags climb no further than an
 * {@code iap:TagBoundary} — an entity homepage, or anything else that declares itself the top of its own content —
 * which is what keeps them out of the containers above it and out of the repository root.
 * </p>
 *
 * <p>
 * That bound is not cosmetic. Every write this editor makes is attributed to the session that committed, and Oak
 * validates permissions after the editors have run, so a copy travelling further up than the committer can write
 * fails their commit — losing the very data the "a failing processor never fails the commit" rule below exists to
 * protect. An unbounded aggregate reached the repository root, which no session may write and no query can use.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public class TagPropagationEditor extends DefaultEditor
{
    private static final Logger LOGGER = LoggerFactory.getLogger(TagPropagationEditor.class);

    /** Subtrees that can never carry tags: the system and index subtrees, and access control policies. */
    private static final Set<String> SKIPPED_NODES =
        Set.of("jcr:system", "oak:index", "rep:policy", "rep:repoPolicy");

    private final TagPropagationEditor parent;

    private final NodeBuilder node;

    private final String path;

    private final TagPropagationConfig config;

    /** Whether this node was added by the commit being processed. */
    private final boolean added;

    /**
     * The editor of the nearest {@code iap:Entity} ancestor, this editor itself when its own node is one, and
     * {@code null} outside any entity or when no processor needs an entity at all.
     */
    private final TagPropagationEditor entity;

    /** Whether the commit changed this node's own explicit {@code tags} property. */
    private boolean explicitChanged;

    /** Whether the tags belonging to this node, explicit or computed, may have changed. */
    private boolean ownTagsChanged;

    /** Whether the tags flowing up from one of this node's children may have changed. */
    private boolean childContributionChanged;

    /** Whether anything inside this entity changed; only ever set on the editor of an entity node. */
    private boolean entityContentChanged;

    /**
     * Constructor for the repository root, receiving the whole commit.
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
            // An editor only exists for a node the commit changed, so reaching here means this entity's content did
            this.entity.entityContentChanged = true;
        }
        // A node marked stale is recomputed in full instead of incrementally. The incremental path derives what to
        // redo from this commit's diff, and staleness is precisely the case where the diff does not say: the values
        // went wrong in an earlier commit, or without any commit at all, when a tag definition changed under them.
        final boolean stale = isStale(before) || isStale(after);
        clearFailureFlag(this.node);
        if (stale) {
            this.ownTagsChanged = recompute(this.node, parentState(), this.path, scopeRoot());
            return;
        }
        if (this.added) {
            // First computation for a new node; its children, also new, are each visited by the ongoing traversal
            runPhase(Phase.TOP_DOWN, this.node, parentState(), this.path, scopeRoot());
        }
        // Local tags are computed from the node's own content, which any commit reaching this node may have changed
        final boolean localChanged = runPhase(Phase.LOCAL, this.node, parentState(), this.path, scopeRoot());
        this.ownTagsChanged = this.explicitChanged || localChanged;
        if (!this.added && this.ownTagsChanged) {
            // The tags flowing down into the subtree changed; the traversal only visits the nodes the commit
            // changed, so the new values have to be pushed into all the descendants manually
            sweepDown(this.node, this.path, scopeRoot());
        }
    }

    @Override
    public void leave(final NodeState before, final NodeState after)
    {
        if (this.entity == this && this.entityContentChanged) {
            // Something inside this entity changed, so every entity-scoped computation in it may have a new answer
            this.ownTagsChanged |= recompute(this.node, parentState(), this.path, this.node.getNodeState());
        }
        boolean upChanged = false;
        if (this.added || this.childContributionChanged) {
            upChanged = runPhase(Phase.BOTTOM_UP, this.node, parentState(), this.path, scopeRoot());
        }
        final boolean contributes = this.ownTagsChanged || upChanged || this.added;
        // A boundary tells its parent nothing: what it aggregated is its own, and stops here. Only the saved
        // recomputation, though — what makes it opaque is that the aggregation never reads a boundary child at all
        if (this.parent != null && contributes && !isBoundary()) {
            // The tags flowing up from this node may have changed, the parent must recompute
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
            // The deleted subtree may have contributed tags flowing up, recompute on leave
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
     * Recomputes the tags flowing down into a node's descendants, recursing as long as the stored values keep
     * changing. Needed when a node's downward flow changes without its descendants being part of the commit.
     *
     * @param current the node whose children must be refreshed
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
            // A local computation may look at what its node inherited, so it is redone when that changed
            changed |= runPhase(Phase.LOCAL, child, current.getNodeState(), subPath, scope);
            if (changed) {
                sweepDown(child, subPath, scope);
            }
        }
    }

    /**
     * Runs every phase on a whole subtree, in the same order the traversal would, for an entity whose content
     * changed: an entity-scoped processor may return a different answer for any node of the entity, however small
     * the change was.
     *
     * @param current the node to recompute
     * @param currentParent the state of that node's parent, {@code null} for the repository root
     * @param currentPath the path of that node
     * @param scope the entity being recomputed
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
     * that phase owns. A processor throwing leaves the stored value untouched, since a union missing one of its
     * contributors is worse than a slightly stale one, and flags the node instead.
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
                // Recorded against the processor's own class, not this editor's: a broken processor is what has to
                // be fixed, and the framework that called it is the same in every such failure. Once per commit,
                // because a processor that fails on one node fails on every node of that commit
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
     * Stores a derived tag set in a node's property, if it differs from the already stored value. An empty set
     * removes the property. Nodes whose types would reject the property are never written to; they act as
     * propagation boundaries.
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
     * Checks whether a node is marked as having stale derived tags, either because a processor failed on it or
     * because a repair asked for one. The two are the same condition — the stored values may be wrong and only a
     * full recomputation can say — so this asks only whether the property is there, never what it says. The value
     * records who asked and why, for whoever has to diagnose it later.
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
        if (this.config.getNodeTypes().canStoreTags(target.getNodeState())) {
            target.setProperty(TagManager.COMPUTATION_STATE_PROPERTY, TagManager.STATE_FAILED);
        }
    }

    /**
     * Clears the failure flag of a node about to be recomputed. Any phase that fails while recomputing it sets the
     * flag again, so it survives exactly as long as the node's tags may be out of date.
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
     * The editor of the entity this node belongs to, looked up once and then inherited from the parent editor.
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
     * @return {@code true} if this node's types declare it an {@code iap:TagBoundary}
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
     * Checks whether a child node must be left out of tag processing: hidden Oak-internal nodes, the system and
     * index subtrees, and access control policies, none of which can carry tags.
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

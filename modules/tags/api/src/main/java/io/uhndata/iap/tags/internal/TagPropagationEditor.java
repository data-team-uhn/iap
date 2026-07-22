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

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.spi.commit.DefaultEditor;
import org.apache.jackrabbit.oak.spi.commit.Editor;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;

import io.uhndata.iap.tags.api.TagManager;
import io.uhndata.iap.tags.spi.TagDefinitions;
import io.uhndata.iap.tags.spi.TagProcessor;

/**
 * A commit editor keeping the derived tag properties maintained by the {@link TagProcessor} services up to date:
 * whenever the explicit tags in a subtree change, the affected derived properties are recomputed and the change is
 * propagated to the neighboring nodes — up the ancestor chain for {@link TagProcessor.Phase#BOTTOM_UP} processors,
 * down into the subtree for {@link TagProcessor.Phase#TOP_DOWN} ones. All writes are compare-and-set, so
 * recomputing an unaffected node is harmless and propagation stops as soon as values stop changing.
 *
 * <p>
 * Derived properties are only written on nodes whose types accept them, as decided by the
 * {@link TagWritabilityChecker}; strict node types that would reject the properties (file contents, access control
 * entries...) are never touched and act as propagation boundaries.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public class TagPropagationEditor extends DefaultEditor
{
    /** Subtrees that can never carry tags: the system and index subtrees, and access control policies. */
    private static final Set<String> SKIPPED_NODES =
        Set.of("jcr:system", "oak:index", "rep:policy", "rep:repoPolicy");

    private final TagPropagationEditor parent;

    private final NodeBuilder node;

    private final List<TagProcessor> topDown;

    private final List<TagProcessor> bottomUp;

    private final TagDefinitions definitions;

    private final TagWritabilityChecker writability;

    /** Whether this node was added by the commit being processed. */
    private final boolean added;

    /** Whether the commit changed this node's own explicit {@code tags} property. */
    private boolean explicitChanged;

    /** Whether the tags flowing up from one of this node's children may have changed. */
    private boolean childContributionChanged;

    /**
     * Constructor for the repository root, receiving the whole commit.
     *
     * @param builder the root node builder
     * @param definitions the tag definitions in effect for this commit
     * @param writability decides which nodes may carry the derived tag properties
     * @param topDown the processors to invoke on the way down, in invocation order
     * @param bottomUp the processors to invoke on the way up, in invocation order
     */
    public TagPropagationEditor(final NodeBuilder builder, final TagDefinitions definitions,
        final TagWritabilityChecker writability, final List<TagProcessor> topDown,
        final List<TagProcessor> bottomUp)
    {
        this(null, builder, definitions, writability, topDown, bottomUp, false);
    }

    private TagPropagationEditor(final TagPropagationEditor parent, final NodeBuilder node,
        final TagDefinitions definitions, final TagWritabilityChecker writability,
        final List<TagProcessor> topDown, final List<TagProcessor> bottomUp, final boolean added)
    {
        this.parent = parent;
        this.node = node;
        this.definitions = definitions;
        this.writability = writability;
        this.topDown = topDown;
        this.bottomUp = bottomUp;
        this.added = added;
    }

    @Override
    public void enter(final NodeState before, final NodeState after)
    {
        this.explicitChanged =
            !Objects.equals(before.getProperty(TagManager.TAGS_PROPERTY), after.getProperty(TagManager.TAGS_PROPERTY));
        if (this.added) {
            // First computation for a new node; its children, also new, are each visited by the ongoing traversal
            runProcessors(this.topDown, this.node, parentState());
        } else if (this.explicitChanged) {
            // The tags flowing down into the subtree changed; the traversal only visits nodes changed by the
            // commit, so manually push the new values into all the children
            sweepDown(this.node);
        }
    }

    @Override
    public void leave(final NodeState before, final NodeState after)
    {
        boolean upChanged = false;
        if (this.added || this.childContributionChanged) {
            upChanged = runProcessors(this.bottomUp, this.node, parentState());
        }
        if (this.parent != null && (this.explicitChanged || upChanged || this.added)) {
            // The tags flowing up from this node may have changed, the parent must recompute
            this.parent.childContributionChanged = true;
        }
    }

    @Override
    public Editor childNodeAdded(final String name, final NodeState after)
    {
        if (skip(name)) {
            return null;
        }
        return new TagPropagationEditor(this, this.node.getChildNode(name), this.definitions, this.writability,
            this.topDown, this.bottomUp, true);
    }

    @Override
    public Editor childNodeChanged(final String name, final NodeState before, final NodeState after)
    {
        if (skip(name)) {
            return null;
        }
        return new TagPropagationEditor(this, this.node.getChildNode(name), this.definitions, this.writability,
            this.topDown, this.bottomUp, false);
    }

    @Override
    public Editor childNodeDeleted(final String name, final NodeState before)
    {
        if (!skip(name)) {
            // The deleted subtree may have contributed tags flowing up, recompute on leave
            this.childContributionChanged = true;
        }
        return null;
    }

    /**
     * Recomputes the tags flowing down into a node's children, recursing as long as the stored values keep
     * changing. Needed when a node's downward flow changes without its descendants being part of the commit.
     *
     * @param current the node whose children must be refreshed
     */
    private void sweepDown(final NodeBuilder current)
    {
        for (final String name : current.getChildNodeNames()) {
            if (skip(name)) {
                continue;
            }
            final NodeBuilder child = current.getChildNode(name);
            if (runProcessors(this.topDown, child, current.getNodeState())) {
                sweepDown(child);
            }
        }
    }

    /**
     * Invokes each of the given processors on a node, storing the results in the processors' properties.
     *
     * @param processors the processors to invoke, in order
     * @param target the node to process
     * @param targetParent the state of the node's parent, {@code null} for the repository root
     * @return {@code true} if any of the stored properties changed
     */
    private boolean runProcessors(final List<TagProcessor> processors, final NodeBuilder target,
        final NodeState targetParent)
    {
        boolean changed = false;
        for (final TagProcessor processor : processors) {
            final Set<String> computed =
                processor.computeTags(target.getNodeState(), targetParent, this.definitions);
            changed |= write(target, processor.getPropertyName(), computed);
        }
        return changed;
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
        if (!this.writability.canStoreTags(target.getNodeState())) {
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

    private NodeState parentState()
    {
        return this.parent == null ? null : this.parent.node.getNodeState();
    }

    /**
     * Checks whether a child node must be left out of tag processing: hidden Oak-internal nodes, the system and
     * index subtrees, and access control policies, none of which can carry tags.
     *
     * @param name a child node name
     * @return {@code true} if the child must not be processed
     */
    private boolean skip(final String name)
    {
        return name.charAt(0) == ':' || SKIPPED_NODES.contains(name);
    }
}

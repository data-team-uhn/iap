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
package io.uhndata.iap.entities.internal;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.spi.commit.DefaultEditor;
import org.apache.jackrabbit.oak.spi.commit.Editor;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStateUtils;

import io.uhndata.iap.entities.nodetypes.NodeTypeRules;

/**
 * A commit editor keeping {@code jcr:lastModified} and {@code jcr:lastModifiedBy} current on every node whose type is
 * {@code mix:lastModified}, so that no writer has to remember to.
 *
 * <p>A node is modified when its own content changes, or the content of a child that is part of it, as
 * {@link NodeTypeRules} decides. Reordering the children that are part of it counts too, since a version keeps their
 * order. A modification climbs to the parent until it reaches a {@code data:Entity}.</p>
 *
 * <p>Only what the commit changed is visited. A node the commit adds keeps the stamp it comes with: Oak sets one when
 * a session creates it. Oak's own hidden items are never content.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class LastModifiedEditor extends DefaultEditor
{
    /** The hidden property where Oak keeps the order of an orderable node's children. */
    private static final String CHILD_ORDER = ":childOrder";

    /** The editor of the parent node, {@code null} for the repository root. */
    private final LastModifiedEditor parent;

    /** This node's name under its parent, {@code null} for the repository root. */
    private final String name;

    private final NodeBuilder builder;

    /** This node as it was before the commit. */
    private final NodeState original;

    private final NodeState node;

    private final NodeTypeRules rules;

    private final ModificationStamp stamp;

    private boolean modified;

    /**
     * Constructor for the repository root, receiving the whole commit.
     *
     * @param builder the root node builder
     * @param before the root before the commit
     * @param after the root as committed
     * @param rules the node type rules of this commit
     * @param stamp the modification stamp of this commit
     */
    LastModifiedEditor(final NodeBuilder builder, final NodeState before, final NodeState after,
        final NodeTypeRules rules, final ModificationStamp stamp)
    {
        this.parent = null;
        this.name = null;
        this.builder = builder;
        this.original = before;
        this.node = after;
        this.rules = rules;
        this.stamp = stamp;
    }

    private LastModifiedEditor(final LastModifiedEditor parent, final String name, final NodeState before,
        final NodeState after)
    {
        this.parent = parent;
        this.name = name;
        this.builder = parent.builder.getChildNode(name);
        this.original = before;
        this.node = after;
        this.rules = parent.rules;
        this.stamp = parent.stamp;
    }

    @Override
    public void propertyAdded(final PropertyState after)
    {
        recordPropertyChange(after);
    }

    @Override
    public void propertyChanged(final PropertyState before, final PropertyState after)
    {
        recordPropertyChange(after);
    }

    @Override
    public void propertyDeleted(final PropertyState before)
    {
        recordPropertyChange(before);
    }

    @Override
    public Editor childNodeAdded(final String childName, final NodeState after)
    {
        recordChildChange(childName, after);
        return null;
    }

    @Override
    public Editor childNodeChanged(final String childName, final NodeState before, final NodeState after)
    {
        return NodeStateUtils.isHidden(childName) ? null : new LastModifiedEditor(this, childName, before, after);
    }

    @Override
    public Editor childNodeDeleted(final String childName, final NodeState before)
    {
        recordChildChange(childName, before);
        return null;
    }

    @Override
    public void leave(final NodeState before, final NodeState after)
    {
        if (!this.modified) {
            return;
        }
        if (this.rules.tracksModifications(this.node)) {
            this.stamp.applyTo(this.builder);
        }
        if (modifiesParent()) {
            this.parent.markModified();
        }
    }

    /**
     * Whether modifying this node modifies its parent: it is part of the parent, and not an entity of its own.
     *
     * @return {@code false} for the repository root
     */
    private boolean modifiesParent()
    {
        return this.parent != null && !this.rules.isEntity(this.node)
            && !this.rules.isUnversionedChild(this.parent.node, this.name, this.node);
    }

    private void markModified()
    {
        this.modified = true;
    }

    private void recordPropertyChange(final PropertyState property)
    {
        if (this.modified) {
            return;
        }
        if (CHILD_ORDER.equals(property.getName())) {
            this.modified = isReordered();
        } else if (!NodeStateUtils.isHidden(property.getName())) {
            this.modified = !this.rules.isUnversionedProperty(this.node, property);
        }
    }

    private void recordChildChange(final String childName, final NodeState child)
    {
        if (!this.modified && !NodeStateUtils.isHidden(childName)) {
            this.modified = !this.rules.isUnversionedChild(this.node, childName, child);
        }
    }

    /**
     * Whether the children that are part of this node changed order. Children added or removed are counted on their
     * own, so only those present both before and after are compared.
     *
     * @return {@code true} if they are in a different order
     */
    private boolean isReordered()
    {
        final List<String> after = orderOf(this.node);
        final Set<String> stayed = orderOf(this.original).stream().filter(after::contains)
            .filter(child -> !this.rules.isUnversionedChild(this.node, child, this.node.getChildNode(child)))
            .collect(Collectors.toSet());
        return !orderOf(this.original).stream().filter(stayed::contains).toList()
            .equals(after.stream().filter(stayed::contains).toList());
    }

    /**
     * The order of a node's children: as Oak records it once the order has been set, as it stores them before.
     *
     * @param state the node
     * @return the child names, in order
     */
    private static List<String> orderOf(final NodeState state)
    {
        final PropertyState order = state.getProperty(CHILD_ORDER);
        final Iterable<String> names = order == null ? state.getChildNodeNames() : order.getValue(Type.NAMES);
        return StreamSupport.stream(names.spliterator(), false).toList();
    }
}

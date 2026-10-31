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

import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.spi.commit.DefaultEditor;
import org.apache.jackrabbit.oak.spi.commit.Editor;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;

/**
 * A commit editor keeping {@code jcr:lastModified} and {@code jcr:lastModifiedBy} current on every node that has
 * them, whoever commits. No writer has to remember to.
 *
 * <p>A node is modified when its own content changes, or the content of a child that is part of it. A node's content
 * is what a version of it keeps, so whatever its type marks {@code IGNORE} is left out, such as tags, workflow
 * instances and links. A modification climbs to the parent until it reaches an entity, so a changed answer modifies
 * its submission, and the submission's homepage is never touched.</p>
 *
 * <p>The traversal visits only what the commit changed. A node the commit adds is not stamped here: it got its
 * stamp when it was created.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
class LastModifiedEditor extends DefaultEditor
{
    private final LastModifiedEditor parent;

    private final NodeBuilder builder;

    private final NodeState node;

    /** Whether this node is part of its parent, so that modifying it modifies the parent. */
    private final boolean partOfParent;

    private final NodeTypeRules rules;

    private final ModificationStamp stamp;

    private boolean modified;

    /**
     * Constructor for the repository root, receiving the whole commit.
     *
     * @param builder the root node builder
     * @param root the root as committed
     * @param rules the node type rules of this commit
     * @param stamp the modification stamp of this commit
     */
    LastModifiedEditor(final NodeBuilder builder, final NodeState root, final NodeTypeRules rules,
        final ModificationStamp stamp)
    {
        this(null, builder, root, false, rules, stamp);
    }

    private LastModifiedEditor(final LastModifiedEditor parent, final NodeBuilder builder, final NodeState node,
        final boolean partOfParent, final NodeTypeRules rules, final ModificationStamp stamp)
    {
        this.parent = parent;
        this.builder = builder;
        this.node = node;
        this.partOfParent = partOfParent;
        this.rules = rules;
        this.stamp = stamp;
    }

    @Override
    public void propertyAdded(final PropertyState after)
    {
        changed(after);
    }

    @Override
    public void propertyChanged(final PropertyState before, final PropertyState after)
    {
        changed(after);
    }

    @Override
    public void propertyDeleted(final PropertyState before)
    {
        changed(before);
    }

    @Override
    public Editor childNodeAdded(final String name, final NodeState after)
    {
        childChanged(name, after);
        return null;
    }

    @Override
    public Editor childNodeChanged(final String name, final NodeState before, final NodeState after)
    {
        return new LastModifiedEditor(this, this.builder.getChildNode(name), after,
            !this.rules.isUnversionedChild(this.node, name, after), this.rules, this.stamp);
    }

    @Override
    public Editor childNodeDeleted(final String name, final NodeState before)
    {
        childChanged(name, before);
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
        if (this.partOfParent && !this.rules.isEntity(this.node)) {
            this.parent.modified = true;
        }
    }

    private void changed(final PropertyState property)
    {
        this.modified |= !this.rules.isUnversionedProperty(this.node, property.getName());
    }

    private void childChanged(final String name, final NodeState child)
    {
        this.modified |= !this.rules.isUnversionedChild(this.node, name, child);
    }
}

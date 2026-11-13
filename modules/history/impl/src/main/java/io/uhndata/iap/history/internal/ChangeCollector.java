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
package io.uhndata.iap.history.internal;

import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.spi.commit.DefaultEditor;
import org.apache.jackrabbit.oak.spi.commit.Editor;
import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStateUtils;

import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.nodetypes.NodeTypeRules;
import io.uhndata.iap.history.models.LastSeen;

/**
 * Walks a commit and notes, in a {@link ChangeLog}, what it did to each entity. A change belongs to the nearest
 * {@code data:Entity} at or above it, and a change outside every entity is not noted.
 *
 * <p>Within an entity a changed property is noted by its path. An added or removed branch is noted by its topmost node
 * alone, and every entity inside it as created or deleted. A node replaced by another of the same name reaches an
 * editor as changed. When the two have different {@code jcr:uuid}s, it is noted as one removed and one added.</p>
 *
 * <p>Some changes are not actions, and are left out: a value derived from elsewhere ({@code COMPUTE}), the
 * modification stamps, the revision token, and read markers. So is the engine's own state under
 * {@code wf:instances}, which the action already names. The repository's own trees, {@code jcr:system} and
 * {@code oak:index}, hold no entity and are not walked.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ChangeCollector extends DefaultEditor
{
    private static final Set<String> SKIPPED_PROPERTIES =
        Set.of("jcr:lastModified", "jcr:lastModifiedBy", Entity.REVISION_PROPERTY);

    private static final Set<String> SKIPPED_CHILDREN = Set.of("wf:instances", LastSeen.NODE_NAME);

    private static final Set<String> SKIPPED_AT_ROOT = Set.of("jcr:system", "oak:index");

    private static final String UUID_PROPERTY = "jcr:uuid";

    private final String path;

    private final NodeState node;

    /** The entity this node is part of, {@code null} if it is part of none. */
    private final Subject entity;

    private final NodeTypeRules rules;

    private final EntityLocator entities;

    private final ChangeLog log;

    /**
     * Constructor for the repository root, receiving the whole commit.
     *
     * @param after the root as committed
     * @param rules the node type rules of this commit
     * @param entities finds the entity each node is part of
     * @param log where to note the changes
     */
    ChangeCollector(final NodeState after, final NodeTypeRules rules, final EntityLocator entities,
        final ChangeLog log)
    {
        this("", after, null, rules, entities, log);
    }

    private ChangeCollector(final String path, final NodeState node, final Subject entity, final NodeTypeRules rules,
        final EntityLocator entities, final ChangeLog log)
    {
        this.path = path;
        this.node = node;
        this.entity = entity;
        this.rules = rules;
        this.entities = entities;
        this.log = log;
    }

    @Override
    public void propertyAdded(final PropertyState after)
    {
        noteProperty(after);
    }

    @Override
    public void propertyChanged(final PropertyState before, final PropertyState after)
    {
        noteProperty(after);
    }

    @Override
    public void propertyDeleted(final PropertyState before)
    {
        noteProperty(before);
    }

    @Override
    public Editor childNodeAdded(final String name, final NodeState after)
    {
        if (!skips(name)) {
            noteBranch(name, after, this.log::noteAdded, this.log::noteCreated);
        }
        return null;
    }

    @Override
    public Editor childNodeChanged(final String name, final NodeState before, final NodeState after)
    {
        if (skips(name)) {
            return null;
        }
        if (!Objects.equals(before.getString(UUID_PROPERTY), after.getString(UUID_PROPERTY))) {
            childNodeDeleted(name, before);
            return childNodeAdded(name, after);
        }
        final String childPath = childPath(name);
        return new ChangeCollector(childPath, after, this.entities.entityAt(childPath, after, this.entity),
            this.rules, this.entities, this.log);
    }

    @Override
    public Editor childNodeDeleted(final String name, final NodeState before)
    {
        if (!skips(name)) {
            noteBranch(name, before, this.log::noteRemoved, this.log::noteDeleted);
        }
        return null;
    }

    private void noteProperty(final PropertyState property)
    {
        final String name = property.getName();
        if (this.entity != null && !NodeStateUtils.isHidden(name) && !SKIPPED_PROPERTIES.contains(name)
            && !this.rules.isComputedProperty(this.node, property)) {
            this.log.noteChanged(this.entity, relative(childPath(name)));
        }
    }

    /**
     * Notes a branch added or removed as a whole: as an entity of its own, or as a branch of the entity holding it.
     * Every entity inside it is noted the same way.
     *
     * @param name the name of the branch's topmost node
     * @param top the branch's topmost node, as it was before the commit if the commit removes it
     * @param asBranch how to note a branch of the entity holding it
     * @param asEntity how to note an entity
     */
    private void noteBranch(final String name, final NodeState top, final BiConsumer<Subject, String> asBranch,
        final Consumer<Subject> asEntity)
    {
        final String topPath = childPath(name);
        final Subject own = this.entities.entityAt(topPath, top, null);
        if (own != null) {
            asEntity.accept(own);
        } else if (this.entity != null) {
            asBranch.accept(this.entity, relative(topPath));
        }
        noteEntitiesWithin(topPath, top, asEntity);
    }

    private void noteEntitiesWithin(final String branchPath, final NodeState branch, final Consumer<Subject> asEntity)
    {
        for (final ChildNodeEntry child : branch.getChildNodeEntries()) {
            if (isUnrecorded(child.getName())) {
                continue;
            }
            final String childPath = branchPath + "/" + child.getName();
            final Subject nested = this.entities.entityAt(childPath, child.getNodeState(), null);
            if (nested != null) {
                asEntity.accept(nested);
            }
            noteEntitiesWithin(childPath, child.getNodeState(), asEntity);
        }
    }

    private boolean skips(final String name)
    {
        return isUnrecorded(name) || (this.path.isEmpty() && SKIPPED_AT_ROOT.contains(name));
    }

    private static boolean isUnrecorded(final String name)
    {
        return NodeStateUtils.isHidden(name) || SKIPPED_CHILDREN.contains(name);
    }

    private String childPath(final String name)
    {
        return this.path + "/" + name;
    }

    private String relative(final String itemPath)
    {
        return itemPath.substring(this.entity.path().length() + 1);
    }
}

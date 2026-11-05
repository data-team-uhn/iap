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

import org.apache.jackrabbit.oak.spi.state.NodeState;

import io.uhndata.iap.entities.nodetypes.NodeTypeRules;

/**
 * Finds the entity a node is part of: the node itself if it is a {@code data:Entity}, else the nearest one above it.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class EntityLocator
{
    private final NodeTypeRules rules;

    /**
     * Constructor.
     *
     * @param rules the node type rules of the commit
     */
    EntityLocator(final NodeTypeRules rules)
    {
        this.rules = rules;
    }

    /**
     * The entity a node is part of, knowing the one its parent is part of.
     *
     * @param path the node's absolute path
     * @param node the node
     * @param enclosing the entity its parent is part of, {@code null} if none
     * @return the node itself if it is an entity, else the enclosing one
     */
    Subject entityAt(final String path, final NodeState node, final Subject enclosing)
    {
        final Subject own = this.rules.isEntity(node) ? Subject.of(path, node) : null;
        return own == null ? enclosing : own;
    }
}

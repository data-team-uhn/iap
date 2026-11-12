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
package io.uhndata.iap.tags.spi;

import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What a {@link TagProcessor} may read while computing the derived tags of one node. The commit editor creates it for
 * one node of one commit, and the processor must not keep it after the {@link TagProcessor#computeTags} call.
 *
 * <p>
 * The context offers only what the processor's {@link TagProcessor#getScope() scope} covers. The editor recomputes a
 * node only when something in that scope changes, so tags computed from anything else would go stale. Only an
 * {@link TagProcessor.Scope#ENTITY} processor gets a {@link #getScopeRoot() scope root}.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface TagContext
{
    /**
     * The current state of the node being processed, with the derived tag properties already recomputed for it in
     * this commit.
     *
     * @return a node state, never {@code null}
     */
    @NotNull
    NodeState getNode();

    /**
     * The current state of the processed node's parent. See {@link TagProcessor.Phase} for which of its derived tags
     * are recomputed by then.
     *
     * @return a node state, {@code null} when the processed node is the repository root
     */
    @Nullable
    NodeState getParent();

    /**
     * The absolute path of the node being processed, such as for resolving a relative reference or for logging.
     *
     * @return an absolute path, {@code /} for the repository root
     */
    @NotNull
    String getPath();

    /**
     * The state of the {@code data:Entity} node enclosing the processed node, which an
     * {@link TagProcessor.Scope#ENTITY} processor may read in full. When the processed node is an entity, it is its
     * own scope root.
     *
     * @return a node state, {@code null} for a {@link TagProcessor.Scope#NODE} processor, and for an
     *         {@link TagProcessor.Scope#ENTITY} one processing a node outside any entity
     */
    @Nullable
    NodeState getScopeRoot();

    /**
     * The children whose tags may flow up into the node being processed. A {@link TagProcessor.Phase#BOTTOM_UP}
     * processor must aggregate over these, not over the node's own child list. They leave out the children that take
     * no part in tagging, such as hidden nodes and access control policies, and every {@code tag:Boundary}.
     *
     * <p>
     * A copy that climbed past a boundary would fail the commit of a session that may not write where it lands.
     * </p>
     *
     * @return the child entries that may contribute, never {@code null}
     */
    @NotNull
    Iterable<? extends ChildNodeEntry> getAggregationSources();

    /**
     * The tag definitions in effect for this commit.
     *
     * @return the definitions, never {@code null}
     */
    @NotNull
    TagDefinitions getDefinitions();
}

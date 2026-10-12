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
package io.uhndata.iap.utils.move;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Moves content: a node and everything under it, into a new parent or to another place among its siblings. Before a
 * node's path changes, each {@link MoveParticipant} can update whatever names the node by its path.
 *
 * <p>The move is made in the session of the nodes given, and saved with it: in a workflow, as part of the engine's
 * single commit.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface ContentMover
{
    /**
     * Moves a node into a parent, under a name, before one of the parent's children or else last.
     *
     * @param node the node moved
     * @param parent where it goes; its current parent to only change its place among its siblings
     * @param name the name it takes there: one the parent does not have yet, such as one
     *     {@code NodeNameUtils.findFreeName} gives, or the node's current name in its current parent
     * @param before the name of the child of the parent it goes before, or {@code null} to place it last
     * @return the node's path once moved
     * @throws RepositoryException when the node cannot be moved there
     */
    @NotNull
    String move(@NotNull Node node, @NotNull Node parent, @NotNull String name, @Nullable String before)
        throws RepositoryException;
}

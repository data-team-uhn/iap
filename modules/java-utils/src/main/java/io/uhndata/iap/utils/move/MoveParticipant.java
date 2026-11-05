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

/**
 * Lets a module that names nodes by their paths update those paths when content moves, before the move is made.
 * Reordering a node among its siblings changes no path, so participants are not called for it.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface MoveParticipant
{
    /**
     * Called before a node's path changes, by a move or a rename, to update the values that name it or a node under
     * it by its path.
     *
     * @param node the node about to move, still at its old path
     * @param newPath the node's absolute path after the move
     * @throws RepositoryException when the preparation cannot be made
     */
    void beforeMove(@NotNull Node node, @NotNull String newPath) throws RepositoryException;
}

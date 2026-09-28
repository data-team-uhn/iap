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

/**
 * Moves content: a node, with everything under it, into a parent, or to another place among its siblings. Anything
 * that names a moved node by where it is gets the chance to name it otherwise before its path changes, from the
 * modules that know, as {@link MoveParticipant}s.
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
     * @param parent where it goes, its own parent to only change its place there
     * @param name the name it takes there, which the parent must not have yet unless it is the node's own parent and
     *            the node's own name, such as one {@code NodeNameUtils.findFreeName} gives
     * @param before the name of the child of the parent it goes before, or {@code null} to place it last
     * @return the node's path once moved
     * @throws RepositoryException when the node cannot be moved there
     */
    String move(Node node, Node parent, String name, String before) throws RepositoryException;
}

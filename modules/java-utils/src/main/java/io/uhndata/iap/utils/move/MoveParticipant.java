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
 * What a module does when content moves. A module that names nodes by where they are, such as a condition naming a
 * question by its path, keeps those names working; changing only a node's place among its siblings changes no path,
 * and involves no participant.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface MoveParticipant
{
    /**
     * Prepares for a node's path to change, e.g. naming it and what is under it in a way that does not depend on
     * where they are.
     *
     * @param node the node about to move, still where it was
     * @param newPath where it will be, which tells, for instance, whether it leaves the content it was part of
     * @throws RepositoryException when the preparation cannot be made
     */
    void beforeMove(Node node, String newPath) throws RepositoryException;
}

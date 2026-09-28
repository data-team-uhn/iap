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
package io.uhndata.iap.workflows.internal;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

/**
 * Makes versionable content writable. Content created through the Sling POST servlet is checked in, which makes it
 * and everything under it read-only; a task about to change it checks out whichever versionable node holds it, as
 * the POST servlet's own auto-checkout would. A checkout takes effect at once, outside the engine's commit, so a
 * refused change leaves the node checked out, which changes nothing about what it holds.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class VersionableContent
{
    private VersionableContent()
    {
        // Utility class
    }

    /**
     * Checks out the versionable node holding a node, if it is checked in.
     *
     * @param node the node about to change, or whose children are about to change
     * @throws RepositoryException when the checkout fails
     */
    static void checkOut(final Node node) throws RepositoryException
    {
        if (node.isCheckedOut()) {
            return;
        }
        Node versionable = node;
        while (!versionable.isNodeType("mix:versionable")) {
            versionable = versionable.getParent();
        }
        versionable.getSession().getWorkspace().getVersionManager().checkout(versionable.getPath());
    }
}

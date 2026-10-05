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
package io.uhndata.iap.utils;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.version.VersionException;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Making versioned content writable. A checked-in versionable node makes itself and everything under it read-only,
 * so before such content is modified, whichever versionable node holds it has to be checked out.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class VersioningUtils
{
    private VersioningUtils()
    {
    }

    /**
     * Makes a node writable: when it is read-only because it, or the versionable node above it, is checked in,
     * checks that versionable node out. A checkout takes effect at once, outside any pending changes, so it stays
     * even if those changes are then discarded; that changes nothing about what the node holds.
     *
     * @param node the node about to be modified, or whose children are about to change
     * @return the path of the versionable node checked out, for whoever means to check it back in, or {@code null}
     *         when the node was writable already
     * @throws VersionException when the node is read-only, yet neither it nor anything above it is versionable
     * @throws RepositoryException when the checkout fails
     */
    @Nullable
    public static String checkOut(@NotNull final Node node) throws RepositoryException
    {
        if (node.isCheckedOut()) {
            return null;
        }
        Node versionable = node;
        while (!versionable.isNodeType("mix:versionable")) {
            if (versionable.getDepth() == 0) {
                throw new VersionException(node.getPath()
                    + " is read-only, but neither it nor anything above it is versionable");
            }
            versionable = versionable.getParent();
        }
        final String path = versionable.getPath();
        versionable.getSession().getWorkspace().getVersionManager().checkout(path);
        return path;
    }
}

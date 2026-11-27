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
import javax.jcr.Session;
import javax.jcr.version.Version;
import javax.jcr.version.VersionException;
import javax.jcr.version.VersionManager;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Makes versioned content writable, and read-only again afterwards. A checked-in versionable node is read-only, with
 * everything under it, so the versionable node holding content has to be checked out before the content changes.
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
     * Makes a node writable. If the node is read-only because it, or the nearest versionable node above it, is
     * checked in, that versionable node is checked out. A checkout takes effect at once, outside the session's
     * pending changes, so discarding them does not undo it. It changes nothing the node holds.
     *
     * @param node the node about to be modified, or whose children are about to change
     * @return the path of the versionable node checked out, for a caller that checks it back in; {@code null} if the
     *         node was writable already
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

    /**
     * Makes a resource writable, as {@link #checkOut(Node)} does a node. A resource that is not a node is under no
     * version control, so it is writable already.
     *
     * @param resource the resource about to be modified, or whose children are about to change
     * @return the path of the versionable node checked out, for a caller that checks it back in; {@code null} if the
     *         resource was writable already
     * @throws PersistenceException when the checkout fails, or nothing versionable holds the resource read-only
     */
    @Nullable
    public static String checkOut(@NotNull final Resource resource) throws PersistenceException
    {
        final Node node = resource.adaptTo(Node.class);
        if (node == null) {
            return null;
        }
        try {
            return checkOut(node);
        } catch (final RepositoryException e) {
            throw new PersistenceException("Cannot check out " + resource.getPath(), e);
        }
    }

    /**
     * Checks a versionable node back in, making it read-only again. A check-in refuses pending changes, so it comes
     * after the changes made while the node was checked out are saved or discarded. A node that no longer exists, or
     * that is checked in already, is left alone.
     *
     * @param resolver the resolver whose JCR session checks the node in; nothing is checked in if it has no session
     * @param path the absolute path of the versionable node, as {@link #checkOut(Resource)} returns it
     * @throws PersistenceException when the check-in fails
     */
    public static void checkIn(@NotNull final ResourceResolver resolver, @NotNull final String path)
        throws PersistenceException
    {
        final Session session = resolver.adaptTo(Session.class);
        if (session == null) {
            return;
        }
        try {
            final VersionManager versions = session.getWorkspace().getVersionManager();
            if (session.nodeExists(path) && versions.isCheckedOut(path)) {
                versions.checkin(path);
            }
        } catch (final RepositoryException e) {
            throw new PersistenceException("Cannot check in " + path, e);
        }
    }

    /**
     * Records a version of a versionable node: checks it in, which saves a copy of it in its version history, then
     * checks it out again, so it stays writable. A check-in refuses pending changes and commits by itself, so it comes
     * after the changes the version should hold are saved. A node that is checked in already is checked out again,
     * and its current version is the one recorded.
     *
     * @param resolver the resolver whose JCR session records the version
     * @param path the absolute path of the versionable node
     * @return the path of the version; {@code null} if the resolver has no JCR session, or the node no longer exists
     * @throws PersistenceException when the version cannot be recorded
     */
    @Nullable
    public static String recordVersion(@NotNull final ResourceResolver resolver, @NotNull final String path)
        throws PersistenceException
    {
        final Session session = resolver.adaptTo(Session.class);
        try {
            if (session == null || !session.nodeExists(path)) {
                return null;
            }
            final VersionManager versions = session.getWorkspace().getVersionManager();
            final Version version = versions.checkin(path);
            versions.checkout(path);
            return version.getPath();
        } catch (final RepositoryException e) {
            throw new PersistenceException("Cannot record a version of " + path, e);
        }
    }
}

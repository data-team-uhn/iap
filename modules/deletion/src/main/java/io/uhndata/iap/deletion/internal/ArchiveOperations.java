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
package io.uhndata.iap.deletion.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.ReferentialIntegrityException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.version.OnParentVersionAction;
import javax.jcr.version.VersionManager;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.deletion.api.DeletionException;
import io.uhndata.iap.deletion.api.DeletionService;
import io.uhndata.iap.deletion.api.RestoreConflict;
import io.uhndata.iap.deletion.api.RestoreResult;
import io.uhndata.iap.links.models.Link;
import io.uhndata.iap.utils.PrefixTree;
import io.uhndata.iap.utils.VersioningUtils;

/**
 * Makes the changes of a {@link DeletionPlan} that nothing forbids: archives or removes what it marks, and removes
 * its links. It also restores and purges archive entries. The changes go through the session of the resolver it is
 * bound to.
 *
 * <p>
 * Every {@code data:Entity} is versionable, and Oak refuses to change the children of a checked-in versionable node.
 * Before each move or removal, the closest versionable ancestor is checked out. After the save, the nodes this
 * operation checked out are checked back in, and no others. A pending deletion leaves the check-in to whoever
 * commits. A node whose definition sets the {@code IGNORE} on-parent-version action, such as a link, needs no
 * check-out.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ArchiveOperations
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ArchiveOperations.class);

    private final ResourceResolver resolver;

    private final Session session;

    private final VersionManager versionManager;

    /** The paths of the versionable ancestors this operation checked out, to check back in after the save. */
    private final Set<String> checkedOut = new LinkedHashSet<>();

    private ArchiveOperations(final ResourceResolver resolver, final Session session,
        final VersionManager versionManager)
    {
        this.resolver = resolver;
        this.session = session;
        this.versionManager = versionManager;
    }

    /**
     * Operations that make their changes through a resolver.
     *
     * @param resolver the resolver to make the changes through, with write access to the archive
     * @return operations bound to the resolver's session
     * @throws RepositoryException if the session's version manager is unavailable
     */
    static ArchiveOperations forResolver(final ResourceResolver resolver)
        throws RepositoryException
    {
        final Session session = resolver.adaptTo(Session.class);
        if (session == null) {
            throw new DeletionException("The deleting resolver is not backed by a repository session", null);
        }
        return new ArchiveOperations(resolver, session, session.getWorkspace().getVersionManager());
    }

    /**
     * Move every subtree the plan marks into a new archive entry, remove the plan's links, and commit.
     *
     * @param plan a plan that nothing forbids
     * @param userId the user id to record on the entry as having deleted the resources
     * @return the path of the new archive entry, inside the {@link PrefixTree prefix tree} under the archive
     * @throws RepositoryException if the changes cannot be made
     */
    String store(final DeletionPlan plan, final String userId) throws RepositoryException
    {
        // PrefixTree.bucketFor saves the buckets it creates, so it has to run before anything else is pending
        final String entryName = UUID.randomUUID().toString();
        final Node bucket = PrefixTree.bucketFor(this.session.getNode(DeletionService.ARCHIVE_PATH),
            entryName, DeletionService.ARCHIVE_NODETYPE);
        final String entryPath = this.fillEntry(bucket, entryName, plan, userId);
        this.saveAndCheckin();
        return entryPath;
    }

    /**
     * Move every subtree the plan marks into a new archive entry and remove the plan's links. Nothing is committed,
     * the buckets of the archive included.
     *
     * @param plan a plan that nothing forbids
     * @param userId the user id to record on the entry as having deleted the resources
     * @return the path the archive entry has once committed
     * @throws RepositoryException if the changes cannot be made
     * @throws PersistenceException if a missing bucket of the archive cannot be added
     */
    String storePending(final DeletionPlan plan, final String userId)
        throws RepositoryException, PersistenceException
    {
        final String entryName = UUID.randomUUID().toString();
        final Resource archive = this.resolver.getResource(DeletionService.ARCHIVE_PATH);
        if (archive == null) {
            throw new DeletionException("The archive is not visible to the session deleting", null);
        }
        final Resource bucket = PrefixTree.pendingBucketFor(archive, entryName, DeletionService.ARCHIVE_NODETYPE);
        return this.fillEntry(this.session.getNode(bucket.getPath()), entryName, plan, userId);
    }

    /**
     * Remove every subtree the plan marks for good, remove the plan's links, and commit.
     *
     * @param plan a plan that nothing forbids
     * @throws RepositoryException if the changes cannot be made
     */
    void deletePermanently(final DeletionPlan plan) throws RepositoryException
    {
        this.deletePermanentlyPending(plan);
        this.saveAndCheckin();
    }

    /**
     * Remove every subtree the plan marks for good, and remove the plan's links. Nothing is committed.
     *
     * @param plan a plan that nothing forbids
     * @throws RepositoryException if the changes cannot be made
     */
    void deletePermanentlyPending(final DeletionPlan plan) throws RepositoryException
    {
        this.removeLinks(plan);
        for (final String rootPath : plan.getRoots().keySet()) {
            final Node root = this.session.getNode(rootPath);
            this.checkoutForRemoval(root);
            root.remove();
        }
    }

    /**
     * The versionable nodes this operation checked out and has not checked back in.
     *
     * @return their paths, in the order they were checked out; empty if there are none
     */
    List<String> checkedOut()
    {
        return List.copyOf(this.checkedOut);
    }

    private String fillEntry(final Node bucket, final String entryName, final DeletionPlan plan, final String userId)
        throws RepositoryException
    {
        final Node entry = bucket.addNode(entryName, DeletionService.ENTRY_NODETYPE);
        entry.setProperty(DeletionService.DELETED_BY_PROPERTY, userId);
        entry.setProperty(DeletionService.REQUESTED_PATH_PROPERTY, plan.getRequestedPath());
        // Links go first: removing a link with its backlink finds the backlink by path, before anything moves
        this.removeLinks(plan);
        int index = 0;
        for (final String rootPath : plan.getRoots().keySet()) {
            final Node root = this.session.getNode(rootPath);
            this.checkoutForRemoval(root);
            final Node wrapper = entry.addNode(String.valueOf(index), DeletionService.ITEM_NODETYPE);
            wrapper.setProperty(DeletionService.ORIGINAL_PATH_PROPERTY, rootPath);
            this.session.move(rootPath, wrapper.getPath() + "/" + root.getName());
            index++;
        }
        return entry.getPath();
    }

    /**
     * Where each archived item would go back to, and what blocks it. Nothing moves. {@link #restore} asks through
     * here too, so a preflight and the restore cannot disagree.
     *
     * @param entry the archive entry, in this operation's session
     * @param userSession the session of the requesting user, whose rights decide where the items may go
     * @return the conflicts, and the items that would move
     * @throws RepositoryException if the archive cannot be read
     */
    RestorePlan evaluateRestore(final Node entry, final Session userSession) throws RepositoryException
    {
        final List<RestoreConflict> conflicts = new ArrayList<>();
        final Map<String, Node> toRestore = new TreeMap<>();
        final NodeIterator wrappers = entry.getNodes();
        while (wrappers.hasNext()) {
            this.evaluateItem(wrappers.nextNode(), userSession, conflicts, toRestore);
        }
        return new RestorePlan(conflicts, toRestore);
    }

    /**
     * Move the contents of an archive entry back to their original paths, remove the entry, and commit. If any item
     * cannot go back, nothing changes.
     *
     * @param entry the entry node, in this operation's session
     * @param userSession the requesting user's session, whose rights decide where the items may go
     * @return the outcome
     * @throws RepositoryException if the archive cannot be read or the changes cannot be made
     */
    RestoreResult restore(final Node entry, final Session userSession) throws RepositoryException
    {
        final RestorePlan plan = this.evaluateRestore(entry, userSession);
        final List<RestoreConflict> conflicts = plan.conflicts();
        final Map<String, Node> toRestore = plan.toRestore();
        if (!conflicts.isEmpty()) {
            return new RestoreResult(RestoreResult.Status.CONFLICT, List.of(), conflicts);
        }
        for (final Map.Entry<String, Node> item : toRestore.entrySet()) {
            this.checkoutForAddition(this.session.getNode(parentPath(item.getKey())));
            this.session.move(item.getValue().getPath(), item.getKey());
        }
        entry.remove();
        this.saveAndCheckin();
        return new RestoreResult(RestoreResult.Status.RESTORED, List.copyOf(toRestore.keySet()), List.of());
    }

    /**
     * Remove an archive entry and everything in it for good, and commit. The guards are not asked here.
     *
     * @param entry the entry node, in this operation's session
     * @throws RepositoryException if the changes cannot be made
     */
    void purge(final Node entry) throws RepositoryException
    {
        entry.remove();
        this.saveAndCheckin();
    }

    private void evaluateItem(final Node wrapper, final Session userSession,
        final List<RestoreConflict> conflicts, final Map<String, Node> toRestore) throws RepositoryException
    {
        if (!wrapper.isNodeType(DeletionService.ITEM_NODETYPE)) {
            return;
        }
        final String originalPath = wrapper.getProperty(DeletionService.ORIGINAL_PATH_PROPERTY).getString();
        final NodeIterator content = wrapper.getNodes();
        if (!content.hasNext()) {
            LOGGER.warn("Archived item {} is empty, nothing to restore from it", wrapper.getPath());
            return;
        }
        if (!this.session.nodeExists(parentPath(originalPath))) {
            conflicts.add(new RestoreConflict(originalPath, RestoreConflict.Reason.PARENT_MISSING));
        } else if (this.session.nodeExists(originalPath)) {
            conflicts.add(new RestoreConflict(originalPath, RestoreConflict.Reason.OCCUPIED));
        } else if (!userSession.hasPermission(originalPath, Session.ACTION_ADD_NODE)) {
            conflicts.add(new RestoreConflict(originalPath, RestoreConflict.Reason.NO_RIGHTS));
        } else {
            toRestore.put(originalPath, content.nextNode());
        }
    }

    private void removeLinks(final DeletionPlan plan) throws RepositoryException
    {
        for (final String linkPath : plan.getLinksToRemove().keySet()) {
            final Node linkNode = this.session.getNode(linkPath);
            this.checkoutForRemoval(linkNode);
            final Resource linkResource = this.resolver.getResource(linkNode.getPath());
            final Link link = linkResource == null ? null : Link.toLink(linkResource);
            if (link == null) {
                // Not a link the links module knows, such as a plain node holding a reference: no backlink to remove
                linkNode.remove();
            } else {
                // Removes its backlink too
                link.remove(true);
            }
        }
    }

    /**
     * Check out the closest versionable ancestor of a node about to be removed or moved away, unless its definition
     * leaves it out of its parent's versions. The node is never the repository root.
     */
    private void checkoutForRemoval(final Node node) throws RepositoryException
    {
        if (node.getDefinition().getOnParentVersion() == OnParentVersionAction.IGNORE) {
            return;
        }
        this.checkoutForAddition(node.getParent());
    }

    /**
     * Check out the closest versionable ancestor-or-self of a node about to gain or lose a child, if it is
     * checked in.
     */
    private void checkoutForAddition(final Node parent) throws RepositoryException
    {
        final String versionable = VersioningUtils.checkOut(parent);
        if (versionable != null) {
            this.checkedOut.add(versionable);
        }
    }

    private void saveAndCheckin() throws RepositoryException
    {
        try {
            this.session.save();
        } catch (final ReferentialIntegrityException e) {
            // A hard reference the plan did not account for, such as one added since the plan was made
            throw new DeletionException("The deletion was blocked by references discovered at commit time", e);
        }
        for (final String path : this.checkedOut) {
            // The same operation may have removed an ancestor it checked out
            if (this.session.nodeExists(path)) {
                this.versionManager.checkin(path);
            }
        }
        this.checkedOut.clear();
    }

    private static String parentPath(final String path)
    {
        final int cut = path.lastIndexOf('/');
        return cut == 0 ? "/" : path.substring(0, cut);
    }

    /**
     * What restoring an archive entry would do: where each archived item would go, and what blocks it.
     *
     * @param conflicts everything that blocks the restore; empty if it would succeed
     * @param toRestore each original path, mapped to the archived node that would move back there
     * @version $Id$
     * @since 0.1.0
     */
    record RestorePlan(List<RestoreConflict> conflicts, Map<String, Node> toRestore)
    {
    }
}

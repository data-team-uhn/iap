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

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.deletion.api.DeletionException;
import io.uhndata.iap.deletion.api.DeletionService;
import io.uhndata.iap.deletion.api.RestoreConflict;
import io.uhndata.iap.deletion.api.RestoreResult;
import io.uhndata.iap.links.models.Link;

/**
 * The execution phase of deletions: performs the actual moves and removals through the privileged service session,
 * after a {@link CascadeResolver resolved} {@link DeletionPlan} established that they are allowed. Also owns the
 * reverse operations, restoring and purging archive entries.
 *
 * <p>
 * Every {@code iap:Entity} is versionable, and JCR refuses to change the children of a checked-in versionable
 * node, so before every move or removal the closest versionable ancestor is checked out; the ancestors <em>this
 * operation</em> checked out, and only those, are checked back in after saving. Items whose own definition sets
 * the {@code IGNORE} on-parent-version action, like the links container and its links, need no checkout at all.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ArchiveOperations
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ArchiveOperations.class);

    private static final String MIX_VERSIONABLE = "mix:versionable";

    private final ResourceResolver serviceResolver;

    private final Session serviceSession;

    private final VersionManager versionManager;

    /** The versionable ancestors checked out by this operation, to be checked back in after saving. */
    private final Set<String> checkedOut = new LinkedHashSet<>();

    private ArchiveOperations(final ResourceResolver serviceResolver, final Session serviceSession,
        final VersionManager versionManager)
    {
        this.serviceResolver = serviceResolver;
        this.serviceSession = serviceSession;
        this.versionManager = versionManager;
    }

    /**
     * Prepare to execute over a service resolver.
     *
     * @param serviceResolver the privileged resolver performing the changes
     * @return an executor bound to the resolver's session
     * @throws RepositoryException if the session's version manager is unavailable
     */
    static ArchiveOperations forResolver(final ResourceResolver serviceResolver)
        throws RepositoryException
    {
        final Session session = serviceResolver.adaptTo(Session.class);
        if (session == null) {
            throw new DeletionException("The service resolver is not backed by a repository session", null);
        }
        return new ArchiveOperations(serviceResolver, session, session.getWorkspace().getVersionManager());
    }

    /**
     * Move every subtree marked in the plan into a new archive entry, and remove the marked links.
     *
     * @param plan a resolved plan whose execution was authorized
     * @param userId the user requesting the deletion, recorded on the entry
     * @return the path of the created archive entry
     * @throws RepositoryException if the changes cannot be applied
     */
    String archive(final DeletionPlan plan, final String userId) throws RepositoryException
    {
        final Node archiveRoot = this.serviceSession.getNode(DeletionService.ARCHIVE_PATH);
        final Node entry = archiveRoot.addNode(UUID.randomUUID().toString(), DeletionService.ENTRY_NODETYPE);
        entry.setProperty(DeletionService.DELETED_BY_PROPERTY, userId);
        entry.setProperty(DeletionService.REQUESTED_PATH_PROPERTY, plan.getRequestedPath());
        // Links go first: removing a completed backlink pair navigates by path, so it must happen while
        // everything is still in place
        this.removeLinks(plan);
        int index = 0;
        for (final Map.Entry<String, Node> root : plan.getRoots().entrySet()) {
            this.checkoutForRemoval(root.getValue());
            final Node wrapper = entry.addNode(String.valueOf(index), DeletionService.ITEM_NODETYPE);
            wrapper.setProperty(DeletionService.ORIGINAL_PATH_PROPERTY, root.getKey());
            this.serviceSession.move(root.getKey(), wrapper.getPath() + "/" + root.getValue().getName());
            index++;
        }
        this.saveAndCheckin();
        return entry.getPath();
    }

    /**
     * Permanently remove every subtree marked in the plan, along with the marked links.
     *
     * @param plan a resolved plan whose execution was authorized
     * @throws RepositoryException if the changes cannot be applied
     */
    void deletePermanently(final DeletionPlan plan) throws RepositoryException
    {
        this.removeLinks(plan);
        for (final Node root : plan.getRoots().values()) {
            this.checkoutForRemoval(root);
            root.remove();
        }
        this.saveAndCheckin();
    }

    /**
     * Move the contents of an archive entry back to their recorded original locations, all or nothing.
     *
     * @param entry the entry node, in the service session
     * @param userSession the requesting user's session, deciding whether they may recreate the resources
     * @return the outcome
     * @throws RepositoryException if the conflicts cannot be evaluated or the changes cannot be applied
     */
    RestoreResult restore(final Node entry, final Session userSession) throws RepositoryException
    {
        final List<RestoreConflict> conflicts = new ArrayList<>();
        final Map<String, Node> toRestore = new TreeMap<>();
        final NodeIterator wrappers = entry.getNodes();
        while (wrappers.hasNext()) {
            this.evaluateItem(wrappers.nextNode(), userSession, conflicts, toRestore);
        }
        if (!conflicts.isEmpty()) {
            return new RestoreResult(RestoreResult.Status.CONFLICT, List.of(), conflicts);
        }
        for (final Map.Entry<String, Node> item : toRestore.entrySet()) {
            this.checkoutForAddition(this.serviceSession.getNode(parentPath(item.getKey())));
            this.serviceSession.move(item.getValue().getPath(), item.getKey());
        }
        entry.remove();
        this.saveAndCheckin();
        return new RestoreResult(RestoreResult.Status.RESTORED, List.copyOf(toRestore.keySet()), List.of());
    }

    /**
     * Permanently remove an archive entry and everything in it. The caller is responsible for consulting the
     * guards first.
     *
     * @param entry the entry node, in the service session
     * @throws RepositoryException if the changes cannot be applied
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
        if (!this.serviceSession.nodeExists(parentPath(originalPath))) {
            conflicts.add(new RestoreConflict(originalPath, RestoreConflict.Reason.PARENT_MISSING));
        } else if (this.serviceSession.nodeExists(originalPath)) {
            conflicts.add(new RestoreConflict(originalPath, RestoreConflict.Reason.OCCUPIED));
        } else if (!userSession.hasPermission(originalPath, Session.ACTION_ADD_NODE)) {
            conflicts.add(new RestoreConflict(originalPath, RestoreConflict.Reason.NO_RIGHTS));
        } else {
            toRestore.put(originalPath, content.nextNode());
        }
    }

    private void removeLinks(final DeletionPlan plan) throws RepositoryException
    {
        for (final Node linkNode : plan.getLinksToRemove().values()) {
            this.checkoutForRemoval(linkNode);
            final Resource linkResource = this.serviceResolver.getResource(linkNode.getPath());
            final Link link = linkResource == null ? null : Link.toLink(linkResource);
            if (link == null) {
                // Not recognized by the links machinery, e.g. an ad-hoc reference property holder; take it out
                // directly, there is no backlink bookkeeping to honor
                linkNode.remove();
            } else {
                // Removal is model behavior, honoring the backlink bookkeeping
                link.remove(true);
            }
        }
    }

    /**
     * Check out the versionable ancestor whose history a removal or move-away of this node would touch, if any.
     * Items ignored by versioning, like links, can be removed without touching anybody's version state. The node
     * is never the repository root; the analysis phase refuses that outright.
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
        Node ancestor = parent;
        while (ancestor.getDepth() > 0 && !ancestor.isNodeType(MIX_VERSIONABLE)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor.isNodeType(MIX_VERSIONABLE) && !ancestor.isCheckedOut()) {
            this.versionManager.checkout(ancestor.getPath());
            this.checkedOut.add(ancestor.getPath());
        }
    }

    private void saveAndCheckin() throws RepositoryException
    {
        try {
            this.serviceSession.save();
        } catch (final ReferentialIntegrityException e) {
            // The analysis phase removes or blocks on every incoming hard reference it can find, so this should
            // be unreachable; if the repository still objects, report it as a failure instead of a crash
            throw new DeletionException("The deletion was blocked by references discovered at commit time", e);
        }
        for (final String path : this.checkedOut) {
            // An ancestor checked out earlier may itself have been deleted by the same operation
            if (this.serviceSession.nodeExists(path)) {
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
}

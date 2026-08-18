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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.deletion.api.DeletionService;
import io.uhndata.iap.deletion.api.Veto;
import io.uhndata.iap.deletion.spi.DeletionMode;
import io.uhndata.iap.deletion.spi.DeletionVeto;
import io.uhndata.iap.links.models.Link;
import io.uhndata.iap.links.models.LinkDefinition;

/**
 * The analysis phase of a deletion: starting from the requested resource, resolve the complete set of impacted
 * subtrees by following containment, incoming references, and link deletion policies, while collecting vetoes and
 * permission failures along the way. Nothing is modified; all findings land in the {@link DeletionPlan}.
 *
 * <p>
 * The traversal is cycle-safe: subtrees are scanned at most once, tracked by node identifier, and resources
 * already marked for deletion are never reprocessed, so even mutually referencing resources — e.g. a completed
 * backlink pair whose definitions both ask for a recursive deletion — resolve in one bounded pass.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
class CascadeResolver
{
    private static final Logger LOGGER = LoggerFactory.getLogger(CascadeResolver.class);

    /** The node type shared by resource links, weak or hard. */
    private static final String LINK_NODETYPE = "iap:Link";

    /** The node type of weak resource links. */
    private static final String WEAK_LINK_NODETYPE = "iap:WeakLink";

    /** The node type of external links, which reference their definition just like resource links do. */
    private static final String EXTERNAL_LINK_NODETYPE = "iap:ExternalLink";

    private final DeletionPlan plan;

    private final List<DeletionVeto> resourceVetoes;

    private final List<DeletionVeto> operationVetoes;

    CascadeResolver(final DeletionPlan plan, final List<DeletionVeto> vetoes)
    {
        this.plan = plan;
        this.resourceVetoes = perResource(vetoes);
        this.operationVetoes = perOperation(vetoes);
    }

    /**
     * Resolve the complete impact of deleting a subtree.
     *
     * @param start the node whose deletion was requested, in the service session
     * @throws RepositoryException if the repository cannot be read
     */
    void resolve(final Node start) throws RepositoryException
    {
        // A guard that judges the operation rather than the resource is asked once, about what was actually
        // requested: its answer does not vary by node, so asking per node would repeat it for every node of every
        // impacted subtree.
        checkVetoes(start, this.operationVetoes, this.plan.getMode(), this.plan.getUserSession(),
            this.plan.getVetoes());
        final Deque<Node> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            final Node candidate = queue.poll();
            final String path = candidate.getPath();
            if (this.plan.isCovered(path)) {
                continue;
            }
            this.plan.markRoot(path, candidate);
            this.scan(candidate, queue);
        }
        this.plan.normalize();
    }

    /**
     * Scan one node and its descendants: ask the guards, check the requesting user's permission, and chase
     * incoming references. Nodes already scanned, e.g. when a previously processed subtree gets swallowed by a
     * larger one, are skipped wholesale.
     */
    private void scan(final Node node, final Deque<Node> queue) throws RepositoryException
    {
        if (!this.plan.visit(node.getIdentifier())) {
            return;
        }
        this.checkVetoes(node);
        this.checkPermission(node);
        this.findReferrers(node.getReferences(), queue);
        this.findReferrers(node.getWeakReferences(), queue);
        final NodeIterator children = node.getNodes();
        while (children.hasNext()) {
            this.scan(children.nextNode(), queue);
        }
    }

    private void checkVetoes(final Node node) throws RepositoryException
    {
        checkVetoes(node, this.resourceVetoes, this.plan.getMode(), this.plan.getUserSession(),
            this.plan.getVetoes());
    }

    /**
     * Ask every guard about one node.
     *
     * @param node the node a deletion would remove
     * @param vetoes the registered guards
     * @param mode the kind of deletion examined
     * @param requester the session of the user who asked for the deletion
     * @param results where objections are collected
     * @throws RepositoryException if the node cannot be read
     */
    static void checkVetoes(final Node node, final List<DeletionVeto> vetoes, final DeletionMode mode,
        final Session requester, final List<Veto> results) throws RepositoryException
    {
        final String path = node.getPath();
        vetoes.stream()
            .map(veto -> applyVeto(veto, node, path, mode, requester))
            .filter(Objects::nonNull)
            .forEach(results::add);
    }

    /**
     * Ask every guard about a subtree, e.g. the contents of an archive entry about to be purged: the root is shown to
     * all of them, its descendants only to those judging each resource separately.
     *
     * @param root the top of the subtree
     * @param vetoes the registered guards
     * @param mode the kind of deletion examined
     * @param requester the session of the user who asked for the deletion
     * @param results where objections are collected
     * @throws RepositoryException if the subtree cannot be read
     */
    static void sweepVetoes(final Node root, final List<DeletionVeto> vetoes, final DeletionMode mode,
        final Session requester, final List<Veto> results) throws RepositoryException
    {
        checkVetoes(root, vetoes, mode, requester, results);
        sweepDescendants(root, perResource(vetoes), mode, requester, results);
    }

    private static void sweepDescendants(final Node node, final List<DeletionVeto> vetoes, final DeletionMode mode,
        final Session requester, final List<Veto> results) throws RepositoryException
    {
        final NodeIterator children = node.getNodes();
        while (children.hasNext()) {
            final Node child = children.nextNode();
            checkVetoes(child, vetoes, mode, requester, results);
            sweepDescendants(child, vetoes, mode, requester, results);
        }
    }

    private static List<DeletionVeto> perResource(final List<DeletionVeto> vetoes)
    {
        return vetoes.stream()
            .filter(veto -> !veto.judgesWholeOperation())
            .collect(Collectors.toList());
    }

    private static List<DeletionVeto> perOperation(final List<DeletionVeto> vetoes)
    {
        return vetoes.stream()
            .filter(DeletionVeto::judgesWholeOperation)
            .collect(Collectors.toList());
    }

    private static Veto applyVeto(final DeletionVeto veto, final Node node, final String path,
        final DeletionMode mode, final Session requester)
    {
        try {
            final String reason = veto.veto(node, mode, requester);
            return reason == null ? null : new Veto(veto.getName(), path, reason);
        } catch (final RepositoryException | RuntimeException e) {
            // Fail closed: a guard that cannot decide keeps the data in place
            LOGGER.warn("Deletion guard {} failed on {}: {}", veto.getName(), path, e.getMessage(), e);
            return new Veto(veto.getName(), path, "Could not verify that deleting this resource is allowed");
        }
    }

    private void checkPermission(final Node node) throws RepositoryException
    {
        if (!this.plan.getUserSession().hasPermission(node.getPath(), Session.ACTION_REMOVE)) {
            this.plan.deny();
        }
    }

    private void findReferrers(final PropertyIterator references, final Deque<Node> queue)
        throws RepositoryException
    {
        while (references.hasNext()) {
            this.handleReferrer(references.nextProperty(), queue);
        }
    }

    private void handleReferrer(final Property property, final Deque<Node> queue) throws RepositoryException
    {
        final Node referrer = property.getParent();
        final String path = referrer.getPath();
        if (this.plan.isCovered(path)) {
            // A reference between two deleted subtrees needs no handling: on an archival the identifiers, and
            // with them the references, survive the move, and on a permanent deletion both ends disappear
            return;
        }
        if (referrer.isNodeType(LINK_NODETYPE) || referrer.isNodeType(EXTERNAL_LINK_NODETYPE)) {
            this.handleLink(property, referrer, path, queue);
        } else {
            this.handlePlainReferrer(referrer, path, queue);
        }
    }

    private void handleLink(final Property property, final Node linkNode, final String path,
        final Deque<Node> queue) throws RepositoryException
    {
        if (isArchived(path)) {
            // Links already sitting in the archive are left alone when archiving — the moved resources keep their
            // identifiers, so those links stay intact — but a permanent deletion must remove the ones holding a
            // hard reference, which would otherwise block the removal at commit time
            if (this.plan.getOptions().isPermanent() && property.getType() == PropertyType.REFERENCE) {
                this.plan.getLinksToRemove().put(path, linkNode);
            }
            return;
        }
        if (Link.TYPE_PROPERTY.equals(property.getName())) {
            // The deleted resource is the link's own definition, and a link never outlives its definition
            this.plan.getLinksToRemove().put(path, linkNode);
            return;
        }
        switch (this.resolvePolicy(path)) {
            case RECURSIVE_DELETE -> this.handleRecursiveLink(linkNode, path, queue);
            case IGNORE -> this.handleIgnoredLink(linkNode, path);
            // REMOVE_LINK, the default policy
            default -> this.plan.getLinksToRemove().put(path, linkNode);
        }
    }

    /**
     * The {@code onDelete} policy of a link, resolved through the service resolver rather than from the link node
     * itself: the policy lives on the link's definition, which only the {@link Link} model can follow.
     */
    private LinkDefinition.OnDelete resolvePolicy(final String path)
    {
        final Resource linkResource = this.plan.getServiceResolver().getResource(path);
        final Link link = linkResource == null ? null : Link.toLink(linkResource);
        final LinkDefinition definition = link == null ? null : link.getDefinition();
        if (definition == null) {
            LOGGER.warn("Cannot resolve the definition of link {}, defaulting to only removing the link", path);
            return LinkDefinition.OnDelete.REMOVE_LINK;
        }
        return definition.getOnDeletePolicy();
    }

    private void handleRecursiveLink(final Node linkNode, final String path, final Deque<Node> queue)
        throws RepositoryException
    {
        // Links are stored under <owner>/iap:links/<link>, so the resource sharing the link's fate sits two
        // levels up; a link node closer to the root than that is malformed
        if (linkNode.getDepth() < 2) {
            LOGGER.warn("Link {} has no owning resource, only removing the link", path);
            this.plan.getLinksToRemove().put(path, linkNode);
            return;
        }
        final Node owner = linkNode.getParent().getParent();
        final String ownerPath = owner.getPath();
        if (this.plan.getOptions().isRecursive()) {
            queue.add(owner);
        } else {
            this.plan.getBlockingReferrers().putIfAbsent(ownerPath, owner);
        }
    }

    private void handleIgnoredLink(final Node linkNode, final String path) throws RepositoryException
    {
        if (!linkNode.isNodeType(WEAK_LINK_NODETYPE)) {
            // Only a weak link may outlive its target as a broken reference; a hard one would block the deletion
            // at commit time, so the illegal policy is downgraded to removing the link
            LOGGER.warn("Hard link {} cannot ignore the deletion of its target, removing the link instead", path);
            this.plan.getLinksToRemove().put(path, linkNode);
        }
    }

    private void handlePlainReferrer(final Node referrer, final String path, final Deque<Node> queue)
    {
        if (isArchived(path)) {
            // An archival preserves identifiers, so references held by archived resources stay intact and nothing
            // needs to happen to them; a permanent deletion, though, would either break them or, when they are
            // hard, be blocked by them at commit time. Deleting archived content as a side effect would quietly
            // mutilate somebody's archive entry, so instead the archived referrer blocks the permanent deletion;
            // it is counted, but never named, since regular users cannot see the archive
            if (this.plan.getOptions().isPermanent()) {
                this.plan.getArchivedReferrers().add(path);
            }
        } else if (this.plan.getOptions().isRecursive()) {
            queue.add(referrer);
        } else {
            this.plan.getBlockingReferrers().putIfAbsent(path, referrer);
        }
    }

    private static boolean isArchived(final String path)
    {
        return path.startsWith(DeletionService.ARCHIVE_PATH + "/");
    }
}

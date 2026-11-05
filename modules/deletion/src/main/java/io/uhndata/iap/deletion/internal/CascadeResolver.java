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
import io.uhndata.iap.links.models.InternalLinkDefinition;
import io.uhndata.iap.links.models.Link;
import io.uhndata.iap.links.models.LinkDefinition;

/**
 * Works out everything a deletion affects, starting from the requested resource. It follows children, incoming
 * references, and the {@code onDelete} policy of incoming links. It asks the guards and checks the requesting user's
 * permissions on the way. Nothing is changed: what it finds goes into the {@link DeletionPlan}.
 *
 * <p>
 * Each node is scanned at most once, by identifier, and a resource already marked for deletion is skipped. The walk
 * ends even when resources reference each other, such as a link and its backlink whose definitions both say
 * {@code RECURSIVE_DELETE}.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
class CascadeResolver
{
    private static final Logger LOGGER = LoggerFactory.getLogger(CascadeResolver.class);

    /** The node type shared by resource links, weak or hard. */
    private static final String LINK_NODETYPE = "link:Link";

    /** The node type of weak resource links. */
    private static final String WEAK_LINK_NODETYPE = "link:WeakLink";

    /** The node type of external links, which reference their definition as resource links do. */
    private static final String EXTERNAL_LINK_NODETYPE = "link:ExternalLink";

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
     * Work out everything deleting a node affects, and record it in the plan.
     *
     * @param start the node whose deletion was requested, in the service session
     * @throws RepositoryException if the repository cannot be read
     */
    void resolve(final Node start) throws RepositoryException
    {
        // A guard that judges the whole deletion is asked once, about the requested resource
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
     * Scan one node and its descendants: ask the guards, check the requesting user's permission, and follow incoming
     * references. A node scanned already is skipped with its descendants, such as one inside a subtree that a larger
     * one has since swallowed.
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
     * Ask each guard about one node.
     *
     * @param node the node a deletion would remove
     * @param vetoes the guards to ask
     * @param mode the kind of deletion asked about
     * @param requester the session of the user who asked for the deletion
     * @param results the list to add each objection to
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
     * Ask the guards about a subtree, such as an archive entry to purge. Every guard is asked about the root. Only
     * the guards that answer about each node are asked about its descendants.
     *
     * @param root the top of the subtree
     * @param vetoes the registered guards
     * @param mode the kind of deletion asked about
     * @param requester the session of the user who asked for the deletion
     * @param results the list to add each objection to
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
            // A guard that cannot decide counts as a veto
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
            // Both ends are deleted. A move keeps identifiers, so archiving keeps the reference intact.
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
            // Archiving leaves an archived link alone, since the moved resource keeps its identifier. A permanent
            // deletion removes an archived hard link, which Oak would otherwise refuse to commit.
            if (this.plan.getOptions().isPermanent() && property.getType() == PropertyType.REFERENCE) {
                this.plan.getLinksToRemove().put(path, linkNode);
            }
            return;
        }
        if (Link.TYPE_PROPERTY.equals(property.getName())) {
            // The deleted resource is the link's definition, and a link never outlives its definition
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
     * The {@code onDelete} policy of a link, read off its definition through the {@link Link} model.
     */
    private InternalLinkDefinition.OnDelete resolvePolicy(final String path)
    {
        final Resource linkResource = this.plan.getServiceResolver().getResource(path);
        final Link link = linkResource == null ? null : Link.toLink(linkResource);
        // Only an internal definition carries a policy. Anything else is a missing or broken definition.
        final LinkDefinition definition = link == null ? null : link.getDefinition();
        if (!(definition instanceof InternalLinkDefinition)) {
            LOGGER.warn("Cannot resolve the definition of link {}, defaulting to only removing the link", path);
            return InternalLinkDefinition.OnDelete.REMOVE_LINK;
        }
        return ((InternalLinkDefinition) definition).getOnDeletePolicy();
    }

    private void handleRecursiveLink(final Node linkNode, final String path, final Deque<Node> queue)
        throws RepositoryException
    {
        // A link is stored as <owner>/link:links/<link>, two levels below the resource that holds it
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
            // Only a weak link may outlive its target. Oak would refuse to commit the deletion under a hard one.
            LOGGER.warn("Hard link {} cannot ignore the deletion of its target, removing the link instead", path);
            this.plan.getLinksToRemove().put(path, linkNode);
        }
    }

    private void handlePlainReferrer(final Node referrer, final String path, final Deque<Node> queue)
    {
        if (isArchived(path)) {
            // Archiving keeps identifiers, so an archived resource's reference stays intact. A permanent deletion
            // would break it, and must not delete archived content along with it. The archived referrer blocks the
            // deletion instead, and is counted but never named.
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

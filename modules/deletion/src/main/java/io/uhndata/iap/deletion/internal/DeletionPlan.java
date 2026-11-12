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
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

import javax.jcr.Node;
import javax.jcr.Session;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;

import io.uhndata.iap.deletion.api.DeletionOptions;
import io.uhndata.iap.deletion.api.Veto;
import io.uhndata.iap.deletion.spi.DeletionMode;

/**
 * What one deletion affects, as {@link CascadeResolver} finds it: the subtrees to delete, the links to remove, and
 * what blocks the deletion. A new plan is made for each deletion.
 *
 * @version $Id$
 * @since 0.1.0
 */
class DeletionPlan
{
    /** How to delete. */
    private final DeletionOptions options;

    /** The path whose deletion was requested. */
    private final String requestedPath;

    /** The requesting user's own session, for permission checks and for the guards. Nothing writes through it. */
    private final Session userSession;

    /** The {@code iap-deletion} service user's resolver, which reads everything the deletion affects. */
    private final ResourceResolver serviceResolver;

    /**
     * The subtrees to delete, by path, none inside another. Sorted, so that a new root removes the roots inside it
     * as one range, and the same plan always runs in the same order.
     */
    private final NavigableMap<String, Node> roots = new TreeMap<>();

    /** The identifiers of the nodes scanned already, so that each node is scanned once. */
    private final Set<String> visitedIds = new HashSet<>();

    /** The link nodes to remove from resources that stay, by path. */
    private final NavigableMap<String, Node> linksToRemove = new TreeMap<>();

    /** The referencing resources that block a deletion that is not recursive, by path. */
    private final NavigableMap<String, Node> blockingReferrers = new TreeMap<>();

    /** The paths of the archived resources that reference a resource to delete for good. Counted, never named. */
    private final Set<String> archivedReferrers = new HashSet<>();

    /** The objections raised by the registered guards. */
    private final List<Veto> vetoes = new ArrayList<>();

    /** Whether the requesting user may not remove one of the nodes to delete. */
    private boolean denied;

    DeletionPlan(final DeletionOptions options, final String requestedPath, final Session userSession,
        final ResourceResolver serviceResolver)
    {
        this.options = options;
        this.requestedPath = requestedPath;
        this.userSession = userSession;
        this.serviceResolver = serviceResolver;
    }

    DeletionOptions getOptions()
    {
        return this.options;
    }

    /**
     * The kind of deletion to ask the guards about.
     *
     * @return {@link DeletionMode#PERMANENT} or {@link DeletionMode#ARCHIVE}, following the options
     */
    DeletionMode getMode()
    {
        return this.options.isPermanent() ? DeletionMode.PERMANENT : DeletionMode.ARCHIVE;
    }

    String getRequestedPath()
    {
        return this.requestedPath;
    }

    Session getUserSession()
    {
        return this.userSession;
    }

    ResourceResolver getServiceResolver()
    {
        return this.serviceResolver;
    }

    /**
     * Whether a path is inside a subtree marked for deletion.
     *
     * @param path an absolute path
     * @return {@code true} if a marked root equals the path or is one of its ancestors
     */
    boolean isCovered(final String path)
    {
        // The closest preceding root need not be an ancestor: "/content/form-2" sorts between "/content/form" and
        // "/content/form/child", since '-' sorts before '/'
        for (String candidate = path; candidate != null; candidate = ResourceUtil.getParent(candidate)) {
            if (this.roots.containsKey(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Mark a subtree for deletion, and drop the roots marked inside it.
     *
     * @param path the subtree's absolute path, which must not be {@link #isCovered covered} already
     * @param node the subtree's root node, in the service session
     */
    void markRoot(final String path, final Node node)
    {
        // The roots inside it sort from path + "/" up to path + "0", since '0' follows '/'
        this.roots.subMap(path + "/", path + "0").clear();
        this.roots.put(path, node);
    }

    /**
     * Remember that a node was scanned.
     *
     * @param identifier the node's JCR identifier
     * @return {@code true} if the node had not been scanned before
     */
    boolean visit(final String identifier)
    {
        return this.visitedIds.add(identifier);
    }

    NavigableMap<String, Node> getRoots()
    {
        return this.roots;
    }

    NavigableMap<String, Node> getLinksToRemove()
    {
        return this.linksToRemove;
    }

    NavigableMap<String, Node> getBlockingReferrers()
    {
        return this.blockingReferrers;
    }

    Set<String> getArchivedReferrers()
    {
        return this.archivedReferrers;
    }

    List<Veto> getVetoes()
    {
        return this.vetoes;
    }

    boolean isDenied()
    {
        return this.denied;
    }

    void deny()
    {
        this.denied = true;
    }

    /**
     * Drop the links and referrers that a subtree marked later contains, since they are deleted with it.
     */
    void normalize()
    {
        this.linksToRemove.keySet().removeIf(this::isCovered);
        this.blockingReferrers.keySet().removeIf(this::isCovered);
    }
}

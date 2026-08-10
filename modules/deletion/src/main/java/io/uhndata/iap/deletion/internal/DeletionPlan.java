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
 * The mutable state of one deletion, gathered by {@link CascadeResolver} and consumed when building the impact
 * report and executing the operation. One instance per operation, passed down the call chain, so the components
 * themselves stay stateless.
 *
 * @version $Id$
 * @since 0.1.0
 */
class DeletionPlan
{
    /** What was asked for. */
    private final DeletionOptions options;

    /** The path whose deletion was requested. */
    private final String requestedPath;

    /** The requesting user's own session, used for authorization checks only. */
    private final Session userSession;

    /** The privileged resolver performing the complete impact scan and all the changes. */
    private final ResourceResolver serviceResolver;

    /**
     * The distinct subtrees to delete, kept maximal: no key is a descendant of another. Keyed by path so that
     * coverage checks are cheap ancestor lookups; the sorted order lets a new root drop the ones it swallows in
     * a single range, and also makes reports and executions deterministic.
     */
    private final NavigableMap<String, Node> roots = new TreeMap<>();

    /** Identifiers of already scanned nodes, so shared referrers and reference cycles are processed only once. */
    private final Set<String> visitedIds = new HashSet<>();

    /** Link nodes to remove from resources which themselves remain in place, keyed by path. */
    private final NavigableMap<String, Node> linksToRemove = new TreeMap<>();

    /** For a non-recursive deletion, the referencing resources that block it, keyed by path. */
    private final NavigableMap<String, Node> blockingReferrers = new TreeMap<>();

    /** Referencing resources that already sit in the archive; they always block, and are never named. */
    private final Set<String> archivedReferrers = new HashSet<>();

    /** The objections raised by the registered guards. */
    private final List<Veto> vetoes = new ArrayList<>();

    /** Whether the requesting user lacks the permission to remove one of the impacted resources. */
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
     * The kind of deletion the guards must be asked about.
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
     * Whether a path is already part of a subtree marked for deletion.
     *
     * @param path an absolute path
     * @return {@code true} if a marked root equals the path or is one of its ancestors
     */
    boolean isCovered(final String path)
    {
        // Each ancestor is looked up in turn, rather than probing the closest preceding root: a sibling whose
        // name extends an ancestor's with a character below '/', as "/content/form-2" does for "/content/form",
        // sorts between that ancestor and the path, so the closest preceding root need not be an ancestor
        for (String candidate = path; candidate != null; candidate = ResourceUtil.getParent(candidate)) {
            if (this.roots.containsKey(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Mark a subtree for deletion, keeping the root set maximal.
     *
     * @param path the subtree's absolute path, must not be already {@link #isCovered covered}
     * @param node the subtree's root node, in the service session
     */
    void markRoot(final String path, final Node node)
    {
        // Roots swallowed by the new one sort right after path + "/", and '0' is the successor of '/'
        this.roots.subMap(path + "/", path + "0").clear();
        this.roots.put(path, node);
    }

    /**
     * Remember that a node was scanned.
     *
     * @param identifier the node's identifier
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
     * Drop bookkeeping made redundant by roots marked later: a link or referrer inside a subtree that ended up
     * marked for deletion needs no separate handling.
     */
    void normalize()
    {
        this.linksToRemove.keySet().removeIf(this::isCovered);
        this.blockingReferrers.keySet().removeIf(this::isCovered);
    }
}

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
package io.uhndata.iap.links.internal;

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.links.models.ExternalLink;
import io.uhndata.iap.links.models.InternalLink;
import io.uhndata.iap.links.models.Link;
import io.uhndata.iap.links.models.Linkable;

/**
 * The internal service backing the behavior offered by the links models — {@link Linkable} for the operations on
 * a piece of content, {@link Link#remove} and {@link InternalLink#addBacklink} for the operations on an individual
 * link. It works on the models' raw nodes, which the models pass in themselves — always their own wrapped
 * resource — so this interface must stay in this non-exported package: the models are the only public face of the
 * links, and the resources they wrap are not to be reachable through the public API.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface LinkOperations
{
    /**
     * Retrieve all the links held by a node, internal and external ones alike.
     *
     * @param source the node holding the links
     * @return a list of links, empty if the node has none
     */
    @NotNull
    List<Link> getLinks(@NotNull Resource source);

    /**
     * Retrieve the links held by a node that are of a desired type.
     *
     * @param source the node holding the links
     * @param type the name or path of the link definition to filter by
     * @return a list of links, empty if the node has none of this type
     */
    @NotNull
    List<Link> getLinks(@NotNull Resource source, @NotNull String type);

    /**
     * Retrieve all the links pointing at a node.
     *
     * @param target the linked node
     * @return a list of links held by other content, empty if none point here
     */
    @NotNull
    List<InternalLink> getBacklinks(@NotNull Resource target);

    /**
     * Create a new link from a node to another piece of content, in memory: committing it is the caller's
     * responsibility.
     *
     * @param source the node to put the link on
     * @param destination the content the link points to; must be referenceable
     * @param type the name or path of the link definition to use
     * @param label an optional extra label for the new link, may be {@code null}
     * @return the created (or existing identical) link
     * @throws IllegalArgumentException if the definition is unknown, external, or backlink-only, or if an end
     *             doesn't satisfy the definition's type requirements
     */
    @NotNull
    InternalLink addLink(@NotNull Resource source, @NotNull Content destination, @NotNull String type,
        @Nullable String label);

    /**
     * Record a new external link on a node, in memory: committing it is the caller's responsibility.
     *
     * @param source the node to put the link on
     * @param type the name or path of the link definition to use
     * @param value the recorded value identifying the external target
     * @param label an optional extra label for the new link, may be {@code null}
     * @return the created (or existing identical) link
     * @throws IllegalArgumentException if the definition is unknown or not external, if the source doesn't
     *             satisfy the definition's type requirements, or if the value doesn't match the definition's
     *             value pattern
     */
    @NotNull
    ExternalLink addExternalLink(@NotNull Resource source, @NotNull String type, @NotNull String value,
        @Nullable String label);

    /**
     * Delete all the links of a node matching the given criteria, in memory: committing the removals is the
     * caller's responsibility.
     *
     * @param source the node to delete links from
     * @param destination the linked content, or {@code null} to match links to any target, external links
     *            included
     * @param type the name or path of the link definition to match
     * @param label the label to match, {@code null} to match any label
     * @return the number of deleted links
     */
    int removeLinks(@NotNull Resource source, @Nullable Content destination, @NotNull String type,
        @Nullable String label);

    /**
     * Create the reverse of a link, if its definition declares a backlink and the reverse doesn't exist yet. The
     * reverse is created in memory through the link's own resolver, only when that session may write to the
     * linked content, and committing it stays the caller's responsibility.
     *
     * @param link an existing link node
     * @return {@code true} if the reverse link now exists in the link's session, {@code false} if the node is
     *         not an internal link, there is nothing to create, or the session may not create it
     */
    boolean addBacklink(@NotNull Resource link);

    /**
     * Delete a link, in memory: committing the removal is the caller's responsibility.
     *
     * @param link an existing link node
     * @param removeBacklink whether the reverse link, if any, should be deleted too; the reverse is only deleted
     *            when the link's own session may write to the linked content
     * @return {@code true} if the link was deleted, {@code false} if the node is not a link or the deletion
     *         failed
     */
    boolean remove(@NotNull Resource link, boolean removeBacklink);
}

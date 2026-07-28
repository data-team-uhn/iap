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
package io.uhndata.iap.links.api;

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.links.models.ExternalLink;
import io.uhndata.iap.links.models.Link;
import io.uhndata.iap.links.models.LinkDefinition;
import io.uhndata.iap.links.models.ResourceLink;

/**
 * Manages the ad-hoc typed links kept in resources' {@code iap:links} containers.
 *
 * <p>
 * Writes are made in memory through the caller's resource resolver and are the caller's to commit, with two
 * exceptions: creating a missing {@code iap:links} container is committed immediately through the links service
 * user (it may require checking out a versionable resource the caller cannot), and the automatic backlink
 * completion commits its own work. Every backlink declared by a link's definition is guaranteed to eventually
 * exist: it is created together with the link when the caller may write to the linked resource, and completed by
 * the links service user shortly after the link is committed otherwise.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface LinkManager
{
    /** The repository location where link definitions are stored. */
    String LINK_TYPES_PATH = "/LinkTypes";

    /** The child node name where the links of a resource are stored. */
    String CONTAINER_NAME = "iap:links";

    /**
     * Resolve a link definition.
     *
     * @param resolver the resolver to read with
     * @param type the name of a definition under {@value #LINK_TYPES_PATH}, or an absolute path to one
     * @return a link definition, or {@code null} if there is no such definition
     */
    @Nullable
    LinkDefinition getDefinition(@NotNull ResourceResolver resolver, @NotNull String type);

    /**
     * Retrieve all the links of a resource, resource and external ones alike.
     *
     * @param resource the resource to get links from
     * @return a list of links, empty if the resource has none
     */
    @NotNull
    List<Link> getLinks(@NotNull Resource resource);

    /**
     * Retrieve the links of a resource of a desired type.
     *
     * @param resource the resource to get links from
     * @param type the name or path of the link definition to filter by
     * @return a list of links, empty if the resource has none of this type
     */
    @NotNull
    List<Link> getLinks(@NotNull Resource resource, @NotNull String type);

    /**
     * Retrieve all the links pointing at a resource.
     *
     * @param resource the linked resource
     * @return a list of links held by other resources, empty if none point here
     */
    @NotNull
    List<ResourceLink> getBacklinks(@NotNull Resource resource);

    /**
     * Create a new link between two resources, in memory: committing it is the caller's responsibility. If an
     * identical link already exists, it is returned instead of a duplicate. If the link's definition declares a
     * backlink and the caller may write to the destination, the reverse link is created in the same session, so
     * that the pair lands in one commit; otherwise the reverse is completed automatically after the commit.
     *
     * @param source the resource to put the link on
     * @param destination the resource the link points to; must be referenceable
     * @param type the name or path of the link definition to use
     * @param label an optional extra label for the new link, may be {@code null}
     * @return the created (or existing identical) link
     * @throws IllegalArgumentException if the definition is unknown, external, or backlink-only, or if a resource
     *             doesn't satisfy the definition's type requirements
     */
    @NotNull
    ResourceLink addLink(@NotNull Resource source, @NotNull Resource destination, @NotNull String type,
        @Nullable String label);

    /**
     * Record a new external link on a resource, in memory: committing it is the caller's responsibility. If an
     * identical link already exists, it is returned instead of a duplicate.
     *
     * @param source the resource to put the link on
     * @param type the name or path of the link definition to use
     * @param value the recorded value identifying the external target
     * @param label an optional extra label for the new link, may be {@code null}
     * @return the created (or existing identical) link
     * @throws IllegalArgumentException if the definition is unknown or not external, if the source doesn't satisfy
     *             the definition's type requirements, or if the value doesn't match the definition's
     *             {@link LinkDefinition#getValuePattern() value pattern}
     */
    @NotNull
    ExternalLink addExternalLink(@NotNull Resource source, @NotNull String type, @NotNull String value,
        @Nullable String label);

    /**
     * Create the reverse of a link, if its definition declares a backlink and the reverse doesn't exist yet. The
     * reverse is created through the original link's own resolver, only when that session may write to the linked
     * resource; this is the one legitimate way {@link LinkDefinition#isBacklinkOnly() backlink-only} definitions
     * are instantiated.
     *
     * @param original an existing link
     * @return {@code true} if the reverse link now exists in the original's session, {@code false} if there is
     *         nothing to create or the session may not create it
     */
    boolean addBacklink(@NotNull ResourceLink original);

    /**
     * Delete a link, in memory: committing the removal is the caller's responsibility.
     *
     * @param link the link to delete
     * @param removeBacklink whether the reverse link, if any, should be deleted too; the reverse is only deleted
     *            when the caller may write to the linked resource
     * @return {@code true} if the link was deleted
     */
    boolean removeLink(@NotNull Link link, boolean removeBacklink);

    /**
     * Delete all the links matching the given criteria, in memory: committing the removals is the caller's
     * responsibility.
     *
     * @param source the resource to delete links from
     * @param destination the linked resource, or {@code null} to match links to any resource, including external
     *            links
     * @param type the name or path of the link definition to match
     * @param label the label to match, {@code null} to match any label
     * @return the number of deleted links
     */
    int removeLinks(@NotNull Resource source, @Nullable Resource destination, @NotNull String type,
        @Nullable String label);
}

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
package io.uhndata.iap.links.models;

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.OSGiService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.links.internal.LinkOperations;

/**
 * The links-aware view of a piece of content, and the entry point for working with its links: view any content
 * model as this one, {@code content.as(Linkable.class)}, then list, add, or remove links. Writes are made in
 * memory through the resolver this model was read with and are the caller's to commit, with two exceptions:
 * creating a missing {@code link:links} container is committed immediately through the links service user (it may
 * require checking out a versionable resource the caller cannot), and the automatic backlink completion commits
 * its own work.
 *
 * <p>
 * The view adapts any content; the {@code link:Linkable} mixin only exists so that node types (or individual
 * nodes) can declare the links container once instead of re-declaring the child node — content whose type allows
 * the container some other way holds links just as well.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Linkable extends Content
{
    // The internal service the behavior delegates to; the model only hands it its own wrapped resource, which
    // keeps the resource itself out of the public API
    @OSGiService
    private LinkOperations operations;

    /**
     * Retrieve all the links held by this content, internal and external ones alike.
     *
     * @return a list of links, empty if there are none
     */
    @NotNull
    public List<Link> getLinks()
    {
        return this.operations == null ? List.of() : this.operations.getLinks(this.resource);
    }

    /**
     * Retrieve the links held by this content that are of a desired type.
     *
     * @param type the name or path of the link definition to filter by
     * @return a list of links, empty if there are none of this type
     */
    @NotNull
    public List<Link> getLinks(@NotNull final String type)
    {
        return this.operations == null ? List.of() : this.operations.getLinks(this.resource, type);
    }

    /**
     * Retrieve all the links pointing at this content.
     *
     * @return a list of links held by other content, empty if none point here
     */
    @NotNull
    public List<InternalLink> getBacklinks()
    {
        return this.operations == null ? List.of() : this.operations.getBacklinks(this.resource);
    }

    /**
     * Create a new link from this content to another piece of content, in memory: committing it is the caller's
     * responsibility, through the resolver this model was read with. If an identical link already exists, it is
     * returned instead of a duplicate. If the link's definition declares a backlink and this session may write to
     * the destination, the reverse link is created in the same session, so that the pair lands in one commit;
     * otherwise the reverse is completed automatically after the commit.
     *
     * @param destination the content the link points to; must be referenceable
     * @param type the name or path of the link definition to use
     * @param label an optional extra label for the new link, may be {@code null}
     * @return the created (or existing identical) link
     * @throws IllegalArgumentException if the definition is unknown, external, or backlink-only, or if an end
     *             doesn't satisfy the definition's type requirements
     * @throws IllegalStateException if the links service is not available
     */
    @NotNull
    public InternalLink addLink(@NotNull final Content destination, @NotNull final String type,
        @Nullable final String label)
    {
        return requireOperations().addLink(this.resource, destination, type, label);
    }

    /**
     * Record a new external link on this content, in memory: committing it is the caller's responsibility,
     * through the resolver this model was read with. If an identical link already exists, it is returned instead
     * of a duplicate.
     *
     * @param type the name or path of the link definition to use
     * @param value the recorded value identifying the external target
     * @param label an optional extra label for the new link, may be {@code null}
     * @return the created (or existing identical) link
     * @throws IllegalArgumentException if the definition is unknown or not external, if this content doesn't
     *             satisfy the definition's type requirements, or if the value doesn't match the definition's
     *             {@link LinkDefinition#getValuePattern() value pattern}
     * @throws IllegalStateException if the links service is not available
     */
    @NotNull
    public ExternalLink addExternalLink(@NotNull final String type, @NotNull final String value,
        @Nullable final String label)
    {
        return requireOperations().addExternalLink(this.resource, type, value, label);
    }

    /**
     * Delete all the links of this content matching the given criteria, in memory: committing the removals is
     * the caller's responsibility, through the resolver this model was read with.
     *
     * @param destination the linked content, or {@code null} to match links to any target, external links
     *            included
     * @param type the name or path of the link definition to match
     * @param label the label to match, {@code null} to match any label
     * @return the number of deleted links
     */
    public int removeLinks(@Nullable final Content destination, @NotNull final String type,
        @Nullable final String label)
    {
        return this.operations == null ? 0 : this.operations.removeLinks(this.resource, destination, type, label);
    }

    private LinkOperations requireOperations()
    {
        if (this.operations == null) {
            throw new IllegalStateException("The links service is not available");
        }
        return this.operations;
    }
}

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
 * The links of a piece of content. Read any content model as this one, {@code content.as(Linkable.class)}, to list,
 * add or remove its links. Any content adapts to it, whether or not it carries the {@code link:Linkable} mixin.
 *
 * <p>
 * Writes go into the session this model was read with, and the caller commits them. Two writes commit on their own,
 * as the {@code iap-links} service user. A missing {@code link:links} container is created and committed at once,
 * since adding it may mean checking out a versionable resource the caller cannot. A reverse link the caller may not
 * add is added after the caller commits.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Linkable extends Content
{
    @OSGiService
    private LinkOperations operations;

    /**
     * All the links this content holds, internal and external.
     *
     * @return the links; empty if there are none, or the links service is not available
     */
    @NotNull
    public List<Link> getLinks()
    {
        return this.operations == null ? List.of() : this.operations.getLinks(this.resource);
    }

    /**
     * The links of one type that this content holds.
     *
     * @param type the name or path of the link definition, such as {@code copiedFrom}
     * @return the links; empty if there are none of this type, or the links service is not available
     */
    @NotNull
    public List<Link> getLinks(@NotNull final String type)
    {
        return this.operations == null ? List.of() : this.operations.getLinks(this.resource, type);
    }

    /**
     * The links that other content holds to this content.
     *
     * @return the links; empty if none point here, or the links service is not available
     */
    @NotNull
    public List<InternalLink> getBacklinks()
    {
        return this.operations == null ? List.of() : this.operations.getBacklinks(this.resource);
    }

    /**
     * Adds a link from this content to other content, in the session this model was read with. The caller commits
     * it. An existing link of the same type to the same content comes back instead of a duplicate. Its label must
     * match too, and a {@code null} or empty label matches only a link without one.
     *
     * <p>When the definition names a backlink and this session may add nodes under the destination, the reverse
     * goes into the same session. The pair then lands in one commit. Otherwise the reverse is added after the
     * commit.</p>
     *
     * @param destination the content to link to; it must be referenceable
     * @param type the name or path of the link definition, such as {@code copiedFrom}
     * @param label the link's own label; {@code null} or empty for none
     * @return the new link, or the existing one
     * @throws IllegalArgumentException if the definition is unknown, external or backlink-only, if either end is
     *             not of a required type, or if the destination is not referenceable
     * @throws IllegalStateException if the links service is not available
     */
    @NotNull
    public InternalLink addLink(@NotNull final Content destination, @NotNull final String type,
        @Nullable final String label)
    {
        return requireOperations().addLink(this.resource, destination, type, label);
    }

    /**
     * Adds an external link to this content, in the session this model was read with. The caller commits it. An
     * existing link of the same type with the same value comes back instead of a duplicate. Its label must
     * match too, and a {@code null} or empty label matches only a link without one.
     *
     * @param type the name or path of the link definition
     * @param value the value naming the target, such as an identifier in another system
     * @param label the link's own label; {@code null} or empty for none
     * @return the new link, or the existing one
     * @throws IllegalArgumentException if the definition is unknown or not external, if this content is not of a
     *             required type, or if the value is blank or does not match the definition's
     *             {@link ExternalLinkDefinition#getValuePattern() value pattern}
     * @throws IllegalStateException if the links service is not available
     */
    @NotNull
    public ExternalLink addExternalLink(@NotNull final String type, @NotNull final String value,
        @Nullable final String label)
    {
        return requireOperations().addExternalLink(this.resource, type, value, label);
    }

    /**
     * Deletes this content's links of one type, to the given content and with the given label, in the session
     * this model was read with. The caller commits the deletions.
     *
     * @param destination the linked content; {@code null} to match any target, external links
     *            included; content without a {@code jcr:uuid} matches no link
     * @param type the name or path of the link definition
     * @param label the label to match; {@code null} for any label, empty for links without one
     * @return the number of links deleted; 0 if the links service is not available
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

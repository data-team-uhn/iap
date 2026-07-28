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

import java.util.stream.StreamSupport;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.links.api.LinkManager;

/**
 * A Sling Model wrapping an {@code iap:Link} or {@code iap:WeakLink} node: a link referencing another resource in
 * the repository.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = Link.class, resourceType = ResourceLink.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class ResourceLink extends Link
{
    /** The {@code sling:resourceType} of an {@code iap:Link} node. */
    public static final String RESOURCE_TYPE = "iap/Link";

    /** The {@code sling:resourceType} of an {@code iap:WeakLink} node. */
    public static final String WEAK_RESOURCE_TYPE = "iap/WeakLink";

    /** The name of the property referencing the linked resource. */
    public static final String REFERENCE_PROPERTY = "reference";

    @ValueMapValue(name = REFERENCE_PROPERTY)
    private String reference;

    /**
     * The resource this link points to.
     *
     * @return a content model, or {@code null} if the reference is broken (a weak link whose target was deleted)
     *         or not visible to the current user
     */
    @Nullable
    public Content getDestination()
    {
        return this.getReference(this.reference, Content.class);
    }

    /**
     * Whether this is a weak link, which may break when the linked resource is deleted, instead of a hard one,
     * which prevents that deletion.
     *
     * @return {@code true} for an {@code iap:WeakLink} node
     */
    public boolean isWeak()
    {
        return this.resource.isResourceType(WEAK_RESOURCE_TYPE);
    }

    /**
     * The reverse of this link, held by the linked resource and pointing back at the linking resource, when the
     * definitions of the two links declare each other as {@link LinkDefinition#getBacklink() backlinks}.
     *
     * @return the reverse link, or {@code null} if there is none (yet)
     */
    @Nullable
    public ResourceLink getBacklink()
    {
        final Content destination = this.getDestination();
        if (destination == null) {
            return null;
        }
        final Resource container = this.resource.getResourceResolver()
            .getResource(destination.getPath() + "/" + LinkManager.CONTAINER_NAME);
        if (container == null) {
            return null;
        }
        return StreamSupport.stream(container.getChildren().spliterator(), false)
            .map(Link::toLink)
            .filter(ResourceLink.class::isInstance)
            .map(ResourceLink.class::cast)
            .filter(this::isReverseOf)
            .findFirst()
            .orElse(null);
    }

    /**
     * Whether this link and another one are the two halves of a backlink pair: their endpoints are swapped, and
     * one's definition declares the other's as its backlink. This is derivable purely from the stored data, so
     * automatic backlink creation can recognize completed pairs without any bookkeeping.
     *
     * @param other another link
     * @return {@code true} if the two links reverse each other
     */
    public boolean isReverseOf(@NotNull final ResourceLink other)
    {
        return this.hasSwappedEndpoints(other) && this.definitionsCrossMatch(other);
    }

    private boolean hasSwappedEndpoints(final ResourceLink other)
    {
        final Content thisDestination = this.getDestination();
        final Content thisSource = this.getLinkingResource();
        final Content otherDestination = other.getDestination();
        final Content otherSource = other.getLinkingResource();
        if (thisDestination == null || thisSource == null || otherDestination == null || otherSource == null) {
            return false;
        }
        return thisDestination.getPath().equals(otherSource.getPath())
            && thisSource.getPath().equals(otherDestination.getPath());
    }

    private boolean definitionsCrossMatch(final ResourceLink other)
    {
        final LinkDefinition thisType = this.getDefinition();
        final LinkDefinition otherType = other.getDefinition();
        if (thisType == null || otherType == null) {
            return false;
        }
        if (thisType.hasBacklink()) {
            final LinkDefinition backlink = thisType.getBacklink();
            return backlink != null && backlink.getPath().equals(otherType.getPath());
        }
        final LinkDefinition otherBacklink = otherType.hasBacklink() ? otherType.getBacklink() : null;
        return otherBacklink != null && otherBacklink.getPath().equals(thisType.getPath());
    }

    /**
     * Whether this link has a completed reverse pair.
     *
     * @return {@code true} if the definition declares a backlink and the reverse link exists
     */
    public boolean isSymmetric()
    {
        final LinkDefinition definition = this.getDefinition();
        return definition != null && definition.hasBacklink() && this.getBacklink() != null;
    }

    @Override
    protected String getDefaultTargetLabel()
    {
        final Content destination = this.getDestination();
        return destination == null ? "inaccessible resource" : destination.getName();
    }

    @Override
    protected String resolveTargetPlaceholder(final String name)
    {
        final Content destination = this.getDestination();
        if (destination == null) {
            return "";
        }
        if ("name".equals(name)) {
            return destination.getName();
        }
        if (name.startsWith("property:")) {
            final Object value = destination.get(name.substring("property:".length()));
            return value == null ? "" : String.valueOf(value);
        }
        return "";
    }
}

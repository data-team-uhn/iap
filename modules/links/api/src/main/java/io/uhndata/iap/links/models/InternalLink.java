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
 * A link to content in this repository: a {@code link:Link} or {@code link:WeakLink} node.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = Link.class, resourceType = InternalLink.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class InternalLink extends Link
{
    /** The resource type of {@code link:Link}. */
    public static final String RESOURCE_TYPE = "link/Link";

    /** The resource type of {@code link:WeakLink}. */
    public static final String WEAK_RESOURCE_TYPE = "link/WeakLink";

    /** The property that references the linked content. */
    public static final String REFERENCE_PROPERTY = "reference";

    @ValueMapValue(name = REFERENCE_PROPERTY)
    private String reference;

    /**
     * The content this link points to.
     *
     * @return the content; {@code null} if this session cannot read it, or if the target of a weak link was deleted
     */
    @Nullable
    public Content getDestination()
    {
        return this.getReference(this.reference, Content.class);
    }

    /**
     * The definition of this link's type, an internal one.
     *
     * @return the definition; {@code null} if this session cannot read it
     */
    @Override
    @Nullable
    public InternalLinkDefinition getDefinition()
    {
        return this.getDefinition(InternalLinkDefinition.class);
    }

    /**
     * Whether this link holds a weak reference.
     *
     * @return {@code true} for a {@code link:WeakLink} node
     */
    public boolean isWeak()
    {
        return this.resource.isResourceType(WEAK_RESOURCE_TYPE);
    }

    /**
     * The reverse of this link: the link the destination holds back to this link's source, as
     * {@link #isReverseOf} tells.
     *
     * @return the reverse link; {@code null} if there is none yet
     */
    @Nullable
    public InternalLink getBacklink()
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
            .filter(InternalLink.class::isInstance)
            .map(InternalLink.class::cast)
            .filter(this::isReverseOf)
            .findFirst()
            .orElse(null);
    }

    /**
     * Adds the reverse of this link, if its definition names a backlink and the reverse does not exist yet. The
     * reverse goes into this link's session, and only if that session may add nodes under the destination. The
     * caller commits it. A link of a {@link InternalLinkDefinition#isBacklinkOnly() backlink-only} type is only ever
     * created this way.
     *
     * @return {@code true} if the reverse now exists in this session; {@code false} if the definition names no
     *         backlink, the reverse cannot be added in this session, or the links service is not available
     */
    public boolean addBacklink()
    {
        return this.operations != null && this.operations.addBacklink(this.resource);
    }

    /**
     * Whether this link and another are the two halves of a backlink pair: their endpoints are swapped, and one's
     * definition names the other's as its backlink.
     *
     * @param other another internal link
     * @return {@code true} if the two links reverse each other
     */
    public boolean isReverseOf(@NotNull final InternalLink other)
    {
        return this.hasSwappedEndpoints(other) && this.definitionsCrossMatch(other);
    }

    private boolean hasSwappedEndpoints(final InternalLink other)
    {
        final Content thisDestination = this.getDestination();
        final Content thisSource = this.getSource();
        final Content otherDestination = other.getDestination();
        final Content otherSource = other.getSource();
        if (thisDestination == null || thisSource == null || otherDestination == null || otherSource == null) {
            return false;
        }
        return thisDestination.getPath().equals(otherSource.getPath())
            && thisSource.getPath().equals(otherDestination.getPath());
    }

    private boolean definitionsCrossMatch(final InternalLink other)
    {
        final InternalLinkDefinition thisType = this.getDefinition();
        final InternalLinkDefinition otherType = other.getDefinition();
        if (thisType == null || otherType == null) {
            return false;
        }
        if (thisType.hasBacklink()) {
            final InternalLinkDefinition backlink = thisType.getBacklink();
            return backlink != null && backlink.getPath().equals(otherType.getPath());
        }
        final InternalLinkDefinition otherBacklink = otherType.hasBacklink() ? otherType.getBacklink() : null;
        return otherBacklink != null && otherBacklink.getPath().equals(thisType.getPath());
    }

    /**
     * Whether the reverse of this link exists.
     *
     * @return {@code true} if the definition names a backlink and the reverse link exists
     */
    public boolean isSymmetric()
    {
        final InternalLinkDefinition definition = this.getDefinition();
        return definition != null && definition.hasBacklink() && this.getBacklink() != null;
    }

    @Override
    protected String getDefaultTargetLabel()
    {
        final Content destination = this.getDestination();
        return destination == null ? "inaccessible target" : destination.getName();
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

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

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.content.models.Content;

/**
 * The abstract base shared by all the links kept in a resource's {@code iap:links} container: a typed, optionally
 * labeled connection from the resource holding it to a target. The target is either another resource in the
 * repository ({@link ResourceLink}) or something outside it, recorded as a value ({@link ExternalLink}). Like the
 * other abstract bases in the data model, this class is deliberately not itself a registered Sling Model: each
 * subtype declares {@code adapters = Link.class} on its own {@code @Model}, so {@code resource.adaptTo(Link.class)}
 * dispatches to the actual concrete subtype.
 *
 * @version $Id$
 * @since 0.1.0
 */
public abstract class Link extends Content
{
    /** The name of the property referencing the {@code iap:LinkDefinition} describing a link. */
    public static final String TYPE_PROPERTY = "type";

    /** The name of the property holding a link's optional extra label. */
    public static final String LABEL_PROPERTY = "label";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)\\}");

    @ValueMapValue(name = TYPE_PROPERTY)
    private String type;

    @ValueMapValue(name = LABEL_PROPERTY)
    private String label;

    /**
     * Adapt a resource to the concrete link model matching its kind, or nothing if it isn't a link. This picks the
     * model deterministically from the node's own type, so it also works on freshly created, still uncommitted
     * nodes, where the autocreated {@code sling:resourceType} driving the generic {@code adaptTo(Link.class)}
     * dispatch is not materialized yet.
     *
     * @param resource a resource, may be {@code null}
     * @return the link, or {@code null} if the resource is not a link node
     */
    @Nullable
    public static Link toLink(@NotNull final Resource resource)
    {
        if (resource == null) {
            return null;
        }
        if (isLinkKind(resource, ResourceLink.RESOURCE_TYPE, "iap:Link")
            || isLinkKind(resource, ResourceLink.WEAK_RESOURCE_TYPE, "iap:WeakLink")) {
            return resource.adaptTo(ResourceLink.class);
        }
        if (isLinkKind(resource, ExternalLink.RESOURCE_TYPE, "iap:ExternalLink")) {
            return resource.adaptTo(ExternalLink.class);
        }
        return null;
    }

    private static boolean isLinkKind(final Resource resource, final String resourceType,
        final String primaryType)
    {
        return resource.isResourceType(resourceType)
            || primaryType.equals(resource.getValueMap().get("jcr:primaryType", String.class));
    }

    /**
     * The definition of this link's type.
     *
     * @return a link definition, or {@code null} if it cannot be resolved
     */
    @Nullable
    public LinkDefinition getDefinition()
    {
        return this.getReference(this.type, LinkDefinition.class);
    }

    /**
     * The optional extra label associated with this link.
     *
     * @return a label, or {@code null} if none is set
     */
    @Nullable
    public String getLabel()
    {
        return this.label;
    }

    /**
     * The resource that this link belongs to, i.e. the owner of the {@code iap:links} container holding it.
     *
     * @return a content model, or {@code null} if the structure is unexpected
     */
    @Nullable
    public Content getLinkingResource()
    {
        final Resource container = this.resource.getParent();
        final Resource owner = container == null ? null : container.getParent();
        return owner == null ? null : owner.adaptTo(Content.class);
    }

    /**
     * A display label for the link target, rendered through the definition's
     * {@link LinkDefinition#getResourceLabelTemplate() label template} when one is set, or the target's natural
     * label otherwise.
     *
     * @return a display label
     */
    @NotNull
    public String getTargetLabel()
    {
        final LinkDefinition definition = this.getDefinition();
        final String template = definition == null ? null : definition.getResourceLabelTemplate();
        if (template == null || template.isBlank()) {
            return this.getDefaultTargetLabel();
        }
        final Matcher matcher = PLACEHOLDER.matcher(template);
        final StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(this.resolvePlaceholder(matcher.group(1))));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String resolvePlaceholder(final String name)
    {
        if ("label".equals(name)) {
            final String ownLabel = this.getLabel();
            return ownLabel == null ? "" : ownLabel;
        }
        if ("typeLabel".equals(name)) {
            // A template only reaches this point when it was read from the definition, so the
            // definition is resolvable in this session
            return Objects.requireNonNull(this.getDefinition()).getLabel();
        }
        if ("sourceName".equals(name)) {
            final Content linkingResource = this.getLinkingResource();
            return linkingResource == null ? "" : linkingResource.getName();
        }
        return this.resolveTargetPlaceholder(name);
    }

    /**
     * The label used for the target when the definition sets no template.
     *
     * @return a display label
     */
    @NotNull
    protected abstract String getDefaultTargetLabel();

    /**
     * Resolve a target-specific template placeholder, e.g. {@code name} or {@code value}.
     *
     * @param name the placeholder name, without the surrounding braces
     * @return the replacement, an empty string for placeholders this kind of target cannot fill
     */
    @NotNull
    protected abstract String resolveTargetPlaceholder(@NotNull String name);
}

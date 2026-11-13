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
import org.apache.sling.models.annotations.injectorspecific.OSGiService;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.links.internal.LinkOperations;

/**
 * A link kept in a resource's {@code link:links} container: a typed connection, with an optional label, from the
 * resource holding it to a target. The target of an {@link InternalLink} is content in the repository. The target of
 * an {@link ExternalLink} is something outside it, named by a recorded value.
 *
 * <p>This abstract class is not a Sling Model itself. Each kind lists {@code Link} among the adapters of its own
 * {@code @Model}, so {@code resource.adaptTo(Link.class)} returns the model of the link's kind.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public abstract class Link extends Content
{
    /** The property that references the link's {@code link:Definition}. */
    public static final String TYPE_PROPERTY = "type";

    /** The property that holds the link's own label, which is optional. */
    public static final String LABEL_PROPERTY = "label";

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([^}]+)\\}");

    @OSGiService
    protected LinkOperations operations;

    @ValueMapValue(name = TYPE_PROPERTY)
    private String type;

    @ValueMapValue(name = LABEL_PROPERTY)
    private String label;

    /**
     * The model of a link node's kind: an {@link InternalLink} for a {@code link:Link} or {@code link:WeakLink}, and
     * an {@link ExternalLink} for a {@code link:ExternalLink}. It recognizes the kind by the resource type, or by the
     * primary type.
     *
     * @param resource any resource
     * @return the link; {@code null} if the resource is not a link node
     */
    @Nullable
    public static Link toLink(@NotNull final Resource resource)
    {
        if (isLinkKind(resource, InternalLink.RESOURCE_TYPE, "link:Link")
            || isLinkKind(resource, InternalLink.WEAK_RESOURCE_TYPE, "link:WeakLink")) {
            return resource.adaptTo(InternalLink.class);
        }
        if (isLinkKind(resource, ExternalLink.RESOURCE_TYPE, "link:ExternalLink")) {
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
     * The definition of this link's type. Each kind returns its own kind of definition.
     *
     * @return the definition; {@code null} if this session cannot read it
     */
    @Nullable
    public abstract LinkDefinition getDefinition();

    /**
     * The definition of this link's type, read as the given model. The links API creates an internal link only from
     * an internal definition, and an external link only from an external one.
     *
     * @param <T> the definition type
     * @param definitionType the model class to read the definition as
     * @return the definition; {@code null} if this session cannot read it
     */
    @Nullable
    protected <T extends LinkDefinition> T getDefinition(@NotNull final Class<T> definitionType)
    {
        return this.getReference(this.type, definitionType);
    }

    /**
     * The link's own label.
     *
     * @return the label; {@code null} if none is set
     */
    @Nullable
    public String getLabel()
    {
        return this.label;
    }

    /**
     * Deletes this link, in the session it was read with. The caller commits the deletion.
     *
     * @param removeBacklink whether to delete the reverse link too, if this session can read and remove it
     * @return {@code true} if the link was deleted; {@code false} if the deletion failed, or the links service is
     *         not available
     */
    public boolean remove(final boolean removeBacklink)
    {
        return this.operations != null && this.operations.remove(this.resource, removeBacklink);
    }

    /**
     * The content holding this link: the owner of its {@code link:links} container.
     *
     * @return the content; {@code null} for a link node too close to the root to have an owner
     */
    @Nullable
    public Content getSource()
    {
        final Resource container = this.resource.getParent();
        final Resource owner = container == null ? null : container.getParent();
        return owner == null ? null : owner.adaptTo(Content.class);
    }

    /**
     * A label for the target, from the definition's {@link LinkDefinition#getTargetLabelTemplate() target label
     * template}. Without a template, it is the target's node name, or an external link's value.
     *
     * @return the label
     */
    @NotNull
    public String getTargetLabel()
    {
        final LinkDefinition definition = this.getDefinition();
        final String template = definition == null ? null : definition.getTargetLabelTemplate();
        if (template == null || template.isBlank()) {
            return this.getDefaultTargetLabel();
        }
        final Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
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
            // The template came from the definition, so this session can read the definition
            return Objects.requireNonNull(this.getDefinition()).getLabel();
        }
        if ("sourceName".equals(name)) {
            final Content linkingResource = this.getSource();
            return linkingResource == null ? "" : linkingResource.getName();
        }
        return this.resolveTargetPlaceholder(name);
    }

    /**
     * The label of the target when the definition sets no template.
     *
     * @return the label
     */
    @NotNull
    protected abstract String getDefaultTargetLabel();

    /**
     * The value of a template placeholder about the target, such as {@code name} or {@code value}.
     *
     * @param name the placeholder, without its braces
     * @return the value; empty if this kind of link has no such value
     */
    @NotNull
    protected abstract String resolveTargetPlaceholder(@NotNull String name);
}

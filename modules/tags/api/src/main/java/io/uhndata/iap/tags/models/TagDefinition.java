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
package io.uhndata.iap.tags.models;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.autodoc.api.DocumentedItem;
import io.uhndata.iap.content.models.Content;

/**
 * The definition of one tag, a {@code tag:Definition} node: what the tag means, where it may be placed, and how it
 * behaves. Tagged content stores the tag as a plain string in its {@code tags} property, equal to the definition's
 * {@link #getName() name}. Each definition is an entry of the {@link TagsHomepage tag catalogue}, which lists its
 * behaviors and where it may be placed.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = TagDefinition.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class TagDefinition extends Content implements DocumentedItem
{
    /** The {@code sling:resourceType} of a {@code tag:Definition} node. */
    public static final String RESOURCE_TYPE = "tag/Definition";

    /**
     * Sorts tag definitions in display order: by their {@link #getOrder() order}, those without one last, and by name
     * when the order is the same.
     */
    public static final Comparator<TagDefinition> DISPLAY_ORDER =
        Comparator.comparing(TagDefinition::getOrder, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(TagDefinition::getName);

    @ValueMapValue
    private String name;

    @ValueMapValue
    private String label;

    @ValueMapValue
    private String description;

    @ValueMapValue(name = "category")
    private String[] categories;

    @ValueMapValue
    private boolean inheritable;

    @ValueMapValue
    private boolean aggregated;

    @ValueMapValue
    private String[] targetResourceTypes;

    @ValueMapValue
    private String color;

    @ValueMapValue
    private String variant;

    @ValueMapValue
    private String icon;

    @ValueMapValue
    private Long order;

    @ValueMapValue
    private boolean system;

    /**
     * The name of the tag, the exact string stored in the {@code tags} property of tagged content. It is the
     * definition node's name, unless the definition sets a {@code name} property for a string that would be awkward
     * as a node name.
     *
     * @return the tag name
     */
    @Override
    @NotNull
    public String getName()
    {
        return this.name == null || this.name.isEmpty() ? super.getName() : this.name;
    }

    /**
     * The tag's name as people read it.
     *
     * @return the {@code label} property, or the tag name if it is not set
     */
    @NotNull
    public String getLabel()
    {
        return this.label == null || this.label.isEmpty() ? getName() : this.label;
    }

    /**
     * What this tag means and when it applies.
     *
     * @return the description, or {@code null} if not set
     */
    @Override
    @Nullable
    public String getDescription()
    {
        return this.description;
    }

    /**
     * The categories this tag belongs to, such as {@code lifecycle} or {@code review}. Listings group and filter tags
     * by category. Placing this tag {@link Taggable#tagReplacing(String, boolean) in place of others} removes the
     * content's tags that share any of these categories.
     *
     * @return the categories, an empty list if none are set
     */
    @NotNull
    public List<String> getCategories()
    {
        return this.categories == null ? List.of() : List.of(this.categories);
    }

    /**
     * Whether the content under a tagged node carries this tag too. Every version of a {@code retired} schema is
     * retired.
     *
     * @return {@code true} if this tag is passed down to the descendants of a tagged node
     */
    public boolean isInheritable()
    {
        return this.inheritable;
    }

    /**
     * Whether a node carries this tag when content below it does, up to the nearest {@code tag:Boundary}. A
     * submission with an {@code incomplete} answer is incomplete.
     *
     * @return {@code true} if this tag is passed up from tagged nodes to their ancestors
     */
    public boolean isAggregated()
    {
        return this.aggregated;
    }

    /**
     * The resource types of the content this tag may be placed on, subtypes included, such as
     * {@code sub/Submission}. When empty, the tag may be placed on any content.
     *
     * @return the accepted resource types, an empty list if the tag is unrestricted
     */
    @NotNull
    public List<String> getTargetResourceTypes()
    {
        return this.targetResourceTypes == null ? List.of() : List.of(this.targetResourceTypes);
    }

    /**
     * The color the UI shows this tag in, as a CSS color. The chip's background, text and border all derive from it,
     * as the {@link #getVariant() variant} says.
     *
     * @return the color, or {@code null} if not set
     */
    @Nullable
    public String getColor()
    {
        return this.color;
    }

    /**
     * How the UI shows this tag. {@code soft} tints the background with the {@link #getColor() color} and makes the
     * text a readable shade of it. {@code outlined} shows that text and a matching border on no background.
     * {@code filled} fills the chip with the color, under black or white text.
     *
     * @return the variant, or {@code null} if not set, which shows the tag as {@code soft}
     */
    @Nullable
    public String getVariant()
    {
        return this.variant;
    }

    /**
     * The icon shown next to this tag's label, as a MUI icon name such as {@code EditOutlined}. A name the UI does
     * not list shows no icon.
     *
     * @return the icon name, or {@code null} if not set
     */
    @Nullable
    public String getIcon()
    {
        return this.icon;
    }

    /**
     * The position of this tag in listings, lowest first. Tags without one come last.
     *
     * @return the order, or {@code null} if not set
     */
    @Nullable
    public Long getOrder()
    {
        return this.order;
    }

    /**
     * Whether only the code managing this tag may add or remove it. The {@link Taggable} methods refuse it unless
     * their {@code allowSystem} is {@code true}.
     *
     * @return {@code true} if this is a system tag
     */
    public boolean isSystem()
    {
        return this.system;
    }

    /**
     * Checks whether this tag may be placed on the given content, by its
     * {@link #getTargetResourceTypes() target resource types}.
     *
     * @param target the content that would be tagged
     * @return {@code true} if the content is of one of the target resource types or a subtype, or if this definition
     *         has none
     */
    public boolean appliesTo(@NotNull final Content target)
    {
        return this.targetResourceTypes == null || this.targetResourceTypes.length == 0
            || getTargetResourceTypes().stream().anyMatch(target::isOfType);
    }

    @Override
    @NotNull
    public String getDocumentationLabel()
    {
        return getLabel();
    }

    @Override
    @NotNull
    public List<String> getDocumentationCategories()
    {
        return getCategories();
    }

    @Override
    @NotNull
    public List<String> getDocumentationDetails()
    {
        final List<String> details = new ArrayList<>();
        if (isInheritable()) {
            details.add("**Inheritable**: implicitly carried by everything inside tagged content");
        }
        if (isAggregated()) {
            details.add("**Aggregated**: implicitly carried by content when anything inside it is tagged");
        }
        if (isSystem()) {
            details.add("**System**: managed by the platform, cannot be manually added or removed");
        }
        if (!getTargetResourceTypes().isEmpty()) {
            details.add("**May only be placed on**: `" + String.join("`, `", getTargetResourceTypes()) + "`");
        }
        return details;
    }

    @Override
    @NotNull
    public JsonObjectBuilder documentationJsonBuilder()
    {
        final JsonObjectBuilder json = DocumentedItem.super.documentationJsonBuilder()
            .add("inheritable", isInheritable())
            .add("aggregated", isAggregated())
            .add("system", isSystem());
        if (!getTargetResourceTypes().isEmpty()) {
            final JsonArrayBuilder targets = Json.createArrayBuilder();
            getTargetResourceTypes().forEach(targets::add);
            json.add("targetResourceTypes", targets);
        }
        // An unset field is left out, not written as null
        Optional.ofNullable(getColor()).ifPresent(value -> json.add("color", value));
        Optional.ofNullable(getVariant()).ifPresent(value -> json.add("variant", value));
        Optional.ofNullable(getIcon()).ifPresent(value -> json.add("icon", value));
        Optional.ofNullable(getOrder()).ifPresent(value -> json.add("order", value));
        return json.add("path", getPath());
    }
}

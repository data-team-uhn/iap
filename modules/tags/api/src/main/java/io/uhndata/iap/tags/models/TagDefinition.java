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

import java.util.Comparator;
import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;

import io.uhndata.iap.content.models.Content;

/**
 * A Sling Model wrapping an {@code iap:TagDefinition} node, the definition of one tag: what it means, where it may be
 * placed, and how it behaves. The tag itself is stored on tagged nodes as a plain string in their {@code tags}
 * property, matching this definition's {@link #getName() name}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = TagDefinition.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class TagDefinition extends Content
{
    /** The {@code sling:resourceType} of an {@code iap:TagDefinition} node. */
    public static final String RESOURCE_TYPE = "iap/TagDefinition";

    /**
     * Sorts tag definitions in their intended display sequence: by their explicit {@link #getOrder() order} first,
     * definitions without an order last, ties broken by comparing tag names.
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
    private String[] targetResources;

    @ValueMapValue
    private String color;

    @ValueMapValue
    private Long order;

    @ValueMapValue
    private boolean system;

    /**
     * The name of the tag, the exact string stored in the {@code tags} property of tagged nodes. This is the
     * definition node's own name, unless overridden by an explicit {@code name} property, which allows tag strings
     * that would be awkward as node names.
     *
     * @return the tag name
     */
    @Override
    public String getName()
    {
        return this.name == null || this.name.isEmpty() ? super.getName() : this.name;
    }

    /**
     * The human-readable name displayed in the UI, falling back to the tag name when no explicit label is set.
     *
     * @return the display label
     */
    public String getLabel()
    {
        return this.label == null || this.label.isEmpty() ? getName() : this.label;
    }

    /**
     * A longer explanation of what this tag means and when it applies.
     *
     * @return the description, or {@code null} if not set
     */
    public String getDescription()
    {
        return this.description;
    }

    /**
     * The categories this tag belongs to, e.g. {@code lifecycle} or {@code review}, used for grouping and filtering
     * tags in listings.
     *
     * @return the categories, an empty list if none are set
     */
    public List<String> getCategories()
    {
        return this.categories == null ? List.of() : List.of(this.categories);
    }

    /**
     * Whether resources under a tagged node implicitly carry this tag too, e.g. every answer inside a "sensitive"
     * submission is itself sensitive.
     *
     * @return {@code true} if this tag is passed down to the descendants of a tagged node
     */
    public boolean isInheritable()
    {
        return this.inheritable;
    }

    /**
     * Whether a node implicitly carries this tag when any of its descendants explicitly does, e.g. a submission with
     * an "incomplete" answer is itself incomplete.
     *
     * @return {@code true} if this tag is passed up from tagged nodes to their ancestors
     */
    public boolean isAggregated()
    {
        return this.aggregated;
    }

    /**
     * The {@code sling:resourceType} values of the resources this tag may be placed on, including their subtypes.
     * When empty, the tag may be placed on any resource.
     *
     * @return the accepted resource types, an empty list if the tag is unrestricted
     */
    public List<String> getTargetResources()
    {
        return this.targetResources == null ? List.of() : List.of(this.targetResources);
    }

    /**
     * An optional color used when displaying this tag, as a CSS color value.
     *
     * @return the color, or {@code null} if not set
     */
    public String getColor()
    {
        return this.color;
    }

    /**
     * The optional explicit position of this tag in listings; tags with a lower order are displayed first, tags
     * without an order are displayed last.
     *
     * @return the order, or {@code null} if not set
     */
    public Long getOrder()
    {
        return this.order;
    }

    /**
     * Whether this tag is managed by the platform itself, which prevents adding or removing it through the regular
     * user-facing APIs.
     *
     * @return {@code true} if this tag may only be managed by the platform
     */
    public boolean isSystem()
    {
        return this.system;
    }

    /**
     * Checks whether this tag may be placed on the given resource, according to the definition's
     * {@link #getTargetResources() accepted resource types}.
     *
     * @param target a candidate resource to be tagged
     * @return {@code true} if the resource is of (a subtype of) one of the accepted resource types, or if this
     *         definition doesn't restrict its targets
     */
    public boolean appliesTo(final Resource target)
    {
        return this.targetResources == null || this.targetResources.length == 0
            || getTargetResources().stream().anyMatch(target::isResourceType);
    }
}

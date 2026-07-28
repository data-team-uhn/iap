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

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.content.models.Content;

/**
 * A Sling Model wrapping an {@code iap:LinkDefinition} node: the definition of a type of link, stored under
 * {@code /LinkTypes}. The definition is the single source of truth for what a connection means, what it may
 * connect, and how it behaves.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = LinkDefinition.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class LinkDefinition extends Content
{
    /** The {@code sling:resourceType} of an {@code iap:LinkDefinition} node. */
    public static final String RESOURCE_TYPE = "iap/LinkDefinition";

    /**
     * What to do with the linking resource when the linked resource is deleted. Declared in the data model, but
     * not enforced yet; enforcement arrives with the workflow engine's deletion handling.
     *
     * @since 0.1.0
     */
    public enum OnDelete
    {
        /** Keep the link as a broken reference. Only valid for weak links. */
        IGNORE,
        /** Only remove the link between resources, keep the linking resource in place. */
        REMOVE_LINK,
        /** Also delete the linking resource, and any others it may impact. */
        RECURSIVE_DELETE
    }

    @ValueMapValue
    private String label;

    @ValueMapValue
    private Boolean displayed;

    @ValueMapValue
    private boolean external;

    @ValueMapValue
    private boolean weak;

    @ValueMapValue
    private String[] requiredSourceTypes;

    @ValueMapValue
    private String[] requiredDestinationTypes;

    @ValueMapValue
    private String resourceLabelTemplate;

    @ValueMapValue
    private String backlink;

    @ValueMapValue
    private boolean backlinkOnly;

    @ValueMapValue
    private String onDelete;

    @ValueMapValue
    private String valuePattern;

    @ValueMapValue
    private String urlTemplate;

    /**
     * A user-friendly name for this type of link.
     *
     * @return the configured label, or the node name if none is set
     */
    @NotNull
    public String getLabel()
    {
        return this.label == null ? this.getName() : this.label;
    }

    /**
     * Whether links of this type appear in the user-facing UI. This is only a rendering hint, not access control:
     * the links remain readable by anyone who can read the resource holding them, and administrative or diagnostic
     * views ignore it.
     *
     * @return {@code false} only when the definition explicitly opts out of display
     */
    public boolean isDisplayed()
    {
        return this.displayed == null || this.displayed;
    }

    /**
     * Whether links of this type point outside the repository, recording a value instead of referencing another
     * resource.
     *
     * @return {@code true} for an external link type
     */
    public boolean isExternal()
    {
        return this.external;
    }

    /**
     * Whether links of this type hold a weak reference, which may break when the linked resource is deleted,
     * instead of a hard one, which prevents that deletion.
     *
     * @return {@code true} if this link type is weak
     */
    public boolean isWeak()
    {
        return this.weak;
    }

    /**
     * An optional list of node types allowed as the linking resource. When empty, any node can hold this link.
     *
     * @return an array of node type names, or {@code null} if unrestricted
     */
    @Nullable
    public String[] getRequiredSourceTypes()
    {
        // A copy, since arrays are mutable and callers must not be able to alter the model's own state
        return this.requiredSourceTypes == null ? null : this.requiredSourceTypes.clone();
    }

    /**
     * An optional list of node types allowed as the linked resource. When empty, any node can be linked to.
     *
     * @return an array of node type names, or {@code null} if unrestricted
     */
    @Nullable
    public String[] getRequiredDestinationTypes()
    {
        // A copy, since arrays are mutable and callers must not be able to alter the model's own state
        return this.requiredDestinationTypes == null ? null : this.requiredDestinationTypes.clone();
    }

    /**
     * An optional template rendering a nicer label for the link target, e.g. {@code {typeLabel}: {name}}. See the
     * node type definition for the supported placeholders.
     *
     * @return a template, or {@code null} if the target's natural label should be used
     */
    @Nullable
    public String getResourceLabelTemplate()
    {
        return this.resourceLabelTemplate;
    }

    /**
     * Whether a reverse link is automatically added from the linked resource back to the linking resource.
     *
     * @return {@code true} if a backlink is configured
     */
    public boolean hasBacklink()
    {
        return this.backlink != null;
    }

    /**
     * The definition of the reverse link automatically added from the linked resource back to the linking
     * resource. A definition may name itself, for a symmetrical double link.
     *
     * @return a link definition, or {@code null} if no backlink is configured or it cannot be resolved
     */
    @Nullable
    public LinkDefinition getBacklink()
    {
        if (this.backlink == null) {
            return null;
        }
        final Resource target = this.resource.getResourceResolver().getResource(this.backlink);
        return target == null ? null : target.adaptTo(LinkDefinition.class);
    }

    /**
     * Whether this link type can only be instantiated as an automatically created backlink, never directly.
     *
     * @return {@code true} if direct creation is forbidden
     */
    public boolean isBacklinkOnly()
    {
        return this.backlinkOnly;
    }

    /**
     * The policy to apply to the linking resource when the linked resource is deleted.
     *
     * @return a deletion policy, {@link OnDelete#REMOVE_LINK} if not set or unrecognized
     */
    @NotNull
    public OnDelete getOnDeletePolicy()
    {
        if (this.onDelete == null) {
            return OnDelete.REMOVE_LINK;
        }
        try {
            return OnDelete.valueOf(this.onDelete);
        } catch (final IllegalArgumentException ex) {
            return OnDelete.REMOVE_LINK;
        }
    }

    /**
     * For external link types, an optional regular expression that recorded values must fully match.
     *
     * @return a regular expression, or {@code null} if values are unrestricted
     */
    @Nullable
    public String getValuePattern()
    {
        return this.valuePattern;
    }

    /**
     * For external link types, an optional URL template turning the recorded value into a navigable address,
     * with {@code {value}} standing for the recorded value.
     *
     * @return a template, or {@code null} if values are not navigable
     */
    @Nullable
    public String getUrlTemplate()
    {
        return this.urlTemplate;
    }
}

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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
 * A Sling Model wrapping an {@code link:Definition} node: the definition of a type of link, stored under
 * {@code /LinkTypes}. The definition is the single source of truth for what a connection means, what it may
 * connect, and how it behaves, and each one documents itself as an entry of the catalogue served at
 * {@code /LinkTypes.doc.json} and {@code /LinkTypes.doc.md}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = LinkDefinition.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class LinkDefinition extends Content implements DocumentedItem
{
    /** The {@code sling:resourceType} of an {@code link:Definition} node. */
    public static final String RESOURCE_TYPE = "link/Definition";

    /**
     * What to do with the linking resource when the linked resource is deleted. Enforced by the deletion
     * service, which consults this policy for every link pointing at a resource being deleted.
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
    private String description;

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
    private String targetLabelTemplate;

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
     * A longer explanation of what this type of link means and when it applies.
     *
     * @return a description, or {@code null} if none is set
     */
    @Override
    @Nullable
    public String getDescription()
    {
        return this.description;
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
    public String getTargetLabelTemplate()
    {
        return this.targetLabelTemplate;
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

    @Override
    @NotNull
    public String getDocumentationLabel()
    {
        return getLabel();
    }

    @Override
    @NotNull
    public List<String> getDocumentationDetails()
    {
        final List<String> details = new ArrayList<>();
        addBehaviorDetails(details);
        addRestrictionDetails(details);
        if (!isDisplayed()) {
            details.add("**Hidden**: not shown in the user-facing UI");
        }
        return details;
    }

    private void addBehaviorDetails(final List<String> details)
    {
        if (isExternal()) {
            details.add("**External**: records a value pointing outside the repository");
        }
        if (isWeak()) {
            details.add("**Weak**: the link may break when the linked resource is deleted,"
                + " instead of preventing the deletion");
        }
        if (hasBacklink()) {
            details.add("**Backlink**: a reverse `" + this.backlink
                + "` link is automatically added on the linked content");
        }
        if (isBacklinkOnly()) {
            details.add("**Backlink only**: never created directly, only as the automatic reverse of another link");
        }
        if (getOnDeletePolicy() == OnDelete.IGNORE) {
            details.add("**On delete**: kept as a broken reference when the linked resource is deleted");
        } else if (getOnDeletePolicy() == OnDelete.RECURSIVE_DELETE) {
            details.add("**On delete**: the linking resource is deleted together with the linked resource");
        }
    }

    private void addRestrictionDetails(final List<String> details)
    {
        if (this.requiredSourceTypes != null && this.requiredSourceTypes.length > 0) {
            details.add("**May only be placed on**: `" + String.join("`, `", this.requiredSourceTypes) + "`");
        }
        if (this.requiredDestinationTypes != null && this.requiredDestinationTypes.length > 0) {
            details.add("**May only point at**: `" + String.join("`, `", this.requiredDestinationTypes) + "`");
        }
        if (this.valuePattern != null) {
            details.add("**Value pattern**: `" + this.valuePattern + "`");
        }
        if (this.urlTemplate != null) {
            details.add("**URL template**: `" + this.urlTemplate + "`");
        }
    }

    @Override
    @NotNull
    public JsonObjectBuilder documentationJsonBuilder()
    {
        final JsonObjectBuilder json = DocumentedItem.super.documentationJsonBuilder()
            .add("external", isExternal())
            .add("weak", isWeak())
            .add("backlinkOnly", isBacklinkOnly())
            .add("displayed", isDisplayed())
            .add("onDelete", getOnDeletePolicy().name());
        if (hasBacklink()) {
            json.add("backlink", this.backlink);
        }
        addTypeList(json, "requiredSourceTypes", this.requiredSourceTypes);
        addTypeList(json, "requiredDestinationTypes", this.requiredDestinationTypes);
        if (this.valuePattern != null) {
            json.add("valuePattern", this.valuePattern);
        }
        if (this.urlTemplate != null) {
            json.add("urlTemplate", this.urlTemplate);
        }
        return json.add("path", getPath());
    }

    private void addTypeList(final JsonObjectBuilder json, final String name, final String[] types)
    {
        if (types != null && types.length > 0) {
            final JsonArrayBuilder list = Json.createArrayBuilder();
            Arrays.stream(types).forEach(list::add);
            json.add(name, list);
        }
    }
}

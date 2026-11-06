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

import jakarta.json.JsonObjectBuilder;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A type of link to content in this repository, defined by a {@code link:InternalDefinition} node, whose links are
 * {@link InternalLink}s. It says what a link may point at, whether the reference is hard or weak, what happens when
 * the target is deleted, and whether the target links back.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = InternalLinkDefinition.RESOURCE_TYPE,
    adapters = { InternalLinkDefinition.class, LinkDefinition.class },
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class InternalLinkDefinition extends LinkDefinition
{
    /** The resource type of {@code link:InternalDefinition}. */
    public static final String RESOURCE_TYPE = "link/InternalDefinition";

    /**
     * What happens to a link, and to the content holding it, when the linked content is deleted.
     *
     * @since 0.1.0
     */
    public enum OnDelete
    {
        /** The link stays, pointing at nothing. Only valid for weak links. */
        IGNORE,
        /** The link is removed, and the content holding it stays. */
        REMOVE_LINK,
        /** The content holding the link is deleted too, with whatever that deletion takes along. */
        RECURSIVE_DELETE
    }

    @ValueMapValue
    private boolean weak;

    @ValueMapValue
    private String[] requiredDestinationTypes;

    @ValueMapValue
    private String backlink;

    @ValueMapValue
    private boolean backlinkOnly;

    @ValueMapValue
    private String onDelete;

    @Override
    @NotNull
    protected String getKind()
    {
        return "internal";
    }

    /**
     * Whether links of this type hold a weak reference, which lets the target be deleted, rather than a hard one,
     * which stops the deletion.
     *
     * @return {@code true} if this link type is weak
     */
    public boolean isWeak()
    {
        return this.weak;
    }

    /**
     * The node types the linked content must have, any one of them.
     *
     * @return node type names, such as {@code data:Entity}; {@code null} or empty if any content may be linked to
     */
    @Nullable
    public String[] getRequiredDestinationTypes()
    {
        return this.requiredDestinationTypes == null ? null : this.requiredDestinationTypes.clone();
    }

    /**
     * Whether links of this type get a reverse link, from the linked content back to the content holding them.
     *
     * @return {@code true} if the definition names a backlink
     */
    public boolean hasBacklink()
    {
        return this.backlink != null;
    }

    /**
     * The definition of the reverse link. A definition may name itself, for a pair of links of the same type. The
     * reverse is a link to content, so only an internal definition can serve.
     *
     * @return the definition; {@code null} if none is named, this session cannot read it, or it is an external
     *         definition
     */
    @Nullable
    public InternalLinkDefinition getBacklink()
    {
        if (this.backlink == null) {
            return null;
        }
        final Resource target = this.resource.getResourceResolver().getResource(this.backlink);
        // Adapting does not check the resource type, so an external definition would come back as an internal one
        if (target == null || !target.isResourceType(RESOURCE_TYPE)) {
            return null;
        }
        return target.adaptTo(InternalLinkDefinition.class);
    }

    /**
     * Whether links of this type are only created as the reverse of another link, never directly.
     *
     * @return {@code true} if a link of this type cannot be added directly
     */
    public boolean isBacklinkOnly()
    {
        return this.backlinkOnly;
    }

    /**
     * What happens when the linked content is deleted.
     *
     * @return the policy; {@link OnDelete#REMOVE_LINK} if none is set, or the value names no policy
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

    @Override
    protected void addBehaviorDetails(@NotNull final List<String> details)
    {
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

    @Override
    protected void addRestrictionDetails(@NotNull final List<String> details)
    {
        super.addRestrictionDetails(details);
        if (this.requiredDestinationTypes != null && this.requiredDestinationTypes.length > 0) {
            details.add("**May only point at**: `" + String.join("`, `", this.requiredDestinationTypes) + "`");
        }
    }

    @Override
    @NotNull
    public JsonObjectBuilder documentationJsonBuilder()
    {
        final JsonObjectBuilder json = super.documentationJsonBuilder()
            .add("weak", isWeak())
            .add("backlinkOnly", isBacklinkOnly())
            .add("onDelete", getOnDeletePolicy().name());
        if (hasBacklink()) {
            json.add("backlink", this.backlink);
        }
        addTypeList(json, "requiredDestinationTypes", this.requiredDestinationTypes);
        return json;
    }
}

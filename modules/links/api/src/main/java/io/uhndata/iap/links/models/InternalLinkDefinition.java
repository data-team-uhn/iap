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
 * A Sling Model wrapping an {@code link:InternalDefinition} node: a type of link whose targets are resources in
 * this repository, instantiated as {@link InternalLink}s. Everything about referential integrity lives here —
 * what may be pointed at, whether the reference is hard or weak, what happens when the target is deleted, and
 * whether the target points back — since none of it means anything without a target the repository knows about.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = InternalLinkDefinition.RESOURCE_TYPE,
    adapters = { InternalLinkDefinition.class, LinkDefinition.class },
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class InternalLinkDefinition extends LinkDefinition
{
    /** The {@code sling:resourceType} of an {@code link:InternalDefinition} node. */
    public static final String RESOURCE_TYPE = "link/InternalDefinition";

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
     * resource. A definition may name itself, for a symmetrical double link. A backlink is itself a reference
     * to content, so only an internal definition can serve as one.
     *
     * @return a link definition, or {@code null} if no backlink is configured, it cannot be resolved, or it does
     *         not name an internal link type
     */
    @Nullable
    public InternalLinkDefinition getBacklink()
    {
        if (this.backlink == null) {
            return null;
        }
        final Resource target = this.resource.getResourceResolver().getResource(this.backlink);
        // The resource type check is what makes a wrong kind report as a miss: adaptation is not a type filter,
        // so an external definition would otherwise come back as a model wrapping a node that has none of the
        // settings this one reads
        if (target == null || !target.isResourceType(RESOURCE_TYPE)) {
            return null;
        }
        return target.adaptTo(InternalLinkDefinition.class);
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

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
 * <p>This is the abstract base holding what every kind of link type says regardless of what it points at.
 * Everything that depends on where the target lives belongs to a concrete kind — {@link InternalLinkDefinition}
 * for targets inside the repository, {@link ExternalLinkDefinition} for recorded values — so a definition never
 * offers a setting that means nothing for it. Like the other abstract bases in the data model, this class is
 * deliberately not itself a registered Sling Model: each subtype declares {@code adapters = LinkDefinition.class}
 * on its own {@code @Model}, so {@code resource.adaptTo(LinkDefinition.class)} dispatches to the actual kind.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public abstract class LinkDefinition extends Content implements DocumentedItem
{
    /** The {@code sling:resourceType} of an {@code link:Definition} node, shared by every kind of definition. */
    public static final String RESOURCE_TYPE = "link/Definition";

    @ValueMapValue
    private String label;

    @ValueMapValue
    private String description;

    @ValueMapValue
    private Boolean displayed;

    @ValueMapValue
    private String[] requiredSourceTypes;

    @ValueMapValue
    private String targetLabelTemplate;

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
     * The kind of link type this is, naming the concrete subtype for the self-documenting catalogue. Not part of
     * the Java API on purpose: callers distinguish the kinds by their model type, and a string would invite
     * branching on a value where the type system already has the answer.
     *
     * @return a short lowercase kind name, e.g. {@code internal}
     */
    @NotNull
    protected abstract String getKind();

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

    /**
     * Describes what this kind of link type does, one bullet per behavior worth calling out. Left to the kinds
     * entirely: every behavior a definition can have depends on what it points at, so there is nothing here to
     * share.
     *
     * @param details the list to append to
     */
    protected abstract void addBehaviorDetails(@NotNull List<String> details);

    /**
     * Describes the limits placed on where this link type may be used. Subtypes calling this add their own after.
     *
     * @param details the list to append to
     */
    protected void addRestrictionDetails(@NotNull final List<String> details)
    {
        if (this.requiredSourceTypes != null && this.requiredSourceTypes.length > 0) {
            details.add("**May only be placed on**: `" + String.join("`, `", this.requiredSourceTypes) + "`");
        }
    }

    @Override
    @NotNull
    public JsonObjectBuilder documentationJsonBuilder()
    {
        final JsonObjectBuilder json = DocumentedItem.super.documentationJsonBuilder()
            .add("kind", getKind())
            .add("displayed", isDisplayed());
        addTypeList(json, "requiredSourceTypes", this.requiredSourceTypes);
        return json.add("path", getPath());
    }

    /**
     * Appends a list of node type names, leaving the entry out entirely when there are none.
     *
     * @param json the builder to append to
     * @param name the name of the JSON property
     * @param types the node type names, may be {@code null} or empty
     */
    protected void addTypeList(@NotNull final JsonObjectBuilder json, @NotNull final String name,
        @Nullable final String[] types)
    {
        if (types != null && types.length > 0) {
            final JsonArrayBuilder list = Json.createArrayBuilder();
            Arrays.stream(types).forEach(list::add);
            json.add(name, list);
        }
    }
}

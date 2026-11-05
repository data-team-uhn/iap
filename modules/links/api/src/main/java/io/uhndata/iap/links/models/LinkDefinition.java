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
import io.uhndata.iap.utils.Referenceable;

/**
 * A type of link, defined by a {@code link:Definition} node under {@code /LinkTypes}: what a link of this type means,
 * what it may connect, and how it behaves. Each definition is an entry of the catalogue served at
 * {@code /LinkTypes.doc.json} and {@code /LinkTypes.doc.md}.
 *
 * <p>This abstract class holds the settings every kind of link type has. What depends on where the target lives
 * belongs to a kind: {@link InternalLinkDefinition} for content in the repository, {@link ExternalLinkDefinition}
 * for a value naming something outside it.</p>
 *
 * <p>It is not a Sling Model itself. Each kind lists {@code LinkDefinition} among the adapters of its own
 * {@code @Model}, so {@code resource.adaptTo(LinkDefinition.class)} returns the model of the definition's
 * kind.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public abstract class LinkDefinition extends Content implements DocumentedItem, Referenceable
{
    /** The resource type of {@code link:Definition}, which the resource type of every kind extends. */
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
     * The name of this type of link, as people read it.
     *
     * @return the {@code label} property; the node name if it is not set
     */
    @NotNull
    public String getLabel()
    {
        return this.label == null ? this.getName() : this.label;
    }

    /**
     * What this type of link means and when it applies.
     *
     * @return plain text; {@code null} if none is set
     */
    @Override
    @Nullable
    public String getDescription()
    {
        return this.description;
    }

    /**
     * Whether a user interface should show links of this type. It is a hint, not access control: anybody who can
     * read a resource can read its links.
     *
     * @return {@code true} unless the definition sets {@code displayed} to {@code false}
     */
    public boolean isDisplayed()
    {
        return this.displayed == null || this.displayed;
    }

    /**
     * The kind of this link type, as the catalogue names it. Code tells the kinds apart by their model class.
     *
     * @return {@code internal} or {@code external}
     */
    @NotNull
    protected abstract String getKind();

    /**
     * The node types the content holding a link of this type must have, any one of them.
     *
     * @return node type names, such as {@code data:Entity}; {@code null} or empty if any content may hold it
     */
    @Nullable
    public String[] getRequiredSourceTypes()
    {
        return this.requiredSourceTypes == null ? null : this.requiredSourceTypes.clone();
    }

    /**
     * A template for the label of a link's target, such as {@code {typeLabel}: {name}}. The {@code link:Definition}
     * node type lists the placeholders.
     *
     * @return the template; {@code null} to use the target's node name, or an external link's value
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
     * Adds a Markdown bullet for each behavior of this link type a reader should know about.
     *
     * @param details the list to add to
     */
    protected abstract void addBehaviorDetails(@NotNull List<String> details);

    /**
     * Adds a Markdown bullet for each limit on what links of this type may connect. A kind that overrides it calls
     * it first.
     *
     * @param details the list to add to
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
     * Adds an array of node type names to the JSON, unless there are none.
     *
     * @param json the builder to add to
     * @param name the name of the JSON property
     * @param types the node type names; {@code null} or empty to add nothing
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

    @Override
    @NotNull
    public String getIdentifier()
    {
        return this.identifier;
    }
}

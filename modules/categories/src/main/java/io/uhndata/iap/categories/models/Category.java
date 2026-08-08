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
package io.uhndata.iap.categories.models;

import java.util.List;

import jakarta.json.JsonObjectBuilder;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.autodoc.api.DocumentedItem;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.schemas.models.SchemaVersion;

/**
 * A Sling Model wrapping a {@code cat:Category} node, one submission category in the {@code /Categories} tree.
 * Categories nest by path; a category with no subcategories is a leaf that submissions may be filed under. Leaf
 * categories appear as entries in the tree's self-served documentation (see {@link CategoriesHomepage}), hence
 * the {@link DocumentedItem} implementation.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Category.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Category extends Entity implements DocumentedItem
{
    /** The {@code sling:resourceType} of a {@code cat:Category} node. */
    public static final String RESOURCE_TYPE = "cat/Category";

    @ValueMapValue
    private String label;

    @ValueMapValue
    private String description;

    @ValueMapValue
    private boolean retired;

    @ValueMapValue
    private String schemaVersion;

    /**
     * The human-readable name displayed to submitters.
     *
     * @return a label
     */
    @NotNull
    public String getLabel()
    {
        return this.label;
    }

    /**
     * The prompt-ready description of what belongs in this category, used both as guidance for submitters and as
     * input for AI-assisted categorization.
     *
     * @return a description, or {@code null} if not set
     */
    @Override
    @Nullable
    public String getDescription()
    {
        return this.description;
    }

    /**
     * Whether this category is retired. No new submissions may be filed under a retired category or its
     * subcategories, but existing submissions keep referencing it.
     *
     * @return {@code true} if this category is retired
     */
    public boolean isRetired()
    {
        return this.retired;
    }

    /**
     * The schema version governing submissions filed under this category; the schema version in turn references the
     * workflow they follow. Only leaf categories are expected to carry a schema version, and categories without one
     * fall back to a default behavior.
     *
     * @return a schema version, or {@code null} if not set or unresolvable
     */
    @Nullable
    public SchemaVersion getSchemaVersion()
    {
        return this.getReference(this.schemaVersion, SchemaVersion.class);
    }

    /**
     * The subcategories of this category, in their stored order.
     *
     * @return a list of categories, empty if none
     */
    @NotNull
    public List<Category> getSubcategories()
    {
        return this.getChildren(RESOURCE_TYPE, Category.class);
    }

    /**
     * Whether this category is a leaf, i.e. has no subcategories. Only leaf categories may be assigned to
     * submissions.
     *
     * @return {@code true} if this category has no subcategories
     */
    public boolean isLeaf()
    {
        return this.getSubcategories().isEmpty();
    }

    @Override
    @NotNull
    public String getDocumentationLabel()
    {
        return this.label != null ? this.label : getName();
    }

    /**
     * Renders like the default, but without the technical node name after the label: the labels are what the
     * catalogue's readers (and the categorization prompt) work with, and machine consumers get the exact
     * identifiers from the JSON serialization instead. The heading is level 2 rather than the default's 3, since
     * the flat catalogue has no section headings in between. Details are not rendered, as categories declare none.
     *
     * @return a Markdown fragment with a level 2 heading
     */
    @Override
    @NotNull
    public String toMarkdown()
    {
        final StringBuilder out = new StringBuilder("## " + getDocumentationLabel() + '\n');
        final String text = getDescription();
        if (text != null) {
            // Turn raw line breaks into Markdown hard line breaks, same as the default rendering
            out.append('\n').append(text.replaceAll("\n", "  \n")).append('\n');
        }
        return out.toString();
    }

    /**
     * Adds the {@code path} and {@code id} of the category to the base serialization: the catalogue's consumers
     * reference the chosen category through one of these, since node names are only unique among siblings.
     *
     * @return a JSON object builder holding this category's data, still open for more fields
     */
    @Override
    @NotNull
    public JsonObjectBuilder documentationJsonBuilder()
    {
        return DocumentedItem.super.documentationJsonBuilder()
            .add("path", getPath())
            .add("id", getIdentifier());
    }
}

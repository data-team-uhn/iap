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
import io.uhndata.iap.tags.models.Taggable;

/**
 * One submission category in the {@code /Categories} tree. A category with no subcategories is a leaf, and
 * submissions are filed under leaves. The live leaves are the entries of the catalogue that
 * {@link CategoriesHomepage} serves.
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

    /** The inheritable tag that closes a category, and everything under it, to new submissions. */
    public static final String RETIRED_TAG = "retired";

    @ValueMapValue
    private String label;

    @ValueMapValue
    private String description;

    @ValueMapValue
    private String schemaVersion;

    /**
     * The name submitters see.
     *
     * @return the label
     */
    @NotNull
    public String getLabel()
    {
        return this.label;
    }

    /**
     * What belongs in this category, written as prose for whoever chooses a category.
     *
     * @return plain text, which may span several lines; {@code null} if none is set
     */
    @Override
    @Nullable
    public String getDescription()
    {
        return this.description;
    }

    /**
     * Whether this category is closed to new submissions. The {@code retired} tag is inheritable, so a category
     * under a retired ancestor is retired too.
     *
     * @return {@code true} if this category or an ancestor carries the tag; {@code false} if neither does, or if
     *         the content cannot be read as {@code Taggable}
     */
    public boolean isRetired()
    {
        final Taggable tags = this.as(Taggable.class);
        return tags != null && tags.hasTag(RETIRED_TAG);
    }

    /**
     * Whether this category carries the {@code retired} tag itself, not only through an ancestor. Only the category
     * carrying the tag can have it taken off.
     *
     * @return {@code true} if the tag is placed on this category itself
     */
    public boolean isRetiredHere()
    {
        final Taggable tags = this.as(Taggable.class);
        return tags != null && tags.hasOwnTag(RETIRED_TAG);
    }

    /**
     * The schema version that submissions filed under this category follow. Only leaves are expected to carry one.
     *
     * @return the schema version; {@code null} if none is set, or if the reference cannot be resolved
     */
    @Nullable
    public SchemaVersion getSchemaVersion()
    {
        return this.getReference(this.schemaVersion, SchemaVersion.class);
    }

    /**
     * The subcategories of this category, in their stored order.
     *
     * @return the categories directly under this one; empty for a leaf
     */
    @NotNull
    public List<Category> getSubcategories()
    {
        return this.getChildren(RESOURCE_TYPE, Category.class);
    }

    /**
     * Whether this category is a leaf, which submissions can be filed under.
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
     * This category as Markdown: a level 2 heading with the label, then the description. The heading leaves out the
     * node name, which the JSON form carries. The catalogue has no category sections, so its entries sit one level
     * below its title.
     *
     * @return the Markdown fragment
     */
    @Override
    @NotNull
    public String toMarkdown()
    {
        final StringBuilder out = new StringBuilder("## " + getDocumentationLabel() + '\n');
        final String text = getDescription();
        if (text != null) {
            // Two spaces before a line break keep it in Markdown
            out.append('\n').append(text.replaceAll("\n", "  \n")).append('\n');
        }
        return out.toString();
    }

    /**
     * The JSON form of this category, still open for more fields. A node name is unique only among its siblings, so
     * the form adds the category's path and {@code jcr:uuid} to the base fields.
     *
     * @return a builder holding the base fields, the {@code path}, and the {@code jcr:uuid} as {@code id}
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

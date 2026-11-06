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
package io.uhndata.iap.autodoc.api;

import java.util.List;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One entry in an {@link AutoDocumentable} catalogue, such as one tag. It needs only a name and a description. The
 * JSON and Markdown forms also show its label and its categories. An entry with more to say adds
 * Markdown bullets through {@link #getDocumentationDetails}, and JSON fields through
 * {@link #documentationJsonBuilder}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface DocumentedItem
{
    /**
     * The name other content refers to this item by, such as a tag's name.
     *
     * @return the name, exactly as a reference writes it
     */
    @NotNull
    String getName();

    /**
     * The human-readable name of this item.
     *
     * @return a short display string; the {@link #getName name} unless the implementation gives another
     */
    @NotNull
    default String getDocumentationLabel()
    {
        return getName();
    }

    /**
     * What this item means and when it applies.
     *
     * @return plain text, which may span several lines; {@code null} if there is none
     */
    @Nullable
    String getDescription();

    /**
     * The categories this item is listed under.
     *
     * @return the categories' names; empty to list it under {@value AutoDocumentable#DEFAULT_CATEGORY}
     */
    @NotNull
    default List<String> getDocumentationCategories()
    {
        return List.of();
    }

    /**
     * What else a reader should know about this item, each shown as a bullet after the description, such as
     * {@code **Inheritable**: implicitly carried by everything inside a tagged resource}.
     *
     * @return one Markdown fragment per bullet; empty if there are none
     */
    @NotNull
    default List<String> getDocumentationDetails()
    {
        return List.of();
    }

    /**
     * The JSON form of this item, still open for more fields. An implementation with more to say adds its fields to
     * what {@code DocumentedItem.super.documentationJsonBuilder()} returns.
     *
     * @return a builder holding the {@code name}, the {@code label}, the {@code description} if there is one, and
     *         the {@code category} array if there are any
     */
    @NotNull
    default JsonObjectBuilder documentationJsonBuilder()
    {
        final JsonObjectBuilder json = Json.createObjectBuilder()
            .add("name", getName())
            .add("label", getDocumentationLabel());
        final String description = getDescription();
        if (description != null) {
            json.add("description", description);
        }
        if (!getDocumentationCategories().isEmpty()) {
            final JsonArrayBuilder categories = Json.createArrayBuilder();
            getDocumentationCategories().forEach(categories::add);
            json.add("category", categories);
        }
        return json;
    }

    /**
     * This item as JSON, as {@link #documentationJsonBuilder} builds it.
     *
     * @return the built object
     */
    @NotNull
    default JsonObject toDocumentationJson()
    {
        return documentationJsonBuilder().build();
    }

    /**
     * This item as Markdown: a level 3 heading with the label and the name, the description, and one bullet per
     * {@link #getDocumentationDetails detail}.
     *
     * @return the Markdown fragment
     */
    @NotNull
    default String toMarkdown()
    {
        final StringBuilder out =
            new StringBuilder("### " + getDocumentationLabel() + " (`" + getName() + "`)\n");
        final String description = getDescription();
        if (description != null) {
            // Two spaces before a line break keep it in Markdown
            out.append('\n').append(description.replaceAll("\n", "  \n")).append('\n');
        }
        if (!getDocumentationDetails().isEmpty()) {
            out.append('\n');
            getDocumentationDetails().forEach(detail -> out.append("- ").append(detail).append('\n'));
        }
        return out.toString();
    }
}

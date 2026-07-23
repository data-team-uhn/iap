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
package io.uhndata.iap.documentation.api;

import java.util.List;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

/**
 * One entry in a {@link SelfDocumenting self-documenting} catalogue: a defined tag, a supported workflow node type,
 * a tracked metric. The base contract is minimal (a name, an optional label, description and categories), and comes
 * with default JSON and Markdown serializations built from it; an implementation only overrides what it has to say
 * more: typically {@link #getDocumentationDetails} for extra behavior bullets in the Markdown rendering, and
 * {@link #documentationJsonBuilder} to append extra fields to the JSON one.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface DocumentedItem
{
    /**
     * The identifier of this item, the exact string used when referencing it, e.g. the tag name or the workflow
     * node type name.
     *
     * @return a simple string
     */
    String getName();

    /**
     * The human-readable name of this item.
     *
     * @return a short display string, the {@link #getName name} itself unless overridden
     */
    default String getDocumentationLabel()
    {
        return getName();
    }

    /**
     * A longer explanation of what this item means and when it applies.
     *
     * @return a description, or {@code null} if there is nothing more to say than the label
     */
    String getDescription();

    /**
     * The categories this item belongs to, used for grouping the catalogue into sections; an item belonging to
     * several categories is documented under each of them.
     *
     * @return category names, an empty list if the item is not categorized
     */
    default List<String> getDocumentationCategories()
    {
        return List.of();
    }

    /**
     * Extra behaviors or constraints of this item worth calling out, each rendered as one bullet point after the
     * description, e.g. {@code **Inheritable**: implicitly carried by everything inside a tagged resource}.
     *
     * @return Markdown fragments, one per bullet point, an empty list if there is nothing to call out
     */
    default List<String> getDocumentationDetails()
    {
        return List.of();
    }

    /**
     * Start the JSON serialization of this item with the base contract: {@code name}, {@code label}, and when
     * present, {@code description} and {@code category}. Implementations override this to append their extra
     * fields to {@code DocumentedItem.super.documentationJsonBuilder()}.
     *
     * @return a JSON object builder holding this item's data, still open for more fields
     */
    default JsonObjectBuilder documentationJsonBuilder()
    {
        final JsonObjectBuilder json = Json.createObjectBuilder()
            .add("name", getName())
            .add("label", getDocumentationLabel());
        if (getDescription() != null) {
            json.add("description", getDescription());
        }
        if (!getDocumentationCategories().isEmpty()) {
            final JsonArrayBuilder categories = Json.createArrayBuilder();
            getDocumentationCategories().forEach(categories::add);
            json.add("category", categories);
        }
        return json;
    }

    /**
     * Serialize this item as a JSON object, as assembled by {@link #documentationJsonBuilder}.
     *
     * @return a JSON object describing this item
     */
    default JsonObject toDocumentationJson()
    {
        return documentationJsonBuilder().build();
    }

    /**
     * Render the documentation of this item as a Markdown fragment: a heading with the label and name, the
     * description, and one bullet point per {@link #getDocumentationDetails detail}.
     *
     * @return a Markdown fragment with a level 3 heading
     */
    default String toMarkdown()
    {
        final StringBuilder out =
            new StringBuilder("### " + getDocumentationLabel() + " (`" + getName() + "`)\n");
        if (getDescription() != null) {
            // Turn raw line breaks into Markdown hard line breaks
            out.append('\n').append(getDescription().replaceAll("\n", "  \n")).append('\n');
        }
        if (!getDocumentationDetails().isEmpty()) {
            out.append('\n');
            getDocumentationDetails().forEach(detail -> out.append("- ").append(detail).append('\n'));
        }
        return out.toString();
    }
}

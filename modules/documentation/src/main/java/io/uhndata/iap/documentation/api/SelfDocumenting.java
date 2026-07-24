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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

/**
 * A configurable or extensible feature that documents itself at runtime: a title, an introduction, and a catalogue
 * of {@link DocumentedItem items} describing what is currently defined or supported, e.g. the defined tags or the
 * node types understood by the workflow engine. The default JSON and Markdown serializations group the items by
 * their categories, with items belonging to several categories repeated under each, and the section headings left
 * out entirely when nothing declares a category.
 *
 * <p>To serve the documentation over HTTP, implement this interface in a Sling Model registered as an adapter for
 * the resource, and mark the node with the {@code iap:Documented} mixin (directly, or as a supertype of its primary
 * type): the documentation then becomes available at the node's path with the {@code doc} selector and the
 * {@code json} or {@code md} extension.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface SelfDocumenting
{
    /** The category under which items without an explicit category are documented. */
    String UNCATEGORIZED = "uncategorized";

    /**
     * The title of the documentation, e.g. {@code Tags}.
     *
     * @return a short display string
     */
    String getDocumentationTitle();

    /**
     * An introductory text displayed under the title, explaining what the catalogue holds.
     *
     * @return a piece of text, or {@code null} if there is nothing more to say than the title
     */
    String getDocumentationIntro();

    /**
     * The items being documented, in display order.
     *
     * @return the documented items, may be empty
     */
    List<? extends DocumentedItem> getDocumentedItems();

    /**
     * The {@link #getDocumentedItems items} grouped by their categories: categories sorted by name, items kept in
     * display order, items belonging to several categories repeated under each, items without a category grouped
     * under {@value #UNCATEGORIZED}.
     *
     * @return the documented items, grouped by category
     */
    default Map<String, List<DocumentedItem>> getDocumentedItemsByCategory()
    {
        final Map<String, List<DocumentedItem>> categories = new TreeMap<>();
        for (final DocumentedItem item : getDocumentedItems()) {
            final List<String> declared = item.getDocumentationCategories();
            for (final String category : declared.isEmpty() ? List.of(UNCATEGORIZED) : declared) {
                categories.computeIfAbsent(category, key -> new ArrayList<>()).add(item);
            }
        }
        return categories;
    }

    /**
     * Serialize this documentation as a JSON object: the title, the introduction when present, and the items
     * grouped by category.
     *
     * @return a JSON object with the {@code title}, optional {@code description}, and {@code items} keys, the
     *         latter holding one array of items per category
     */
    default JsonObject toDocumentationJson()
    {
        final JsonObjectBuilder result = Json.createObjectBuilder()
            .add("title", getDocumentationTitle());
        if (getDocumentationIntro() != null) {
            result.add("description", getDocumentationIntro());
        }
        final JsonObjectBuilder items = Json.createObjectBuilder();
        getDocumentedItemsByCategory().forEach((category, list) -> {
            final JsonArrayBuilder array = Json.createArrayBuilder();
            list.forEach(item -> array.add(item.toDocumentationJson()));
            items.add(category, array);
        });
        return result.add("items", items).build();
    }

    /**
     * Render this documentation as a Markdown document: the title, the introduction when present, then one section
     * per category with the items documented in it. When no item declares a category, the items are listed directly
     * without section headings.
     *
     * @return a Markdown document
     */
    default String toMarkdown()
    {
        final StringBuilder out = new StringBuilder("# ").append(getDocumentationTitle()).append('\n');
        if (getDocumentationIntro() != null) {
            out.append('\n').append(getDocumentationIntro()).append('\n');
        }
        final Map<String, List<DocumentedItem>> categories = getDocumentedItemsByCategory();
        final boolean flat = categories.size() == 1 && categories.containsKey(UNCATEGORIZED);
        categories.forEach((category, items) -> {
            if (!flat) {
                out.append("\n## ").append(category).append('\n');
            }
            items.forEach(item -> out.append('\n').append(item.toMarkdown()));
        });
        return out.toString();
    }
}

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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A feature that documents itself at runtime: a title, an introduction, and a catalogue of the {@link DocumentedItem
 * items} the running instance defines, such as its tags.
 *
 * <p>To serve the documentation over HTTP, implement this interface in a Sling Model that lists it among its
 * adapters. Then give the node the {@code doc:Documented} mixin, directly or through its primary type, and it serves
 * its documentation at {@code <path>.doc.json} and {@code <path>.doc.md}.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface AutoDocumentable
{
    /** The mixin marking the nodes that serve documentation. */
    String DOCUMENTED_TYPE = "doc:Documented";

    /** The category of an item that names none. */
    String DEFAULT_CATEGORY = "uncategorized";

    /**
     * The title of the documentation, e.g. {@code Tags}.
     *
     * @return a short display string
     */
    @NotNull
    String getDocumentationTitle();

    /**
     * An introduction shown under the title, saying what the catalogue holds.
     *
     * @return the text, or {@code null} if the title needs no introduction
     */
    @Nullable
    String getDocumentationIntro();

    /**
     * The items being documented, in display order.
     *
     * @return the items; empty if the instance defines none
     */
    @NotNull
    List<? extends DocumentedItem> getDocumentedItems();

    /**
     * The {@link #getDocumentedItems items}, grouped by category. The categories are sorted by name, except
     * {@value #DEFAULT_CATEGORY}, which comes last and holds the items that name no category. An item in several
     * categories appears under each, and the items keep their display order.
     *
     * @return each category's name, mapped to its items
     */
    @NotNull
    default Map<String, List<DocumentedItem>> getDocumentedItemsByCategory()
    {
        final Map<String, List<DocumentedItem>> categories = new TreeMap<>(
            Comparator.comparing((String category) -> DEFAULT_CATEGORY.equals(category))
                .thenComparing(Comparator.naturalOrder()));
        for (final DocumentedItem item : getDocumentedItems()) {
            final List<String> declared = item.getDocumentationCategories();
            for (final String category : declared.isEmpty() ? List.of(DEFAULT_CATEGORY) : declared) {
                categories.computeIfAbsent(category, key -> new ArrayList<>()).add(item);
            }
        }
        return categories;
    }

    /**
     * This documentation as JSON.
     *
     * @return an object with the {@code title}, the {@code description} if there is an introduction, and
     *         {@code items}: one array of items per category, under the category's name
     */
    @NotNull
    default JsonObject toDocumentationJson()
    {
        final JsonObjectBuilder result = Json.createObjectBuilder()
            .add("title", getDocumentationTitle());
        final String intro = getDocumentationIntro();
        if (intro != null) {
            result.add("description", intro);
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
     * This documentation as Markdown: the title, the introduction if there is one, then one section per category.
     * When no item names a category, the items follow without section headings.
     *
     * @return a Markdown document
     */
    @NotNull
    default String toMarkdown()
    {
        final StringBuilder out = new StringBuilder("# ").append(getDocumentationTitle()).append('\n');
        final String intro = getDocumentationIntro();
        if (intro != null) {
            out.append('\n').append(intro).append('\n');
        }
        final Map<String, List<DocumentedItem>> categories = getDocumentedItemsByCategory();
        final boolean flat = categories.size() == 1 && categories.containsKey(DEFAULT_CATEGORY);
        categories.forEach((category, items) -> {
            if (!flat) {
                out.append("\n## ").append(category).append('\n');
            }
            items.forEach(item -> out.append('\n').append(item.toMarkdown()));
        });
        return out.toString();
    }
}

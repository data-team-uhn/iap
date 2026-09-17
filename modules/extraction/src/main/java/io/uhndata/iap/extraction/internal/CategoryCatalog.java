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
package io.uhndata.iap.extraction.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.categories.models.CategoriesHomepage;
import io.uhndata.iap.categories.models.Category;

/**
 * The categories a proposal may be filed under, as a model is shown them: the live leaves of the categories
 * tree, one line each, and the lookup that turns a model's answer back into one of them.
 *
 * <p>The descriptions are what the model judges by - they were written for that - so they go in whole. The id
 * the model answers with is the category's path; a label is accepted too, since a model will sometimes copy
 * the wrong half of the line.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class CategoryCatalog
{
    /** Where the categories tree lives. */
    static final String ROOT_PATH = "/Categories";

    /** The header of the block, naming its columns. */
    static final String HEADER = "## CATEGORIES (id: label -- description)";

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * One category a proposal can be filed under.
     *
     * @param path the category's path, which is the id the model answers with
     * @param label what submitters see it called
     * @param description what studies belong there, in prose; blank when the category has none
     * @since 0.1.0
     */
    record Entry(String path, String label, String description)
    {
    }

    private CategoryCatalog()
    {
        // Utility
    }

    /**
     * The live leaves of the categories tree, in the order administrators arranged them.
     *
     * @param resolver how to reach the tree
     * @return the categories that can be chosen; empty when there is no tree
     */
    static List<Entry> read(final ResourceResolver resolver)
    {
        final Resource root = resolver.getResource(ROOT_PATH);
        final CategoriesHomepage homepage = root == null ? null : root.adaptTo(CategoriesHomepage.class);
        if (homepage == null) {
            return List.of();
        }
        final List<Entry> entries = new ArrayList<>();
        for (final Category category : homepage.getDocumentedItems()) {
            final String description = category.getDescription();
            entries.add(new Entry(category.getPath(), category.getDocumentationLabel(),
                description == null ? "" : description));
        }
        return entries;
    }

    /**
     * The block a model is shown: the header, then one line per category.
     *
     * @param entries the categories to list
     * @return the block, with no trailing line break
     */
    static String describe(final List<Entry> entries)
    {
        final StringBuilder block = new StringBuilder(HEADER).append("\n\n");
        for (final Entry entry : entries) {
            block.append(entry.path()).append(": ").append(entry.label());
            final String description = WHITESPACE.matcher(entry.description()).replaceAll(" ").strip();
            if (!description.isEmpty()) {
                block.append(" -- ").append(description);
            }
            block.append('\n');
        }
        return block.toString().strip();
    }

    /**
     * The category a model's answer names: by path first, then by label, case aside.
     *
     * @param entries the categories it could choose from
     * @param id what it answered
     * @return the category, or {@code null} when the answer names none of them
     */
    static Entry find(final List<Entry> entries, final String id)
    {
        if (id == null || id.isBlank()) {
            return null;
        }
        final String wanted = id.strip();
        for (final Entry entry : entries) {
            if (entry.path().equals(wanted)) {
                return entry;
            }
        }
        for (final Entry entry : entries) {
            if (entry.label().equalsIgnoreCase(wanted)) {
                return entry;
            }
        }
        return null;
    }
}

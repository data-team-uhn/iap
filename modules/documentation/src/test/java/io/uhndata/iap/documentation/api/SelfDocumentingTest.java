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
import java.util.Map;

import jakarta.json.JsonObject;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the default serializations of {@link SelfDocumenting}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class SelfDocumentingTest
{
    /**
     * A simple item with fixed data.
     */
    private static final class Item implements DocumentedItem
    {
        private final String name;

        private final List<String> categories;

        Item(final String name, final String... categories)
        {
            this.name = name;
            this.categories = List.of(categories);
        }

        @Override
        public String getName()
        {
            return this.name;
        }

        @Override
        public String getDescription()
        {
            return null;
        }

        @Override
        public List<String> getDocumentationCategories()
        {
            return this.categories;
        }
    }

    /**
     * A simple catalogue with fixed data.
     */
    private static final class Catalogue implements SelfDocumenting
    {
        private final String intro;

        private final List<DocumentedItem> items;

        Catalogue(final String intro, final DocumentedItem... items)
        {
            this.intro = intro;
            this.items = List.of(items);
        }

        @Override
        public String getDocumentationTitle()
        {
            return "Catalogue";
        }

        @Override
        public String getDocumentationIntro()
        {
            return this.intro;
        }

        @Override
        public List<? extends DocumentedItem> getDocumentedItems()
        {
            return this.items;
        }
    }

    @Test
    void groupsItemsByCategory()
    {
        final Map<String, List<DocumentedItem>> groups = new Catalogue(null,
            new Item("first", "one"),
            new Item("second", "one", "two"),
            new Item("loose")).getDocumentedItemsByCategory();

        assertEquals(List.of("one", "two", SelfDocumenting.UNCATEGORIZED), List.copyOf(groups.keySet()));
        assertEquals(2, groups.get("one").size());
        // An item belonging to several categories is repeated under each
        assertEquals("second", groups.get("two").get(0).getName());
        assertEquals("loose", groups.get(SelfDocumenting.UNCATEGORIZED).get(0).getName());
    }

    @Test
    void markdownListsItemsFlatWhenNothingIsCategorized()
    {
        assertEquals("# Catalogue\n"
            + "\n"
            + "Everything defined here.\n"
            + "\n"
            + "### first (`first`)\n"
            + "\n"
            + "### second (`second`)\n",
            new Catalogue("Everything defined here.", new Item("first"), new Item("second")).toMarkdown());
    }

    @Test
    void markdownGroupsSectionsByCategory()
    {
        assertEquals("# Catalogue\n"
            + "\n"
            + "## one\n"
            + "\n"
            + "### categorized (`categorized`)\n"
            + "\n"
            + "## uncategorized\n"
            + "\n"
            + "### loose (`loose`)\n",
            new Catalogue(null, new Item("categorized", "one"), new Item("loose")).toMarkdown());
    }

    @Test
    void jsonHoldsItemsPerCategory()
    {
        final JsonObject json = new Catalogue("Everything defined here.",
            new Item("first", "one"), new Item("second", "one")).toDocumentationJson();

        assertEquals("Catalogue", json.getString("title"));
        assertEquals("Everything defined here.", json.getString("description"));
        assertEquals(2, json.getJsonObject("items").getJsonArray("one").size());
        assertEquals("first",
            json.getJsonObject("items").getJsonArray("one").getJsonObject(0).getString("name"));
    }

    @Test
    void jsonLeavesMissingIntroOut()
    {
        final JsonObject json = new Catalogue(null, new Item("first")).toDocumentationJson();
        assertFalse(json.containsKey("description"));
        assertTrue(json.getJsonObject("items").containsKey(SelfDocumenting.UNCATEGORIZED));
    }

    @Test
    void emptyCatalogueStillRenders()
    {
        final Catalogue empty = new Catalogue(null);
        assertEquals("# Catalogue\n", empty.toMarkdown());
        assertTrue(empty.toDocumentationJson().getJsonObject("items").isEmpty());
    }
}

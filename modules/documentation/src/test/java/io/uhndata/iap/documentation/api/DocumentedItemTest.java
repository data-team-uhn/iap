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

import jakarta.json.JsonObject;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the default serializations of {@link DocumentedItem}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class DocumentedItemTest
{
    /**
     * A minimal implementation exposing only what the interface requires.
     */
    private static final class MinimalItem implements DocumentedItem
    {
        @Override
        public String getName()
        {
            return "minimal";
        }

        @Override
        public String getDescription()
        {
            return null;
        }
    }

    /**
     * An implementation overriding every hook the interface offers.
     */
    private static final class FullItem implements DocumentedItem
    {
        @Override
        public String getName()
        {
            return "full";
        }

        @Override
        public String getDocumentationLabel()
        {
            return "The full item";
        }

        @Override
        public String getDescription()
        {
            return "First line\nSecond line";
        }

        @Override
        public List<String> getDocumentationCategories()
        {
            return List.of("examples", "tests");
        }

        @Override
        public List<String> getDocumentationDetails()
        {
            return List.of("**Detailed**: has details", "**Tested**: has tests");
        }
    }

    @Test
    void labelFallsBackToTheName()
    {
        assertEquals("minimal", new MinimalItem().getDocumentationLabel());
    }

    @Test
    void minimalMarkdownIsJustTheHeading()
    {
        assertEquals("### minimal (`minimal`)\n", new MinimalItem().toMarkdown());
    }

    @Test
    void fullMarkdownHasDescriptionAndDetails()
    {
        assertEquals("### The full item (`full`)\n"
            + "\n"
            // Raw line breaks in the description become Markdown hard line breaks
            + "First line  \nSecond line\n"
            + "\n"
            + "- **Detailed**: has details\n"
            + "- **Tested**: has tests\n", new FullItem().toMarkdown());
    }

    @Test
    void minimalJsonLeavesOptionalFieldsOut()
    {
        final JsonObject json = new MinimalItem().toDocumentationJson();
        assertEquals("minimal", json.getString("name"));
        assertEquals("minimal", json.getString("label"));
        assertFalse(json.containsKey("description"));
        assertFalse(json.containsKey("category"));
    }

    @Test
    void fullJsonHasDescriptionAndCategories()
    {
        final JsonObject json = new FullItem().toDocumentationJson();
        assertEquals("full", json.getString("name"));
        assertEquals("The full item", json.getString("label"));
        assertEquals("First line\nSecond line", json.getString("description"));
        assertEquals(2, json.getJsonArray("category").size());
        assertEquals("examples", json.getJsonArray("category").getString(0));
    }

    @Test
    void jsonBuilderIsOpenForMoreFields()
    {
        final JsonObject json = new FullItem().documentationJsonBuilder().add("extra", true).build();
        assertEquals("full", json.getString("name"));
        assertTrue(json.getBoolean("extra"));
    }
}

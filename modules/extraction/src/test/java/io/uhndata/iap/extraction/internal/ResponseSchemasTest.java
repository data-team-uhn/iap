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

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ResponseSchemas}: what actually goes on the wire, rather than what the schema file says.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ResponseSchemasTest
{
    private static final String PROPERTIES = "properties";

    private static final String CATEGORY = "category";

    private static final String ENUM = "enum";

    private static final List<CategoryCatalog.Entry> CATEGORIES = List.of(
        new CategoryCatalog.Entry("/Categories/clinicalTrial", "Clinical trial", "Assigns an intervention."),
        new CategoryCatalog.Entry("/Categories/chartReview", "Chart review", "Reads records already held."));

    private static JsonObject parse(final String schema)
    {
        try (JsonReader reader = Json.createReader(new StringReader(schema))) {
            return reader.readObject();
        }
    }

    private static List<String> strings(final JsonArray values)
    {
        final List<String> read = new ArrayList<>(values.size());
        for (final JsonValue value : values) {
            read.add(value.getValueType() == JsonValue.ValueType.NULL ? null : ((JsonString) value).getString());
        }
        return read;
    }

    @Test
    void closesTheGateCategoryOverTheTreeAsItStands()
    {
        final JsonObject category = parse(ResponseSchemas.gate(CATEGORIES))
            .getJsonObject(PROPERTIES).getJsonObject(CATEGORY);

        assertEquals(Arrays.asList("/Categories/clinicalTrial", "/Categories/chartReview", null),
            strings(category.getJsonArray(ENUM)));
    }

    // Naming no category is a real answer, and the one the gate must be able to give rather than reaching
    // for the likeliest
    @Test
    void leavesNamingNoCategoryOpen()
    {
        final JsonObject category = parse(ResponseSchemas.gate(CATEGORIES))
            .getJsonObject(PROPERTIES).getJsonObject(CATEGORY);

        assertTrue(strings(category.getJsonArray(ENUM)).contains(null));
    }

    // An empty enum is a schema nothing can satisfy, which would turn "no categories configured" into
    // "every answer is invalid"
    @Test
    void leavesTheCategoryAloneWhenTheTreeHoldsNone()
    {
        final JsonObject category = parse(ResponseSchemas.gate(List.of()))
            .getJsonObject(PROPERTIES).getJsonObject(CATEGORY);

        assertFalse(category.containsKey(ENUM));
        assertTrue(category.containsKey("type"), "the shape the file gave it survives");
    }

    @Test
    void closesTheGateChunkTagsOverTheRubrics()
    {
        final JsonObject tag = parse(ResponseSchemas.gate(CATEGORIES)).getJsonObject(PROPERTIES)
            .getJsonObject("chunk_tags").getJsonObject("items").getJsonObject(PROPERTIES).getJsonObject("tag");

        assertEquals(RubricTags.ALL, strings(tag.getJsonArray(ENUM)));
    }

    @Test
    void keepsEverythingElseTheGateSchemaSaid()
    {
        final JsonObject schema = parse(ResponseSchemas.gate(CATEGORIES));

        assertEquals(JsonValue.FALSE, schema.get("additionalProperties"));
        assertTrue(schema.getJsonArray("required").toString().contains("is_proposal"));
        assertEquals("boolean", schema.getJsonObject(PROPERTIES).getJsonObject("is_proposal").getString("type"));
    }

    @Test
    void closesTheSecondLookOverTheSameCategories()
    {
        final JsonObject category = parse(ResponseSchemas.classify(CATEGORIES))
            .getJsonObject(PROPERTIES).getJsonObject(CATEGORY);

        assertEquals(Arrays.asList("/Categories/clinicalTrial", "/Categories/chartReview", null),
            strings(category.getJsonArray(ENUM)));
    }

    @Test
    void leavesTheSecondLookAloneWhenTheTreeHoldsNone()
    {
        final JsonObject category = parse(ResponseSchemas.classify(List.of()))
            .getJsonObject(PROPERTIES).getJsonObject(CATEGORY);

        assertFalse(category.containsKey(ENUM));
    }

    @Test
    void keepsEverythingElseTheSecondLookSchemaSaid()
    {
        final JsonObject schema = parse(ResponseSchemas.classify(CATEGORIES));

        assertEquals(JsonValue.FALSE, schema.get("additionalProperties"));
        assertEquals("number", schema.getJsonObject(PROPERTIES).getJsonObject("confidence").getString("type"));
    }

    @Test
    void offersTheRubricsToASchemaBuiltInJava()
    {
        assertEquals(RubricTags.ALL, strings(ResponseSchemas.rubricEnum().build()));
    }
}

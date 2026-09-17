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
import java.util.List;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;

/**
 * Closes the response schemas over the vocabularies a reply has to answer from: the rubric tags, and the
 * categories that actually exist in the tree.
 *
 * <p>The schema files hold the shape. The lists cannot live in them: categories are content an administrator
 * edits, so the enum is only knowable per call, and duplicating the rubrics into a file beside
 * {@link RubricTags} would give the vocabulary two homes and one of them would drift.
 *
 * <p>This is a saving, not a safeguard. A provider that honours the schema cannot answer off-list, which
 * spares the wasted round trip; a provider that ignores it still can, which is why every reply is read back
 * through the same lists.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ResponseSchemas
{
    private static final String PROPERTIES = "properties";

    private static final String CATEGORY = "category";

    private static final String ENUM = "enum";

    private ResponseSchemas()
    {
        // Utility
    }

    /**
     * The gate's schema, closed over the rubrics and the categories it may pick from.
     *
     * @param categories what it may file a proposal under, empty when the tree holds none
     * @return the schema to put on the wire
     */
    static String gate(final List<CategoryCatalog.Entry> categories)
    {
        final JsonObject schema = read(Prompts.read(Prompts.IS_PROPOSAL_SCHEMA));
        final JsonObjectBuilder properties = rebuild(schema.getJsonObject(PROPERTIES))
            .add(CATEGORY, withCategories(schema.getJsonObject(PROPERTIES).getJsonObject(CATEGORY), categories))
            .add("chunk_tags", withTaggedItems(schema.getJsonObject(PROPERTIES).getJsonObject("chunk_tags")));
        return rebuild(schema).add(PROPERTIES, properties).build().toString();
    }

    /**
     * The second look's schema, closed over the categories it may pick from.
     *
     * @param categories what it may file a proposal under, empty when the tree holds none
     * @return the schema to put on the wire
     */
    static String classify(final List<CategoryCatalog.Entry> categories)
    {
        final JsonObject schema = read(Prompts.read(Prompts.CLASSIFY_SCHEMA));
        final JsonObjectBuilder properties = rebuild(schema.getJsonObject(PROPERTIES))
            .add(CATEGORY, withCategories(schema.getJsonObject(PROPERTIES).getJsonObject(CATEGORY), categories));
        return rebuild(schema).add(PROPERTIES, properties).build().toString();
    }

    /**
     * The rubric tags as a schema enum, for a schema built in Java rather than read from a file.
     *
     * @return the tags, in rubric order
     */
    static JsonArrayBuilder rubricEnum()
    {
        final JsonArrayBuilder tags = Json.createArrayBuilder();
        RubricTags.ALL.forEach(tags::add);
        return tags;
    }

    /**
     * The category property, closed over the paths that exist. Left alone when there are none: an empty enum
     * is a schema nothing can satisfy, which would turn "no categories configured" into "every answer is
     * invalid".
     */
    private static JsonObjectBuilder withCategories(final JsonObject property,
        final List<CategoryCatalog.Entry> categories)
    {
        if (categories.isEmpty()) {
            return rebuild(property);
        }
        final JsonArrayBuilder allowed = Json.createArrayBuilder();
        categories.forEach(entry -> allowed.add(entry.path()));
        // Null stays allowed: naming no category is a real answer, and the one the gate must be able to give
        // rather than reaching for the likeliest.
        allowed.addNull();
        return rebuild(property).add(ENUM, allowed);
    }

    /** The chunk-tag array property, with each item's tag closed over the rubrics. */
    private static JsonObjectBuilder withTaggedItems(final JsonObject property)
    {
        final JsonObject items = property.getJsonObject("items");
        final JsonObjectBuilder tagged = rebuild(items.getJsonObject(PROPERTIES))
            .add("tag", rebuild(items.getJsonObject(PROPERTIES).getJsonObject("tag")).add(ENUM, rubricEnum()));
        return rebuild(property).add("items", rebuild(items).add(PROPERTIES, tagged));
    }

    /** A builder holding everything the object already said, so adding a key replaces only that key. */
    private static JsonObjectBuilder rebuild(final JsonObject object)
    {
        final JsonObjectBuilder builder = Json.createObjectBuilder();
        object.forEach(builder::add);
        return builder;
    }

    private static JsonObject read(final String schema)
    {
        try (JsonReader reader = Json.createReader(new StringReader(schema))) {
            return reader.readObject();
        }
    }
}

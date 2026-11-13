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
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ResponseSchema}: the reply format each question's own type dictates.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ResponseSchemaTest
{
    private static JsonObject parseSchema(final List<ExtractionField> fields)
    {
        return Json.createReader(new StringReader(ResponseSchema.build(fields))).readObject();
    }

    private static JsonObject getValueSchema(final JsonObject schema, final String key)
    {
        return schema.getJsonObject("properties").getJsonObject(key).getJsonObject("properties")
            .getJsonObject(ResponseSchema.VALUE_KEY);
    }

    @Test
    void requiresEveryFieldAndNothingElse()
    {
        final JsonObject schema = parseSchema(List.of(TestFields.createField("common.title", "text"),
            TestFields.createField("common.size", ExtractionField.LONG_TYPE)));

        assertEquals("object", schema.getString("type"));
        assertFalse(schema.getBoolean("additionalProperties"));
        assertEquals(List.of("common.title", "common.size"),
            schema.getJsonArray("required").getValuesAs(value -> ((JsonString) value).getString()));
    }

    // The model writes its reply in the schema's order, so it quotes and reasons before it answers
    @Test
    void asksForTheQuotesBeforeTheValue()
    {
        final String schema = ResponseSchema.build(List.of(TestFields.createField("k", "text")));
        final String field = schema.substring(schema.indexOf("\"k\":"));

        final int evidence = field.indexOf("\"" + ResponseSchema.EVIDENCE_KEY + "\":");
        final int reasoning = field.indexOf("\"" + ResponseSchema.REASONING_KEY + "\":");
        final int value = field.indexOf("\"" + ResponseSchema.VALUE_KEY + "\":");
        final int confidence = field.indexOf("\"" + ResponseSchema.CONFIDENCE_KEY + "\":");
        assertTrue(0 <= evidence && evidence < reasoning && reasoning < value && value < confidence, schema);
    }

    @Test
    void requiresEveryPartOfAnAnswerAndNothingElse()
    {
        final JsonObject field = parseSchema(List.of(TestFields.createField("k", "text")))
            .getJsonObject("properties").getJsonObject("k");

        assertEquals(Json.createArrayBuilder().add(ResponseSchema.EVIDENCE_KEY).add(ResponseSchema.REASONING_KEY)
            .add(ResponseSchema.VALUE_KEY).add(ResponseSchema.CONFIDENCE_KEY).build(), field.getJsonArray("required"));
        assertFalse(field.getBoolean("additionalProperties"));
    }

    @Test
    void pointsEachQuoteAtTheSharedQuoteDefinition()
    {
        final JsonObject field = parseSchema(List.of(TestFields.createField("k", "text")))
            .getJsonObject("properties").getJsonObject("k");

        assertEquals("#/$defs/evidence", field.getJsonObject("properties").getJsonObject(ResponseSchema.EVIDENCE_KEY)
            .getJsonObject("items").getString("$ref"));
    }

    @Test
    void definesAQuoteAsItsWordsAndItsPage()
    {
        final JsonObject evidence = parseSchema(List.of(TestFields.createField("k", "text")))
            .getJsonObject("$defs").getJsonObject(ResponseSchema.EVIDENCE_KEY);

        assertEquals(Json.createArrayBuilder().add(ResponseSchema.QUOTE_KEY).add(ResponseSchema.PAGE_KEY).build(),
            evidence.getJsonArray("required"));
        assertEquals(Json.createArrayBuilder().add("integer").add("null").build(),
            evidence.getJsonObject("properties").getJsonObject(ResponseSchema.PAGE_KEY).getJsonArray("type"));
    }

    @Test
    void typesEachValueAfterItsQuestion()
    {
        final JsonObject schema = parseSchema(List.of(TestFields.createField("t", "text"),
            TestFields.createField("l", ExtractionField.LONG_TYPE),
            TestFields.createField("d", ExtractionField.DOUBLE_TYPE),
            TestFields.createField("b", ExtractionField.BOOLEAN_TYPE),
            TestFields.createField("w", ExtractionField.DATE_TYPE)));

        assertEquals(createNullableType("string"), getValueSchema(schema, "t").getJsonArray("type"));
        assertEquals(createNullableType("integer"), getValueSchema(schema, "l").getJsonArray("type"));
        assertEquals(createNullableType("number"), getValueSchema(schema, "d").getJsonArray("type"));
        assertEquals(createNullableType("boolean"), getValueSchema(schema, "b").getJsonArray("type"));
        // JSON has no date type, and strict output refuses a format; the question block says how to write one
        assertEquals(createNullableType("string"), getValueSchema(schema, "w").getJsonArray("type"));
    }

    @Test
    void limitsAChoiceToItsOptionsOrNull()
    {
        final JsonObject value =
            getValueSchema(parseSchema(List.of(TestFields.createChoiceField("k", TestFields.DRUG_OR_DEVICE))), "k");

        assertEquals(createNullableType("string"), value.getJsonArray("type"));
        assertEquals(Json.createArrayBuilder().add("drug").add("device").addNull().build(),
            value.getJsonArray("enum"));
    }

    @Test
    void answersSeveralOptionsWithAListOfThem()
    {
        final JsonObject value =
            getValueSchema(parseSchema(List.of(TestFields.createMultipleChoiceField("k", TestFields.DRUG_OR_DEVICE))),
                "k");

        assertEquals(createNullableType("array"), value.getJsonArray("type"));
        final JsonObject item = value.getJsonObject("items");
        // An item is never null: an empty answer is a null list, not a list holding null
        assertEquals("string", item.getString("type"));
        assertEquals(Json.createArrayBuilder().add("drug").add("device").build(), item.getJsonArray("enum"));
    }

    @Test
    void answersSeveralFreeValuesWithAListOfTheirType()
    {
        final JsonObject value =
            getValueSchema(parseSchema(List.of(TestFields.createListField("k", ExtractionField.LONG_TYPE))), "k");

        assertEquals(createNullableType("array"), value.getJsonArray("type"));
        assertEquals("integer", value.getJsonObject("items").getString("type"));
    }

    private static JsonArray createNullableType(final String type)
    {
        return Json.createArrayBuilder().add(type).add("null").build();
    }
}

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
import jakarta.json.JsonValue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Unit tests for {@link ResponseSchema}: the reply format each question's own type dictates.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ResponseSchemaTest
{
    private static final List<ExtractionField.Option> DRUG_OR_DEVICE = List.of(
        new ExtractionField.Option("drug", "Drug", null), new ExtractionField.Option("device", "Device", null));

    private static ExtractionField field(final String key, final String dataType, final boolean multiple,
        final List<ExtractionField.Option> options)
    {
        return new ExtractionField(key, "Question?", null, null, "Find it.",
            new ExtractionField.Answer(dataType, multiple, null, null, null, options));
    }

    private static JsonObject parse(final List<ExtractionField> fields)
    {
        return Json.createReader(new StringReader(ResponseSchema.build(fields))).readObject();
    }

    private static JsonObject valueOf(final JsonObject schema, final String key)
    {
        return schema.getJsonObject("properties").getJsonObject(key).getJsonObject("properties")
            .getJsonObject(ResponseSchema.VALUE);
    }

    @Test
    void requiresEveryFieldAndNothingElse()
    {
        final JsonObject schema = parse(List.of(field("common.title", "text", false, List.of()),
            field("common.size", ExtractionField.LONG, false, List.of())));

        assertEquals("object", schema.getString("type"));
        assertFalse(schema.getBoolean("additionalProperties"));
        assertEquals(List.of("common.title", "common.size"),
            schema.getJsonArray("required").getValuesAs(value -> ((JsonString) value).getString()));
    }

    @Test
    void asksForTheQuotesBeforeTheValue()
    {
        final JsonObject field = parse(List.of(field("k", "text", false, List.of())))
            .getJsonObject("properties").getJsonObject("k");

        assertEquals(List.of(ResponseSchema.EVIDENCE, ResponseSchema.REASONING, ResponseSchema.VALUE,
            ResponseSchema.CONFIDENCE), List.copyOf(field.getJsonObject("properties").keySet()));
        assertEquals(Json.createArrayBuilder().add(ResponseSchema.EVIDENCE).add(ResponseSchema.REASONING)
            .add(ResponseSchema.VALUE).add(ResponseSchema.CONFIDENCE).build(), field.getJsonArray("required"));
        assertFalse(field.getBoolean("additionalProperties"));
        assertEquals("#/$defs/evidence", field.getJsonObject("properties").getJsonObject(ResponseSchema.EVIDENCE)
            .getJsonObject("items").getString("$ref"));
    }

    @Test
    void definesAQuoteAsItsWordsAndItsPage()
    {
        final JsonObject evidence = parse(List.of(field("k", "text", false, List.of())))
            .getJsonObject("$defs").getJsonObject(ResponseSchema.EVIDENCE);

        assertEquals(Json.createArrayBuilder().add(ResponseSchema.QUOTE).add(ResponseSchema.PAGE).build(),
            evidence.getJsonArray("required"));
        assertEquals(Json.createArrayBuilder().add("integer").add("null").build(),
            evidence.getJsonObject("properties").getJsonObject(ResponseSchema.PAGE).getJsonArray("type"));
    }

    @Test
    void typesEachValueAfterItsQuestion()
    {
        final JsonObject schema = parse(List.of(field("t", "text", false, List.of()),
            field("l", ExtractionField.LONG, false, List.of()), field("d", ExtractionField.DOUBLE, false, List.of()),
            field("b", ExtractionField.BOOLEAN, false, List.of()), field("w", ExtractionField.DATE, false, List.of())));

        assertEquals(nullable("string"), valueOf(schema, "t").getJsonArray("type"));
        assertEquals(nullable("integer"), valueOf(schema, "l").getJsonArray("type"));
        assertEquals(nullable("number"), valueOf(schema, "d").getJsonArray("type"));
        assertEquals(nullable("boolean"), valueOf(schema, "b").getJsonArray("type"));
        // JSON has no date type, and strict output refuses a format; the question block says how to write one
        assertEquals(nullable("string"), valueOf(schema, "w").getJsonArray("type"));
    }

    @Test
    void limitsAChoiceToItsOptionsOrNull()
    {
        final JsonObject value = valueOf(parse(List.of(field("k", "text", false, DRUG_OR_DEVICE))), "k");

        assertEquals(nullable("string"), value.getJsonArray("type"));
        assertEquals(Json.createArrayBuilder().add("drug").add("device").addNull().build(),
            value.getJsonArray("enum"));
    }

    @Test
    void answersSeveralOptionsWithAListOfThem()
    {
        final JsonObject value = valueOf(parse(List.of(field("k", "text", true, DRUG_OR_DEVICE))), "k");

        assertEquals(nullable("array"), value.getJsonArray("type"));
        final JsonObject item = value.getJsonObject("items");
        // An item is never null: an empty answer is a null list, not a list holding null
        assertEquals("string", item.getString("type"));
        assertEquals(Json.createArrayBuilder().add("drug").add("device").build(), item.getJsonArray("enum"));
    }

    @Test
    void answersSeveralFreeValuesWithAListOfTheirType()
    {
        final JsonObject value = valueOf(parse(List.of(field("k", ExtractionField.LONG, true, List.of()))), "k");

        assertEquals(nullable("array"), value.getJsonArray("type"));
        assertEquals(JsonValue.ValueType.STRING, value.getJsonObject("items").get("type").getValueType());
        assertEquals("integer", value.getJsonObject("items").getString("type"));
    }

    private static JsonArray nullable(final String type)
    {
        return Json.createArrayBuilder().add(type).add("null").build();
    }
}

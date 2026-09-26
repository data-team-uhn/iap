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
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.junit.jupiter.api.Test;

import io.uhndata.iap.schemas.models.OfferedOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link IntakePayload}: what the model is shown, and the shape its answer is held to.
 *
 * @version $Id$
 * @since 0.1.0
 */
class IntakePayloadTest
{
    /** Enough for everything in these tests to be sent whole. */
    private static final String AIMS = "aims";

    private static final String DOCUMENT = "# Aims\n\nTo find out whether it works.\n";

    private static ExtractionField field(final String name)
    {
        return new ExtractionField(name, "What are the " + name + "?", "Judging the science",
            "Find the " + name + ".", null, false);
    }

    private static IntakePayload build(final List<ExtractionField> fields, final String markdown)
    {
        return IntakePayload.build(IntakePayload.buildQuestionBlock(fields), markdown);
    }

    private static JsonObject schemaOf(final List<ExtractionField> fields)
    {
        try (JsonReader reader = Json.createReader(new StringReader(IntakePayload.buildResponseSchema(fields)))) {
            return reader.readObject();
        }
    }

    @Test
    void describesEveryFieldForTheModel()
    {
        final String message = build(
            List.of(new ExtractionField(AIMS, "What are the aims?", "Judging the science",
                "Quote the primary objective.", "{\"type\":\"string\"}", true)),
            DOCUMENT).getUserMessage();

        assertTrue(message.contains("## SCHEMA"), message);
        assertTrue(message.contains("aims:"), message);
        assertTrue(message.contains("question: What are the aims?"), message);
        assertTrue(message.contains("purpose: Judging the science"), message);
        assertTrue(message.contains("rules: Quote the primary objective."), message);
        assertTrue(message.contains("answer shape: {\"type\":\"string\"}"), message);
        assertTrue(message.contains("multiple values: yes"), message);
    }

    // A field that says nothing about its shape or purpose leaves those lines out rather than printing blanks
    @Test
    void leavesOutWhatAFieldDoesNotSay()
    {
        final String message = build(
            List.of(new ExtractionField(AIMS, "What are the aims?", "  ", "Find them.", null, false)),
            DOCUMENT).getUserMessage();

        assertFalse(message.contains("purpose:"), message);
        assertFalse(message.contains("answer shape:"), message);
        assertFalse(message.contains("multiple values:"), message);
    }

    @Test
    void sendsTheDocumentWhole()
    {
        final IntakePayload payload = build(List.of(field(AIMS)), DOCUMENT);

        assertTrue(payload.getUserMessage().contains("## DOCUMENT (untrusted data)"));
        assertTrue(payload.getUserMessage().contains("To find out whether it works."));
        assertTrue(payload.getUserMessage().endsWith(DOCUMENT.strip()));
    }

    // The questions come first so the model reads what it is looking for before the text it looks in
    @Test
    void putsTheQuestionsBeforeTheDocument()
    {
        final String message = build(List.of(field(AIMS)), DOCUMENT).getUserMessage();

        assertTrue(message.indexOf("## SCHEMA") < message.indexOf("## DOCUMENT"), message);
    }

    @Test
    void holdsTheAnswerToOneEntryPerField()
    {
        final JsonObject schema = schemaOf(List.of(field(AIMS), field("funding")));

        assertEquals(List.of(AIMS, "funding"),
            schema.getJsonArray("required").getValuesAs(JsonString.class)
                .stream().map(JsonString::getString).toList());
        assertTrue(schema.getJsonObject("properties").containsKey(AIMS));
        assertEquals("#/$defs/field", schema.getJsonObject("properties").getJsonObject(AIMS).getString("$ref"));
    }

    // A choice field names the values it may take on the reply schema, so a provider that honours the
    // schema cannot answer off-list. Null stays allowed: not finding an answer is a real reply.
    @Test
    void closesAChoiceFieldOverTheValuesItOffers()
    {
        final ExtractionField kind = new ExtractionField("kind", "Which kind?", "The type",
            "Say which.", null, false, List.of(
                new OfferedOption("prom", "PROM", ""),
                new OfferedOption("prem", "PREM", "")));
        final JsonObject value = schemaOf(List.of(kind)).getJsonObject("properties").getJsonObject("kind")
            .getJsonObject("properties").getJsonObject("value");

        assertEquals(List.of("\"prom\"", "\"prem\"", "null"),
            value.getJsonArray("enum").stream().map(JsonValue::toString).toList());
    }

    // Several values are one comma-separated string, which is not itself one of the options. An enum would
    // make the only legal replies a single option, and the parts are matched when the answer is stored.
    @Test
    void leavesAMultiChoiceFieldOpen()
    {
        final ExtractionField languages = new ExtractionField("languages", "Which languages?", "Who it is for",
            "Name them.", null, true, List.of(
                new OfferedOption("english", "English", ""),
                new OfferedOption("french", "French", "")));
        final JsonObject field = schemaOf(List.of(languages)).getJsonObject("properties")
            .getJsonObject("languages");

        assertEquals("#/$defs/field", field.getString("$ref"));
    }

    // Strict structured output refuses a schema whose objects allow extra keys
    @Test
    void closesEveryObjectInTheAnswerSchema()
    {
        final JsonObject schema = schemaOf(List.of(field(AIMS)));
        final JsonObject one = schema.getJsonObject("$defs").getJsonObject("field");

        assertFalse(schema.getBoolean("additionalProperties"));
        assertFalse(one.getBoolean("additionalProperties"));
        assertFalse(one.getJsonObject("properties").getJsonObject("evidence")
            .getJsonObject("items").getBoolean("additionalProperties"));
    }

    @Test
    void asksEachFieldForItsValueConfidenceReasoningAndEvidence()
    {
        final JsonObject one = schemaOf(List.of(field(AIMS))).getJsonObject("$defs").getJsonObject("field");

        assertEquals(List.of("found_answer", "value", "confidence", "reasoning", "evidence"),
            one.getJsonArray("required").getValuesAs(JsonString.class)
                .stream().map(JsonString::getString).toList());
        // Strict structured output rejects minimum and maximum, so confidence stays a plain number
        final JsonObject confidence = one.getJsonObject("properties").getJsonObject("confidence");
        assertEquals("number", confidence.getString("type"));
        assertFalse(confidence.containsKey("minimum"));
        assertFalse(confidence.containsKey("maximum"));
    }

    // A quote carries its page, and a document with no page markers has to be able to say so
    @Test
    void asksEachQuoteForItsPageAndAllowsNone()
    {
        final JsonObject evidence = schemaOf(List.of(field(AIMS)))
            .getJsonObject("$defs").getJsonObject("field")
            .getJsonObject("properties").getJsonObject("evidence").getJsonObject("items");

        assertEquals(List.of("quote", "page"),
            evidence.getJsonArray("required").getValuesAs(JsonString.class)
                .stream().map(JsonString::getString).toList());
        assertEquals(List.of("integer", "null"),
            evidence.getJsonObject("properties").getJsonObject("page")
                .getJsonArray("type").getValuesAs(JsonString.class)
                .stream().map(JsonString::getString).toList());
    }
}

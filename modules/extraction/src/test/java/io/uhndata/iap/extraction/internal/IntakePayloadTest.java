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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.JsonObject;
import jakarta.json.JsonString;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.submissions.models.Chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link IntakePayload}: what the model is shown, which chunks go in full, and the shape its
 * answer is held to.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class IntakePayloadTest
{
    /** Enough for everything in these tests to be sent in full. */
    private static final long PLENTY = 1_000_000L;

    private static final String AIMS = "aims";

    private static final String AIMS_TAG = "B.3";

    private static final String OTHER_TAG = "B.9";

    private static final String AIMS_HEADING = "Aims";

    private static final String BUDGET_HEADING = "Budget";

    private static final String CHUNK_1 = "Chunk-1";

    private static final String CHUNK_2 = "Chunk-2";

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    private int created;

    private static ExtractionField field(final String name, final String... tags)
    {
        return new ExtractionField(name, "What is it?", "Judging it", "Find it.", null, List.of(tags), false);
    }

    /** A chunk placed by a model, so selection can rule it out. */
    private ChunkCatalog.Entry placed(final String text, final String heading, final String... tags)
    {
        this.created++;
        final Map<String, Object> properties = new HashMap<>();
        properties.put("sling:resourceType", Chunk.RESOURCE_TYPE);
        properties.put("tagBasis", "fulltext");
        if (tags.length > 0) {
            properties.put("rubricTags", tags);
        }
        final Resource resource = this.context.create().resource("/chunks/Chunk-" + this.created, properties);
        final Chunk chunk = resource.adaptTo(Chunk.class);
        assertNotNull(chunk);
        return new ChunkCatalog.Entry(chunk, text, heading == null ? List.of() : List.of(heading));
    }

    private static String text(final int tokens)
    {
        return "x".repeat(tokens * ChunkContent.CHARS_PER_TOKEN);
    }

    @Test
    void describesEveryFieldForTheModel()
    {
        final ExtractionField full = new ExtractionField(AIMS, "What are the aims?", "Whether there is one",
            "Find the primary aims.", "{\"type\": \"string\"}", List.of(AIMS_TAG), true);
        final ExtractionField bare = new ExtractionField("title", "Title?", null, "Find the title.", null,
            List.of(), false);

        final String message = IntakePayload.build(List.of(full, bare), List.of(), PLENTY).getUserMessage();

        assertTrue(message.contains("## SCHEMA"));
        assertTrue(message.contains("aims:\nquestion: What are the aims?\npurpose: Whether there is one\n"
            + "rules: Find the primary aims.\nanswer shape: {\"type\": \"string\"}\n"
            + "multiple values: yes, as one comma-separated string\n"));
        assertTrue(message.contains("title:\nquestion: Title?\nrules: Find the title.\n"),
            "what the schema leaves out is left out here too");
    }

    @Test
    void sendsTheWholeDocumentWhenItFits()
    {
        final List<ChunkCatalog.Entry> entries = List.of(
            placed("## Aims\n\nWhy.", AIMS_HEADING, AIMS_TAG),
            placed("## Budget\n\nHow much.", BUDGET_HEADING, OTHER_TAG));

        final IntakePayload payload = IntakePayload.build(List.of(field(AIMS, AIMS_TAG)), entries, PLENTY);

        assertEquals(List.of(CHUNK_1, CHUNK_2), payload.getSentChunkIds(),
            "a chunk placed elsewhere still goes when there is room");
        assertTrue(payload.getUserMessage().contains("[chunk:Chunk-1]\n## Aims\n\nWhy."));
        assertTrue(payload.getUserMessage().contains("[chunk:Chunk-2]\n## Budget"));
        assertTrue(payload.getUserMessage().contains("Chunk-1: Aims\nChunk-2: Budget"));
        assertEquals("## Aims\n\nWhy.", payload.getSentTexts().get(CHUNK_1));
    }

    @Test
    void whenItDoesNotFitOnlyTheChunksThatCouldAnswerAreSent()
    {
        final List<ChunkCatalog.Entry> entries = List.of(
            placed(text(40), AIMS_HEADING, AIMS_TAG),
            placed(text(40), BUDGET_HEADING, OTHER_TAG),
            placed(text(40), "Consent", AIMS_TAG));

        final IntakePayload payload = IntakePayload.build(List.of(field(AIMS, AIMS_TAG)), entries, 100);

        assertEquals(List.of(CHUNK_1, "Chunk-3"), payload.getSentChunkIds());
    }

    @Test
    void anUnsentChunkIsListedWithASnippet()
    {
        final List<ChunkCatalog.Entry> entries = List.of(
            placed(text(40), AIMS_HEADING, AIMS_TAG),
            placed("The budget is   large,\nvery large.", BUDGET_HEADING, OTHER_TAG));

        // 40 + 8 tokens would fit 50 and all be sent; 45 leaves the budget chunk out
        final String message = IntakePayload.build(List.of(field(AIMS, AIMS_TAG)), entries, 45).getUserMessage();

        assertTrue(message.contains("Chunk-2: Budget  - The budget is large, very large."),
            "one line, whitespace collapsed");
        assertFalse(message.contains("[chunk:Chunk-2]"));
    }

    @Test
    void aLongSnippetIsCutShort()
    {
        final String opening = "y".repeat(IntakePayload.SNIPPET_CHARS + 20);
        final List<ChunkCatalog.Entry> entries =
            List.of(placed(text(40), AIMS_HEADING, AIMS_TAG), placed(opening, BUDGET_HEADING, OTHER_TAG));

        final String message = IntakePayload.build(List.of(field(AIMS, AIMS_TAG)), entries, 50).getUserMessage();

        assertTrue(message.contains("y".repeat(IntakePayload.SNIPPET_CHARS) + "..."));
        assertFalse(message.contains(opening));
    }

    @Test
    void anUnsentChunkWithNoTextGetsNoSnippet()
    {
        final List<ChunkCatalog.Entry> entries = List.of(
            placed(text(40), AIMS_HEADING, AIMS_TAG),
            placed(null, "Blank", OTHER_TAG),
            placed("   ", "Spaces", OTHER_TAG));

        // below the 40 tokens of the first chunk, so the two empty ones stay unsent
        final String message = IntakePayload.build(List.of(field(AIMS, AIMS_TAG)), entries, 30).getUserMessage();

        assertTrue(message.contains("Chunk-2: Blank\nChunk-3: Spaces\n"));
    }

    @Test
    void stopsBeforeAChunkThatWouldOverrunTheBudget()
    {
        final List<ChunkCatalog.Entry> entries = List.of(
            placed(text(60), AIMS_HEADING, AIMS_TAG),
            placed(text(60), "More aims", AIMS_TAG),
            placed(text(60), BUDGET_HEADING, OTHER_TAG));

        assertEquals(List.of(CHUNK_1),
            IntakePayload.build(List.of(field(AIMS, AIMS_TAG)), entries, 100).getSentChunkIds());
    }

    @Test
    void stopsOnceTheBudgetIsExactlySpent()
    {
        final List<ChunkCatalog.Entry> entries = List.of(
            placed(text(50), AIMS_HEADING, AIMS_TAG),
            placed(text(50), "More aims", AIMS_TAG),
            placed(text(50), BUDGET_HEADING, OTHER_TAG));

        assertEquals(List.of(CHUNK_1),
            IntakePayload.build(List.of(field(AIMS, AIMS_TAG)), entries, 50).getSentChunkIds());
    }

    @Test
    void alwaysSendsAtLeastTheFirstChunkThatCouldAnswer()
    {
        final List<ChunkCatalog.Entry> entries =
            List.of(placed(text(500), AIMS_HEADING, AIMS_TAG), placed(text(500), BUDGET_HEADING, OTHER_TAG));

        assertEquals(List.of(CHUNK_1),
            IntakePayload.build(List.of(field(AIMS, AIMS_TAG)), entries, 10).getSentChunkIds());
    }

    @Test
    void dropsAReferenceListNearTheEnd()
    {
        final List<ChunkCatalog.Entry> entries = List.of(
            placed("## Aims", AIMS_HEADING, AIMS_TAG),
            placed("## Methods", "Methods", "B.5"),
            placed("## References\n\n1. Smith", "References", "B.17"));

        final IntakePayload payload = IntakePayload.build(List.of(field(AIMS)), entries, PLENTY);

        assertEquals(List.of(CHUNK_1, CHUNK_2), payload.getSentChunkIds());
        assertFalse(payload.getUserMessage().contains("[chunk:Chunk-3]"));
    }

    @Test
    void keepsAReferenceHeadingThatComesEarly()
    {
        final List<ChunkCatalog.Entry> entries = List.of(
            placed("## References to earlier work", "References to earlier work", "B.2"),
            placed("## Aims", AIMS_HEADING, AIMS_TAG),
            placed("## Methods", "Methods", "B.5"));

        assertEquals(3, IntakePayload.build(List.of(field(AIMS)), entries, PLENTY).getSentChunkIds().size());
    }

    @Test
    void sendsAWholeUnchunkedDocumentUnderOneId()
    {
        final IntakePayload payload =
            IntakePayload.buildForWholeDocument(List.of(field(AIMS)), "# Proposal\n\nAll of it.\n", PLENTY);

        assertEquals(List.of(IntakePayload.WHOLE_DOCUMENT_ID), payload.getSentChunkIds());
        assertTrue(payload.getUserMessage().contains("[chunk:document]\n# Proposal\n\nAll of it."));
        assertFalse(payload.getUserMessage().contains("## CATALOG"), "there are no chunks to list");
        assertEquals("# Proposal\n\nAll of it.\n", payload.getSentTexts().get("document"));
    }

    @Test
    void cutsAWholeDocumentToTheBudget()
    {
        final IntakePayload payload = IntakePayload.buildForWholeDocument(List.of(field(AIMS)), text(100), 10);

        assertEquals(10 * ChunkContent.CHARS_PER_TOKEN, payload.getSentTexts().get("document").length());
    }

    @Test
    void holdsTheAnswerToOneEntryPerFieldPlusTheTags()
    {
        final JsonObject schema = ModelReplies.readJsonObject(
            IntakePayload.buildResponseSchema(List.of(field(AIMS), field("title"))));
        assertNotNull(schema);

        assertEquals(List.of(AIMS, "title", "chunk_tags"), strings(schema, "required"));
        assertFalse(schema.getBoolean("additionalProperties"));
        assertEquals("#/$defs/field", schema.getJsonObject("properties").getJsonObject(AIMS).getString("$ref"));

        final JsonObject field = schema.getJsonObject("$defs").getJsonObject("field");
        assertEquals(List.of("found_answer", "confidence", "value", "reasoning", "evidence"),
            strings(field, "required"));
        assertFalse(field.getBoolean("additionalProperties"));
        final JsonObject passage = field.getJsonObject("properties").getJsonObject("evidence").getJsonObject("items");
        assertEquals(List.of("quote", "chunk_id", "page"), strings(passage, "required"));

        final JsonObject tag = schema.getJsonObject("properties").getJsonObject("chunk_tags").getJsonObject("items");
        assertEquals(List.of("chunk_id", "tags", "confidence"), strings(tag, "required"));
    }

    private static List<String> strings(final JsonObject object, final String key)
    {
        final List<String> values = new ArrayList<>();
        object.getJsonArray(key).forEach(value -> values.add(((JsonString) value).getString()));
        return values;
    }
}

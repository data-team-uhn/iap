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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;

/**
 * What the intake call sends: the message describing the fields and the document, and the schema the answer
 * must fit.
 *
 * <p>Which chunks go in full is decided here. When the whole document fits the budget it is all sent. When it
 * does not, the chunks that could hold an answer are packed in document order until the budget is spent, and
 * the rest are listed in the catalog with a snippet, so the model knows they exist without reading them.
 * Reference lists near the end of a document are dropped either way: they hold nothing to extract.
 *
 * <p>The response schema is built per call because the fields come from the schema version, not from code.
 * Every object in it forbids extra properties and requires all of its own, which is what a provider's strict
 * structured-output mode asks for.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class IntakePayload
{
    /** The id the whole document is sent under when it was never split into chunks. */
    static final String WHOLE_DOCUMENT_ID = "document";

    /** How much of an unsent chunk's opening the catalog shows. */
    static final int SNIPPET_CHARS = 150;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final String BREAK = "\n\n";

    private static final String SCHEMA_HEADER = "## SCHEMA";

    private static final String CATALOG_HEADER = "## CATALOG (chunk id: heading) (untrusted data)";

    private static final String CHUNK_HEADER = "## CHUNK (untrusted data)";

    private static final String TYPE = "type";

    private static final String OBJECT = "object";

    private static final String STRING = "string";

    private static final String REQUIRED = "required";

    private static final String PROPERTIES = "properties";

    private static final String NO_EXTRAS = "additionalProperties";

    private final String userMessage;

    private final Map<String, String> sentTexts;

    private IntakePayload(final String userMessage, final Map<String, String> sentTexts)
    {
        this.userMessage = userMessage;
        this.sentTexts = sentTexts;
    }

    /**
     * Build the message for a chunked document.
     *
     * @param fields what to extract
     * @param entries the document's chunks, read
     * @param tokenBudget how much text may be sent in full
     * @return the payload
     */
    static IntakePayload build(final List<ExtractionField> fields, final List<ChunkCatalog.Entry> entries,
        final long tokenBudget)
    {
        return buildForChunks(fields, entries,
            FullTextSelection.select(entries, ChunkSelection.wantedTags(fields), tokenBudget));
    }

    /**
     * Build the message over a chunk set somebody else chose. Step 2 uses this: its planner has already
     * worked out which chunks this batch of fields has not seen.
     *
     * @param fields what to extract
     * @param entries the document's chunks, read
     * @param sent which of them to send in full
     * @return the payload
     */
    static IntakePayload buildForChunks(final List<ExtractionField> fields,
        final List<ChunkCatalog.Entry> entries, final Set<String> sent)
    {
        final Map<String, String> texts = new LinkedHashMap<>();
        final StringBuilder chunkBlock = new StringBuilder();
        final StringBuilder catalog = new StringBuilder();
        for (final ChunkCatalog.Entry entry : entries) {
            catalog.append(entry.getName()).append(": ").append(entry.describeHeadings());
            if (sent.contains(entry.getName())) {
                texts.put(entry.getName(), entry.text());
                chunkBlock.append("[chunk:").append(entry.getName()).append("]\n")
                    .append(entry.text().strip()).append(BREAK);
            } else {
                final String snippet = snippet(entry.text());
                if (!snippet.isEmpty()) {
                    catalog.append("  - ").append(snippet);
                }
            }
            catalog.append('\n');
        }
        final String message = SCHEMA_HEADER + BREAK + describeFields(fields) + BREAK
            + CATALOG_HEADER + BREAK + catalog.toString().strip() + BREAK
            + CHUNK_HEADER + BREAK + chunkBlock.toString().strip();
        return new IntakePayload(message, texts);
    }

    /**
     * Build the message for a document that was never split into chunks, sent as one piece.
     *
     * @param fields what to extract
     * @param markdown the whole document
     * @param tokenBudget how much of it may be sent
     * @return the payload
     */
    static IntakePayload buildForWholeDocument(final List<ExtractionField> fields, final String markdown,
        final long tokenBudget)
    {
        final int limit = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, tokenBudget) * ChunkContent.CHARS_PER_TOKEN);
        final String text = markdown.length() <= limit ? markdown : markdown.substring(0, limit);
        final Map<String, String> texts = new LinkedHashMap<>();
        texts.put(WHOLE_DOCUMENT_ID, text);
        final String message = SCHEMA_HEADER + BREAK + describeFields(fields) + BREAK
            + CHUNK_HEADER + BREAK + "[chunk:" + WHOLE_DOCUMENT_ID + "]\n" + text.strip();
        return new IntakePayload(message, texts);
    }

    /**
     * The user message.
     *
     * @return what the model is shown
     */
    String getUserMessage()
    {
        return this.userMessage;
    }

    /**
     * The chunks sent in full, by id, with their text - what a quote has to be found in to count.
     *
     * @return the texts, in document order
     */
    Map<String, String> getSentTexts()
    {
        return this.sentTexts;
    }

    /**
     * The ids of the chunks sent in full, in document order.
     *
     * @return the ids
     */
    List<String> getSentChunkIds()
    {
        return new ArrayList<>(this.sentTexts.keySet());
    }

    /**
     * The schema the answer must fit: one entry per field, then the chunk tags.
     *
     * @param fields what to extract
     * @return the schema as JSON
     */
    static String buildResponseSchema(final List<ExtractionField> fields)
    {
        final JsonObjectBuilder properties = Json.createObjectBuilder();
        final JsonArrayBuilder required = Json.createArrayBuilder();
        for (final ExtractionField field : fields) {
            properties.add(field.name(), Json.createObjectBuilder().add("$ref", "#/$defs/field"));
            required.add(field.name());
        }
        properties.add("chunk_tags", chunkTagsSchema());
        required.add("chunk_tags");
        return Json.createObjectBuilder()
            .add(TYPE, OBJECT)
            .add(NO_EXTRAS, false)
            .add(REQUIRED, required)
            .add(PROPERTIES, properties)
            .add("$defs", Json.createObjectBuilder().add("field", fieldSchema()))
            .build().toString();
    }

    /**
     * One entry per field: its key, the question, what the answer is for, the rules, and where the schema
     * gives them, the shape the answer must take and whether it may hold several values.
     */
    private static String describeFields(final List<ExtractionField> fields)
    {
        final StringBuilder block = new StringBuilder();
        for (final ExtractionField field : fields) {
            block.append(field.name()).append(":\n");
            appendLine(block, "question", field.text());
            appendLine(block, "purpose", field.purpose());
            appendLine(block, "rules", field.prompt());
            appendLine(block, "answer shape", field.responseShape());
            if (field.multiple()) {
                block.append("multiple values: yes, as one comma-separated string\n");
            }
            block.append('\n');
        }
        return block.toString().strip();
    }

    private static void appendLine(final StringBuilder block, final String label, final String value)
    {
        if (value != null && !value.isBlank()) {
            block.append(label).append(": ").append(value.strip()).append('\n');
        }
    }

    private static String snippet(final String text)
    {
        if (text == null || text.isBlank()) {
            return "";
        }
        final String collapsed = WHITESPACE.matcher(text.strip()).replaceAll(" ");
        return collapsed.length() <= SNIPPET_CHARS ? collapsed : collapsed.substring(0, SNIPPET_CHARS) + "...";
    }

    /** What the model says about one field: found or not, how sure, the value, why, and the quotes. */
    private static JsonObjectBuilder fieldSchema()
    {
        final JsonObjectBuilder passage = Json.createObjectBuilder()
            .add(TYPE, OBJECT)
            .add(NO_EXTRAS, false)
            .add(REQUIRED, Json.createArrayBuilder().add("quote").add("chunk_id").add("page"))
            .add(PROPERTIES, Json.createObjectBuilder()
                .add("quote", Json.createObjectBuilder().add(TYPE, STRING))
                .add("chunk_id", Json.createObjectBuilder().add(TYPE, STRING))
                .add("page", Json.createObjectBuilder()
                    .add(TYPE, Json.createArrayBuilder().add("integer").add("null"))));
        return Json.createObjectBuilder()
            .add(TYPE, OBJECT)
            .add(NO_EXTRAS, false)
            .add(REQUIRED, Json.createArrayBuilder()
                .add("found_answer").add("confidence").add("value").add("reasoning").add("evidence"))
            .add(PROPERTIES, Json.createObjectBuilder()
                .add("found_answer", Json.createObjectBuilder().add(TYPE, "boolean"))
                .add("confidence", Json.createObjectBuilder().add(TYPE, "number"))
                .add("value", Json.createObjectBuilder()
                    .add(TYPE, Json.createArrayBuilder().add(STRING).add("null")))
                .add("reasoning", Json.createObjectBuilder().add(TYPE, STRING))
                .add("evidence", Json.createObjectBuilder().add(TYPE, "array").add("items", passage)));
    }

    /** One tag per chunk the model read. */
    private static JsonObjectBuilder chunkTagsSchema()
    {
        final JsonObjectBuilder item = Json.createObjectBuilder()
            .add(TYPE, OBJECT)
            .add(NO_EXTRAS, false)
            .add(REQUIRED, Json.createArrayBuilder().add("chunk_id").add("tags").add("confidence"))
            .add(PROPERTIES, Json.createObjectBuilder()
                .add("chunk_id", Json.createObjectBuilder().add(TYPE, STRING))
                // Closed over the rubrics, so a provider that honours the schema cannot answer off-list.
                // The reply is still read back through RubricTags, because one that ignores it can.
                .add("tags", Json.createObjectBuilder().add(TYPE, "array")
                    .add("items", Json.createObjectBuilder().add(TYPE, STRING)
                        .add("enum", ResponseSchemas.rubricEnum())))
                .add("confidence", Json.createObjectBuilder().add(TYPE, "number")));
        return Json.createObjectBuilder().add(TYPE, "array").add("items", item);
    }
}

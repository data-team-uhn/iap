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

import java.util.List;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

/**
 * Builds what one intake call sends: the user message with the questions and then the document, and the JSON
 * schema the reply must follow.
 *
 * <p>The document goes last, so the model knows what it is looking for before it reads the text.
 *
 * <p>The reply schema is built for each call, because the fields come from the schema version, not from code.
 * Every object in it forbids extra properties and requires all of its own, as a provider's strict
 * structured-output mode needs.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class IntakePayload
{
    private static final String PARAGRAPH_BREAK = "\n\n";

    private static final String SCHEMA_HEADER = "## SCHEMA";

    private static final String DOCUMENT_HEADER = "## DOCUMENT (untrusted data)";

    private static final String TYPE_KEY = "type";

    private static final String OBJECT_TYPE = "object";

    private static final String STRING_TYPE = "string";

    private static final String REQUIRED_KEY = "required";

    private static final String PROPERTIES_KEY = "properties";

    private static final String NO_EXTRAS = "additionalProperties";

    private final String userMessage;

    private IntakePayload(final String userMessage)
    {
        this.userMessage = userMessage;
    }

    /**
     * The user message without the document: the questions and the headers. It is built first so the caller can
     * count its tokens, then work out how much of the document still fits.
     *
     * @param fields the fields to extract
     * @return the start of the user message, up to and including the document's header
     */
    static String buildQuestionBlock(final List<ExtractionField> fields)
    {
        return SCHEMA_HEADER + PARAGRAPH_BREAK + describeFields(fields) + PARAGRAPH_BREAK + DOCUMENT_HEADER
            + PARAGRAPH_BREAK;
    }

    /**
     * Builds the user message: the questions, then the document.
     *
     * @param questionBlock the start of the message, from {@link #buildQuestionBlock}
     * @param markdown the part of the document's Markdown that fits in the call, see
     *            {@link DocumentBudget#fitDocument}
     * @return the payload
     */
    static IntakePayload build(final String questionBlock, final String markdown)
    {
        return new IntakePayload(questionBlock + markdown.strip());
    }

    String getUserMessage()
    {
        return this.userMessage;
    }

    static String buildResponseSchema(final List<ExtractionField> fields)
    {
        final JsonObjectBuilder properties = Json.createObjectBuilder();
        final JsonArrayBuilder required = Json.createArrayBuilder();
        for (final ExtractionField field : fields) {
            // A multi-select answer is one comma-separated string, which is not one of the allowed values,
            // so a list of allowed values would let the model pick only one option
            properties.add(field.name(), isClosedList(field)
                ? buildFieldSchema(field.getAllowedValues())
                : Json.createObjectBuilder().add("$ref", "#/$defs/field").build());
            required.add(field.name());
        }
        return Json.createObjectBuilder()
            .add(TYPE_KEY, OBJECT_TYPE)
            .add(NO_EXTRAS, false)
            .add(REQUIRED_KEY, required)
            .add(PROPERTIES_KEY, properties)
            .add("$defs", Json.createObjectBuilder().add("field", buildFieldSchema(List.of())))
            .build().toString();
    }

    /**
     * The SCHEMA block: for each field, its question, purpose, rules and answer shape, as the model reads them.
     *
     * @param fields the fields to extract
     * @return the block, without its header
     */
    static String describeFields(final List<ExtractionField> fields)
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

    /**
     * Whether the reply schema can list a field's allowed values. Only a single-choice field can: several
     * values come as one string, and each part is matched to an option when the answer is stored.
     *
     * @param field the field
     * @return {@code true} for a single-choice field with allowed values
     */
    private static boolean isClosedList(final ExtractionField field)
    {
        return !field.multiple() && !field.getAllowedValues().isEmpty();
    }

    /**
     * The schema for one field's answer: whether it was found, the value, how sure the model is, why, and the
     * quotes. With allowed values, the model can only answer one of them, or {@code null}, since not finding an
     * answer is a valid reply.
     *
     * <p>Confidence is a plain number. Strict structured output rejects {@code minimum} and {@code maximum},
     * and the intake prompt already asks for a value from 0 to 1.</p>
     *
     * @param allowed the values the answer may take, empty for free text
     * @return the schema for one field
     */
    private static JsonObject buildFieldSchema(final List<String> allowed)
    {
        final JsonObjectBuilder value = Json.createObjectBuilder()
            .add(TYPE_KEY, Json.createArrayBuilder().add(STRING_TYPE).add("null"));
        if (!allowed.isEmpty()) {
            final JsonArrayBuilder values = Json.createArrayBuilder();
            allowed.forEach(values::add);
            values.addNull();
            value.add("enum", values);
        }
        return Json.createObjectBuilder()
            .add(TYPE_KEY, OBJECT_TYPE)
            .add(NO_EXTRAS, false)
            .add(REQUIRED_KEY, Json.createArrayBuilder()
                .add("found_answer").add("value").add("confidence").add("reasoning").add("evidence"))
            .add(PROPERTIES_KEY, Json.createObjectBuilder()
                .add("found_answer", Json.createObjectBuilder().add(TYPE_KEY, "boolean"))
                .add("value", value)
                .add("confidence", Json.createObjectBuilder().add(TYPE_KEY, "number"))
                .add("reasoning", Json.createObjectBuilder().add(TYPE_KEY, STRING_TYPE))
                .add("evidence", Json.createObjectBuilder()
                    .add(TYPE_KEY, "array")
                    .add("items", buildEvidenceSchema())))
            .build();
    }

    /**
     * The schema for one quote: its words, and the page it is on, or {@code null} when the page is not known.
     *
     * @return the schema for one quote
     */
    private static JsonObject buildEvidenceSchema()
    {
        return Json.createObjectBuilder()
            .add(TYPE_KEY, OBJECT_TYPE)
            .add(NO_EXTRAS, false)
            .add(REQUIRED_KEY, Json.createArrayBuilder().add("quote").add("page"))
            .add(PROPERTIES_KEY, Json.createObjectBuilder()
                .add("quote", Json.createObjectBuilder().add(TYPE_KEY, STRING_TYPE))
                .add("page", Json.createObjectBuilder()
                    .add(TYPE_KEY, Json.createArrayBuilder().add("integer").add("null"))))
            .build();
    }
}

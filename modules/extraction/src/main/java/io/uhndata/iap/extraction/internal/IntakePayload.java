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
 * What one intake call sends: the questions, then the document.
 *
 * <p>The document goes last, after the questions, so the model reads what it is looking for before it reads
 * the text to look in.
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
    private static final String BREAK = "\n\n";

    private static final String SCHEMA_HEADER = "## SCHEMA";

    private static final String DOCUMENT_HEADER = "## DOCUMENT (untrusted data)";

    private static final String TYPE = "type";

    private static final String OBJECT = "object";

    private static final String STRING = "string";

    private static final String REQUIRED = "required";

    private static final String PROPERTIES = "properties";

    private static final String NO_EXTRAS = "additionalProperties";

    private final String userMessage;

    private IntakePayload(final String userMessage)
    {
        this.userMessage = userMessage;
    }

    /**
     * Everything the user message carries besides the document: the questions and the headers. Built on its
     * own so the caller can count what it costs before working out how much document is left to send.
     *
     * @param fields what to extract
     * @return the opening of the user message, up to and including the document's header
     */
    static String buildQuestionBlock(final List<ExtractionField> fields)
    {
        return SCHEMA_HEADER + BREAK + describeFields(fields) + BREAK + DOCUMENT_HEADER + BREAK;
    }

    /**
     * Build the message for a document.
     *
     * @param questionBlock the opening of the message, from {@link #buildQuestionBlock}
     * @param markdown as much of the document's Markdown as the call may carry, see
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
            // A multi-select answer is one comma-separated string. That string is not itself one of the
            // allowed values, so a strict enum would leave the model able to name only a single option.
            properties.add(field.name(), closesOver(field)
                ? fieldSchema(field.allowedValues())
                : Json.createObjectBuilder().add("$ref", "#/$defs/field").build());
            required.add(field.name());
        }
        return Json.createObjectBuilder()
            .add(TYPE, OBJECT)
            .add(NO_EXTRAS, false)
            .add(REQUIRED, required)
            .add(PROPERTIES, properties)
            .add("$defs", Json.createObjectBuilder().add("field", fieldSchema(List.of())))
            .build().toString();
    }

    /**
     * The SCHEMA block: what each field is, in the model's terms.
     *
     * @param fields what to extract
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
     * Whether a field's value is a closed list. Only a single choice is: several values travel as one string,
     * and each part is matched to an option when the answer is stored.
     *
     * @param field the field
     * @return {@code true} when the reply schema may name the values it accepts
     */
    private static boolean closesOver(final ExtractionField field)
    {
        return !field.multiple() && !field.allowedValues().isEmpty();
    }

    /**
     * One field's answer: whether it was found, the value, how sure the model is, why, and the quotes it rests
     * on. A single-choice field names the values it may take, so a provider that honours the schema cannot
     * answer off-list; {@code null} stays allowed, because not finding an answer is a real reply.
     *
     * <p>Confidence is a plain number. Strict structured output rejects {@code minimum} and {@code maximum},
     * and the intake prompt already bounds it to 0–1.</p>
     *
     * @param allowed the values the answer may take, empty when it is free text
     * @return the schema for one field
     */
    private static JsonObject fieldSchema(final List<String> allowed)
    {
        final JsonObjectBuilder value = Json.createObjectBuilder()
            .add(TYPE, Json.createArrayBuilder().add(STRING).add("null"));
        if (!allowed.isEmpty()) {
            final JsonArrayBuilder values = Json.createArrayBuilder();
            allowed.forEach(values::add);
            values.addNull();
            value.add("enum", values);
        }
        return Json.createObjectBuilder()
            .add(TYPE, OBJECT)
            .add(NO_EXTRAS, false)
            .add(REQUIRED, Json.createArrayBuilder()
                .add("found_answer").add("value").add("confidence").add("reasoning").add("evidence"))
            .add(PROPERTIES, Json.createObjectBuilder()
                .add("found_answer", Json.createObjectBuilder().add(TYPE, "boolean"))
                .add("value", value)
                .add("confidence", Json.createObjectBuilder().add(TYPE, "number"))
                .add("reasoning", Json.createObjectBuilder().add(TYPE, STRING))
                .add("evidence", Json.createObjectBuilder()
                    .add(TYPE, "array")
                    .add("items", evidenceSchema())))
            .build();
    }

    /**
     * One quote: the words themselves and, when the document says so, the page they are on.
     *
     * @return the schema for one piece of evidence
     */
    private static JsonObject evidenceSchema()
    {
        return Json.createObjectBuilder()
            .add(TYPE, OBJECT)
            .add(NO_EXTRAS, false)
            .add(REQUIRED, Json.createArrayBuilder().add("quote").add("page"))
            .add(PROPERTIES, Json.createObjectBuilder()
                .add("quote", Json.createObjectBuilder().add(TYPE, STRING))
                .add("page", Json.createObjectBuilder()
                    .add(TYPE, Json.createArrayBuilder().add("integer").add("null"))))
            .build();
    }
}

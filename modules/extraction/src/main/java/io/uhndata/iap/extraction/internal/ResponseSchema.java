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

import org.jetbrains.annotations.NotNull;

/**
 * The strict JSON schema a reply must follow. Each field gets the quotes it rests on, the reasoning, the value and a
 * confidence, in that order.
 *
 * <p>A provider generates the properties in the order the schema lists them, so the model writes its quotes before
 * it commits to a value. The value's type comes from the question. A number is a number, several answers are an
 * array, and a question with options takes only one of their values. {@code null} is always allowed, since not
 * finding an answer is a real reply.</p>
 *
 * <p>Bounds and patterns stay out of the schema, because strict structured output rejects {@code minimum},
 * {@code maximum} and {@code pattern}. The question block says them in words instead.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ResponseSchema
{
    /** The name the schema is sent under. */
    static final String NAME = "iap_extraction";

    /** The entry holding the quotes an answer rests on. */
    static final String EVIDENCE = "evidence";

    /** The entry explaining the answer. */
    static final String REASONING = "reasoning";

    /** The entry holding the answer itself. */
    static final String VALUE = "value";

    /** The entry saying how sure the model is. */
    static final String CONFIDENCE = "confidence";

    /** One quote's words. */
    static final String QUOTE = "quote";

    /** The page one quote is on. */
    static final String PAGE = "page";

    private static final String TYPE = "type";

    private static final String NULL = "null";

    private static final String STRING = "string";

    private static final String OBJECT = "object";

    private static final String ARRAY = "array";

    private static final String REQUIRED = "required";

    private static final String PROPERTIES = "properties";

    private static final String NO_EXTRAS = "additionalProperties";

    private static final String EVIDENCE_REFERENCE = "#/$defs/evidence";

    private ResponseSchema()
    {
        // Utility class, not meant to be instantiated
    }

    /**
     * The schema for one call.
     *
     * @param fields what the call asks, in the order it asks them
     * @return the schema, serialized
     */
    @NotNull
    static String build(@NotNull final List<ExtractionField> fields)
    {
        final JsonObjectBuilder properties = Json.createObjectBuilder();
        final JsonArrayBuilder required = Json.createArrayBuilder();
        for (final ExtractionField field : fields) {
            properties.add(field.key(), buildFieldSchema(field));
            required.add(field.key());
        }
        return Json.createObjectBuilder()
            .add(TYPE, OBJECT)
            .add(NO_EXTRAS, false)
            .add(REQUIRED, required)
            .add(PROPERTIES, properties)
            .add("$defs", Json.createObjectBuilder().add(EVIDENCE, buildEvidenceSchema()))
            .build().toString();
    }

    private static JsonObject buildFieldSchema(final ExtractionField field)
    {
        return Json.createObjectBuilder()
            .add(TYPE, OBJECT)
            .add(NO_EXTRAS, false)
            .add(REQUIRED, Json.createArrayBuilder().add(EVIDENCE).add(REASONING).add(VALUE).add(CONFIDENCE))
            .add(PROPERTIES, Json.createObjectBuilder()
                .add(EVIDENCE, Json.createObjectBuilder().add(TYPE, ARRAY)
                    .add("items", Json.createObjectBuilder().add("$ref", EVIDENCE_REFERENCE)))
                .add(REASONING, Json.createObjectBuilder().add(TYPE, STRING))
                .add(VALUE, buildValueSchema(field))
                .add(CONFIDENCE, Json.createObjectBuilder().add(TYPE, "number")))
            .build();
    }

    private static JsonObject buildValueSchema(final ExtractionField field)
    {
        if (field.answer().multiple()) {
            return Json.createObjectBuilder()
                .add(TYPE, Json.createArrayBuilder().add(ARRAY).add(NULL))
                .add("items", buildItemSchema(field, false))
                .build();
        }
        return buildItemSchema(field, true);
    }

    /**
     * One answer's schema.
     *
     * @param field the field answered
     * @param nullable whether the answer itself may be {@code null}, which an item of a list may not
     * @return the schema
     */
    private static JsonObject buildItemSchema(final ExtractionField field, final boolean nullable)
    {
        final String type = field.answer().options().isEmpty() ? jsonType(field.answer().dataType()) : STRING;
        final JsonObjectBuilder schema = Json.createObjectBuilder()
            .add(TYPE, nullable ? Json.createArrayBuilder().add(type).add(NULL).build() : Json.createValue(type));
        if (!field.answer().options().isEmpty()) {
            final JsonArrayBuilder values = Json.createArrayBuilder();
            field.answer().options().forEach(option -> values.add(option.value()));
            if (nullable) {
                values.addNull();
            }
            schema.add("enum", values);
        }
        return schema.build();
    }

    private static String jsonType(final String dataType)
    {
        return switch (dataType) {
            case ExtractionField.LONG -> "integer";
            case ExtractionField.DOUBLE -> "number";
            case ExtractionField.BOOLEAN -> "boolean";
            default -> STRING;
        };
    }

    private static JsonObject buildEvidenceSchema()
    {
        return Json.createObjectBuilder()
            .add(TYPE, OBJECT)
            .add(NO_EXTRAS, false)
            .add(REQUIRED, Json.createArrayBuilder().add(QUOTE).add(PAGE))
            .add(PROPERTIES, Json.createObjectBuilder()
                .add(QUOTE, Json.createObjectBuilder().add(TYPE, STRING))
                .add(PAGE, Json.createObjectBuilder().add(TYPE, Json.createArrayBuilder().add("integer").add(NULL))))
            .build();
    }
}

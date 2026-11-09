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
 * The strict JSON schema a reply must follow. Each field gets the quotes it rests on, the reasoning, the value and a
 * confidence, in that order.
 *
 * <p>A provider writes the properties in the order the schema lists them, so the model writes its quotes before it
 * commits to a value. The value's type comes from the question: a number is a number, several answers are an array,
 * and a question with options takes only their values. {@code null} is always allowed, since not finding an answer is
 * a real reply.</p>
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
    static final String SCHEMA_NAME = "iap_extraction";

    /** The entry holding the quotes an answer rests on. */
    static final String EVIDENCE_KEY = "evidence";

    /** The entry explaining the answer. */
    static final String REASONING_KEY = "reasoning";

    /** The entry holding the answer itself. */
    static final String VALUE_KEY = "value";

    /** The entry saying how sure the model is. */
    static final String CONFIDENCE_KEY = "confidence";

    /** One quote's words. */
    static final String QUOTE_KEY = "quote";

    /** The page one quote is on. */
    static final String PAGE_KEY = "page";

    private static final String TYPE_KEY = "type";

    private static final String NULL_TYPE = "null";

    private static final String STRING_TYPE = "string";

    private static final String OBJECT_TYPE = "object";

    private static final String ARRAY_TYPE = "array";

    private static final String REQUIRED_KEY = "required";

    private static final String PROPERTIES_KEY = "properties";

    private static final String ADDITIONAL_PROPERTIES_KEY = "additionalProperties";

    private static final String EVIDENCE_REFERENCE = "#/$defs/evidence";

    private ResponseSchema()
    {
        // Utility
    }

    /**
     * The schema for one call.
     *
     * @param fields what the call asks, in the order it asks them
     * @return the schema, as JSON text
     */
    static String build(final List<ExtractionField> fields)
    {
        final JsonObjectBuilder properties = Json.createObjectBuilder();
        final JsonArrayBuilder required = Json.createArrayBuilder();
        for (final ExtractionField field : fields) {
            properties.add(field.key(), buildFieldSchema(field));
            required.add(field.key());
        }
        return Json.createObjectBuilder()
            .add(TYPE_KEY, OBJECT_TYPE)
            .add(ADDITIONAL_PROPERTIES_KEY, false)
            .add(REQUIRED_KEY, required)
            .add(PROPERTIES_KEY, properties)
            .add("$defs", Json.createObjectBuilder().add(EVIDENCE_KEY, buildEvidenceSchema()))
            .build().toString();
    }

    private static JsonObject buildFieldSchema(final ExtractionField field)
    {
        return Json.createObjectBuilder()
            .add(TYPE_KEY, OBJECT_TYPE)
            .add(ADDITIONAL_PROPERTIES_KEY, false)
            .add(REQUIRED_KEY, Json.createArrayBuilder()
                .add(EVIDENCE_KEY).add(REASONING_KEY).add(VALUE_KEY).add(CONFIDENCE_KEY))
            .add(PROPERTIES_KEY, Json.createObjectBuilder()
                .add(EVIDENCE_KEY, Json.createObjectBuilder().add(TYPE_KEY, ARRAY_TYPE)
                    .add("items", Json.createObjectBuilder().add("$ref", EVIDENCE_REFERENCE)))
                .add(REASONING_KEY, Json.createObjectBuilder().add(TYPE_KEY, STRING_TYPE))
                .add(VALUE_KEY, buildValueSchema(field))
                .add(CONFIDENCE_KEY, Json.createObjectBuilder().add(TYPE_KEY, "number")))
            .build();
    }

    private static JsonObject buildValueSchema(final ExtractionField field)
    {
        if (field.answer().multiple()) {
            return Json.createObjectBuilder()
                .add(TYPE_KEY, Json.createArrayBuilder().add(ARRAY_TYPE).add(NULL_TYPE))
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
        final String type = field.answer().options().isEmpty() ? getJsonType(field.answer().dataType()) : STRING_TYPE;
        final JsonObjectBuilder schema = Json.createObjectBuilder()
            .add(TYPE_KEY, nullable ? Json.createArrayBuilder().add(type).add(NULL_TYPE).build()
                : Json.createValue(type));
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

    private static String getJsonType(final String dataType)
    {
        return switch (dataType) {
            case ExtractionField.LONG_TYPE -> "integer";
            case ExtractionField.DOUBLE_TYPE -> "number";
            case ExtractionField.BOOLEAN_TYPE -> "boolean";
            default -> STRING_TYPE;
        };
    }

    private static JsonObject buildEvidenceSchema()
    {
        return Json.createObjectBuilder()
            .add(TYPE_KEY, OBJECT_TYPE)
            .add(ADDITIONAL_PROPERTIES_KEY, false)
            .add(REQUIRED_KEY, Json.createArrayBuilder().add(QUOTE_KEY).add(PAGE_KEY))
            .add(PROPERTIES_KEY, Json.createObjectBuilder()
                .add(QUOTE_KEY, Json.createObjectBuilder().add(TYPE_KEY, STRING_TYPE))
                .add(PAGE_KEY, Json.createObjectBuilder()
                    .add(TYPE_KEY, Json.createArrayBuilder().add("integer").add(NULL_TYPE))))
            .build();
    }
}

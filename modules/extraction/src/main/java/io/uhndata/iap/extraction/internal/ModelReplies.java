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
import java.util.Optional;

import jakarta.json.Json;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

/**
 * Reading what a model answered.
 *
 * <p>A model wraps its JSON in prose however firmly it is told not to, and leaves out or mistypes fields the
 * schema required. Everything here reads defensively and hands back {@code null} or a safe default instead of
 * throwing, so a bad answer is one the caller can ask for again rather than a failure.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ModelReplies
{
    private ModelReplies()
    {
        // Utility
    }

    /**
     * The JSON object in a reply, wherever the model put it.
     *
     * @param reply what the model said
     * @return the object, or {@code null} when the reply holds none
     */
    static JsonObject readJsonObject(final String reply)
    {
        if (reply == null || reply.isBlank()) {
            return null;
        }
        final int start = reply.indexOf('{');
        final int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try (JsonReader reader = Json.createReader(new StringReader(reply.substring(start, end + 1)))) {
            return reader.readObject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A nested object.
     *
     * @param parent the object holding it
     * @param key its key
     * @return the object, or {@code null} when the key is absent or holds something else
     */
    static JsonObject readObject(final JsonObject parent, final String key)
    {
        return parent.containsKey(key) && parent.get(key).getValueType() == JsonValue.ValueType.OBJECT
            ? parent.getJsonObject(key) : null;
    }

    /**
     * A string, taking a non-string value as its JSON text rather than refusing it.
     *
     * @param object the object holding it
     * @param key its key
     * @return the string, or {@code null} when the key is absent or null
     */
    static String readString(final JsonObject object, final String key)
    {
        if (!object.containsKey(key) || object.isNull(key)) {
            return null;
        }
        final JsonValue value = object.get(key);
        return value.getValueType() == JsonValue.ValueType.STRING ? ((JsonString) value).getString()
            : value.toString();
    }

    /**
     * A confidence, held to 0..1.
     *
     * @param object the object holding it
     * @param key its key
     * @return the confidence, or 0 when the key is absent or not a number
     */
    static double readConfidence(final JsonObject object, final String key)
    {
        final JsonValue value = object.get(key);
        if (!(value instanceof JsonNumber)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, ((JsonNumber) value).doubleValue()));
    }

    /**
     * A whole number.
     *
     * @param object the object holding it
     * @param key its key
     * @return the number, or {@code null} when the key is absent, null, or not a whole number
     */
    static Long readLong(final JsonObject object, final String key)
    {
        final JsonValue value = object.get(key);
        if (!(value instanceof JsonNumber) || !((JsonNumber) value).isIntegral()) {
            return null;
        }
        return ((JsonNumber) value).longValue();
    }

    /**
     * A yes or no, and only that. A model that answers {@code null}, {@code "yes"} or a number has not
     * answered the question, and reading any of those as a no would turn a bad answer into a decision.
     *
     * @param object the object holding it
     * @param key its key
     * @return the answer, empty when the key is absent or holds anything but a boolean
     */
    static Optional<Boolean> readBoolean(final JsonObject object, final String key)
    {
        final JsonValue value = object.get(key);
        if (JsonValue.TRUE.equals(value)) {
            return Optional.of(Boolean.TRUE);
        }
        return JsonValue.FALSE.equals(value) ? Optional.of(Boolean.FALSE) : Optional.empty();
    }
}

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
package io.uhndata.iap.workflows.internal;

import java.io.StringReader;

import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * A JSON object an event gives as one of its parameters, such as the {@code patch} of an update.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class EventJson
{
    private EventJson()
    {
        // Utility class
    }

    /**
     * The JSON object an event parameter holds.
     *
     * @param context the executing task's context
     * @param parameter the parameter
     * @return the object
     * @throws InvalidPayloadException when the event gives none, or something else
     */
    static JsonObject object(final WorkflowTaskContext context, final String parameter)
        throws InvalidPayloadException
    {
        return read(context, parameter, false);
    }

    /**
     * The JSON object an event parameter holds, or {@code null} when it holds JSON's {@code null}.
     *
     * @param context the executing task's context
     * @param parameter the parameter
     * @return the object, or {@code null}
     * @throws InvalidPayloadException when the event gives none, or something else
     */
    static JsonObject objectOrNull(final WorkflowTaskContext context, final String parameter)
        throws InvalidPayloadException
    {
        return read(context, parameter, true);
    }

    /**
     * The JSON object an event parameter holds.
     *
     * @param context the executing task's context
     * @param parameter the parameter
     * @param nullable whether JSON's {@code null} is taken, as nothing
     * @return the object, or {@code null}
     * @throws InvalidPayloadException when the event gives none, or something else
     */
    private static JsonObject read(final WorkflowTaskContext context, final String parameter, final boolean nullable)
        throws InvalidPayloadException
    {
        if (!(context.getEvent().get(parameter) instanceof String text)) {
            throw new InvalidPayloadException("The " + parameter + " is required");
        }
        try (JsonReader reader = Json.createReader(new StringReader(text))) {
            final JsonValue value = reader.readValue();
            if (value instanceof JsonObject object) {
                return object;
            }
            if (nullable && value == JsonValue.NULL) {
                return null;
            }
        } catch (final JsonException | IllegalStateException e) {
            // Refused below, as anything else that is not an object
        }
        throw new InvalidPayloadException("The " + parameter + " must be a JSON object"
            + (nullable ? ", or null to remove it" : ""));
    }
}

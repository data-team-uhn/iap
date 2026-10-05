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
package io.uhndata.iap.schemas.editing.internal;

import java.io.StringReader;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The changes an {@code update} event asks for, sent as one JSON object in its {@code patch} parameter: a key
 * left out is left alone, {@code null} removes the property, anything else is the new value.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class SchemaPatch
{
    /** The payload entry holding the patch. */
    static final String PATCH_PARAMETER = "patch";

    private SchemaPatch()
    {
        // Utility class
    }

    /**
     * Reads the patch out of an event.
     *
     * @param context the executing task's context
     * @return the requested changes, by property name
     * @throws InvalidPayloadException when the patch is missing or is not a JSON object
     */
    @NotNull
    static Map<String, JsonValue> read(@NotNull final WorkflowTaskContext context) throws InvalidPayloadException
    {
        final Object patch = context.getEvent().get(PATCH_PARAMETER);
        if (!(patch instanceof String)) {
            throw new InvalidPayloadException("A patch is required");
        }
        try (JsonReader reader = Json.createReader(new StringReader((String) patch))) {
            return reader.readObject();
        } catch (final JsonException | IllegalStateException e) {
            throw new InvalidPayloadException("The patch must be a JSON object", e);
        }
    }
}

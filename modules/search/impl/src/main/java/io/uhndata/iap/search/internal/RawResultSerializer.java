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
package io.uhndata.iap.search.internal;

import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.query.Row;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a query result row into the object that {@code rawResults} asks {@code /search.json} for: the path of each
 * selector, and the value of each selected column.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class RawResultSerializer
{
    /**
     * What a column holding a binary reports instead of its contents. A {@code null} means the row has no value for
     * the column.
     */
    static final String BINARY_PLACEHOLDER = "BINARY_VALUE";

    private static final Logger LOGGER = LoggerFactory.getLogger(RawResultSerializer.class);

    private RawResultSerializer()
    {
        // Utility class
    }

    /**
     * Serializes one row.
     *
     * @param row the row to serialize
     * @param selectors the selector names of the query
     * @param columns the column names of the query
     * @return the serialized row; {@code null} if it cannot be read
     */
    static JsonObject serialize(final Row row, final String[] selectors, final String[] columns)
    {
        try {
            final JsonObjectBuilder builder = Json.createObjectBuilder();
            for (final String selector : selectors) {
                final String path = row.getPath(selector);
                builder.add(selector, path == null ? JsonValue.NULL : Json.createValue(path));
            }
            for (final String column : columns) {
                builder.add(column, columnValue(row.getValue(column)));
            }
            return builder.build();
        } catch (final RepositoryException e) {
            LOGGER.warn("Failed to serialize a search result row: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Converts the value of one column into JSON.
     *
     * @param value the value of the column in the current row; {@code null} if the row has none
     * @return the value as a JSON string; {@link JsonValue#NULL} if the row has no value, and
     *         {@value #BINARY_PLACEHOLDER} for a binary
     * @throws RepositoryException if the value cannot be read
     */
    private static JsonValue columnValue(final Value value) throws RepositoryException
    {
        if (value == null) {
            return JsonValue.NULL;
        }
        // Reading a binary as a string reads all of it, and a statement can select the data of every file in the
        // repository. The client fetches the contents from the node.
        if (value.getType() == PropertyType.BINARY) {
            return Json.createValue(BINARY_PLACEHOLDER);
        }
        return Json.createValue(value.getString());
    }
}

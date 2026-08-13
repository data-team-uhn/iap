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
 * Turns a query result row into the object the {@code rawResults} mode of the search endpoint returns: the path of
 * each selector the query has, and the value of each column it selected, instead of the nodes the query matched.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class RawResultSerializer
{
    private static final Logger LOGGER = LoggerFactory.getLogger(RawResultSerializer.class);

    private RawResultSerializer()
    {
        // This is a utility class, it should not be instantiated
    }

    /**
     * Serializes one row.
     *
     * @param row the row to serialize
     * @param selectors the selector names of the query
     * @param columns the column names of the query
     * @return the serialized row, or {@code null} if it cannot be read
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
     * Converts one selected column into the value that goes into the response.
     *
     * @param value the value of the column in the current row, may be {@code null} for a column the row has no value
     *            for
     * @return the value as JSON, or {@code null} for a column that has none to give
     * @throws RepositoryException if the value cannot be read
     */
    private static JsonValue columnValue(final Value value) throws RepositoryException
    {
        if (value == null) {
            return JsonValue.NULL;
        }
        // Reading a binary as a string reads all of it, and a statement is free to select the data of every file in
        // the repository, so the response says the column is there and leaves its contents to be fetched from the
        // node itself. A search result is a place to find content, not a way to download it.
        if (value.getType() == PropertyType.BINARY) {
            return JsonValue.NULL;
        }
        return Json.createValue(value.getString());
    }
}

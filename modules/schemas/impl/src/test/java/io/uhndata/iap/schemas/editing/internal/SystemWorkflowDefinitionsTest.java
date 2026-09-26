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

import java.io.IOException;
import java.io.Reader;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the system workflows this module ships: each one reachable through the servlet binding, open to
 * administrators only, and performed by handlers that exist.
 *
 * @version $Id$
 * @since 0.1.0
 */
class SystemWorkflowDefinitionsTest
{
    private static final Set<String> BOUND_TYPES = Set.of("sch/SchemasHomepage", "sch/Schema",
        "sch/SchemaVersion", "sch/SchemaPart");

    private static final Set<String> HANDLERS = Set.of("createEntity", InitializeSchemaVersionHandler.HANDLER_NAME,
        UpdateSchemaContentHandler.HANDLER_NAME, ChangeSchemaStateHandler.HANDLER_NAME,
        DiscardSchemaContentHandler.HANDLER_NAME);

    @Test
    void everyDefinitionIsReachableAdministrativeAndPerformable() throws IOException, URISyntaxException
    {
        final List<Path> definitions = definitions();
        assertEquals(9, definitions.size());
        for (final Path path : definitions) {
            final JsonObject version = read(path).getJsonObject("v1");
            final String name = path.getFileName().toString();
            assertTrue(BOUND_TYPES.contains(version.getString("targetResourceType")), name);
            final JsonObject start = version.getJsonObject("requested");
            assertEquals(List.of("iap-administrators"),
                start.getJsonArray("performers").getValuesAs(value -> ((JsonString) value).getString()),
                name);
            version.values().stream()
                .filter(node -> node.getValueType() == JsonValue.ValueType.OBJECT)
                .map(JsonValue::asJsonObject)
                .filter(node -> "wf:Activity".equals(node.getString("jcr:primaryType")))
                .forEach(activity -> assertTrue(HANDLERS.contains(activity.getString("handler")), name));
        }
    }

    private static List<Path> definitions() throws IOException, URISyntaxException
    {
        final Path directory = Path.of(Objects.requireNonNull(
            SystemWorkflowDefinitionsTest.class.getResource("/SLING-INF/content/SystemWorkflows")).toURI());
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> file.toString().endsWith(".json")).sorted().toList();
        }
    }

    private static JsonObject read(final Path path) throws IOException
    {
        try (Reader reader = Files.newBufferedReader(path)) {
            return Json.createReader(reader).readObject();
        }
    }
}

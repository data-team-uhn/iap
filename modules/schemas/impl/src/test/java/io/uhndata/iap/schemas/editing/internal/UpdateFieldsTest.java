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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NodeTypeManager;
import javax.jcr.nodetype.PropertyDefinition;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the fields the shipped update workflows list against the node types they target: the engine leaves out,
 * without a word, a listed field that no targeted type declares, so a misspelt one would never be offered.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class UpdateFieldsTest
{
    // The node types content of each targeted resource type may have
    private static final Map<String, List<String>> NODE_TYPES = Map.of(
        "sch/Schema", List.of("sch:Schema"),
        "sch/SchemaVersion", List.of("sch:SchemaVersion"),
        "sch/SchemaPart", List.of("sch:FormRequirement", "sch:DocumentRequirement", "sch:ApprovalRequirement",
            "sch:Section", "sch:Question"),
        "sch/AnswerOption", List.of("sch:AnswerOption"));

    // The property types updateContent edits
    private static final Set<Integer> EDITABLE = Set.of(PropertyType.STRING, PropertyType.LONG, PropertyType.DOUBLE,
        PropertyType.BOOLEAN, PropertyType.REFERENCE, PropertyType.WEAKREFERENCE);

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    void everyListedFieldIsOneATargetedTypeDeclares() throws IOException, URISyntaxException, RepositoryException
    {
        final NodeTypeManager types =
            this.context.resourceResolver().adaptTo(Session.class).getWorkspace().getNodeTypeManager();
        int checked = 0;
        for (final Path path : definitions()) {
            final JsonObject version = read(path).getJsonObject("v1");
            final JsonObject fields = version.getJsonObject("update") == null ? null
                : version.getJsonObject("update").getJsonObject("fields");
            if (fields == null) {
                continue;
            }
            final List<String> targeted = NODE_TYPES.get(version.getString("targetResourceType"));
            for (final Map.Entry<String, JsonValue> field : fields.entrySet()) {
                if (field.getValue().getValueType() != JsonValue.ValueType.OBJECT) {
                    continue;
                }
                final String where = path.getFileName() + ": " + field.getKey();
                assertTrue(declared(types, targeted, field.getKey()), where);
                final JsonObject applicability = field.getValue().asJsonObject().getJsonObject("appliesWhen");
                if (applicability != null) {
                    assertTrue(declared(types, targeted, applicability.getString("property")), where);
                }
                checked++;
            }
        }
        assertEquals(36, checked);
    }

    private static boolean declared(final NodeTypeManager types, final List<String> targeted, final String name)
        throws RepositoryException
    {
        for (final String type : targeted) {
            final boolean declares = Arrays.stream(types.getNodeType(type).getPropertyDefinitions())
                .anyMatch(definition -> editable(definition, name));
            if (declares) {
                return true;
            }
        }
        return false;
    }

    private static boolean editable(final PropertyDefinition definition, final String name)
    {
        return definition.getName().equals(name) && !definition.isProtected()
            && EDITABLE.contains(definition.getRequiredType());
    }

    private static List<Path> definitions() throws IOException, URISyntaxException
    {
        final Path directory = Path.of(Objects.requireNonNull(
            UpdateFieldsTest.class.getResource("/SLING-INF/content/SystemWorkflows")).toURI());
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

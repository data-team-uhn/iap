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
import java.util.ArrayList;
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
 * Checks the fields the shipped update workflows list against the node types they target, or create: the engine
 * leaves out, without a word, a listed field that no such type declares, so a misspelt one would never be offered.
 * And since the engine gives a create workflow no way to share a draft update's fields, it checks that the copies
 * agree.
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

    // The draft update of each type a create workflow makes, whose fields it must fill in alike
    private static final Map<String, String> DRAFT_UPDATES = Map.of(
        "sch:FormRequirement", "updateDraftSchemaPart.json",
        "sch:DocumentRequirement", "updateDraftSchemaPart.json",
        "sch:ApprovalRequirement", "updateDraftSchemaPart.json",
        "sch:Section", "updateDraftSchemaPart.json",
        "sch:Question", "updateDraftSchemaPart.json",
        "sch:AnswerOption", "updateDraftAnswerOption.json");

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
            final List<String> targeted = targetedTypes(version);
            for (final String type : targeted) {
                assertTrue(types.hasNodeType(type), path.getFileName() + ": " + type);
            }
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
        // The update workflows' fields, then those of the requirement and the part create workflows
        assertEquals(36 + 6 + 15, checked);
    }

    @Test
    void createsWithTheFieldsADraftsUpdateOffers() throws IOException, URISyntaxException, RepositoryException
    {
        final NodeTypeManager types =
            this.context.resourceResolver().adaptTo(Session.class).getWorkspace().getNodeTypeManager();
        int compared = 0;
        for (final Path path : definitions()) {
            final JsonObject version = read(path).getJsonObject("v1");
            if (creating(version) == null) {
                continue;
            }
            final JsonObject fields = version.getJsonObject("update").getJsonObject("fields");
            for (final String created : targetedTypes(version)) {
                final JsonObject draft = read(path.resolveSibling(DRAFT_UPDATES.get(created)))
                    .getJsonObject("v1").getJsonObject("update").getJsonObject("fields");
                assertEquals(declaredFields(types, created, draft), declaredFields(types, created, fields),
                    path.getFileName() + ": " + created);
                compared++;
            }
        }
        assertEquals(6, compared);
    }

    /**
     * The node types an update's fields are held to: what the workflow creates, when it creates something, and else
     * what it targets.
     *
     * @param version a workflow version
     * @return the node types
     */
    private static List<String> targetedTypes(final JsonObject version)
    {
        final JsonObject create = creating(version);
        if (create == null) {
            return NODE_TYPES.get(version.getString("targetResourceType"));
        }
        return create.getJsonObject("types").values().stream()
            .filter(type -> type.getValueType() == JsonValue.ValueType.OBJECT)
            .map(type -> type.asJsonObject().getString("nodeType"))
            .toList();
    }

    /**
     * The activity of a workflow that creates content, if it has one.
     *
     * @param version a workflow version
     * @return its createContent activity, or {@code null}
     */
    private static JsonObject creating(final JsonObject version)
    {
        return version.values().stream()
            .filter(node -> node.getValueType() == JsonValue.ValueType.OBJECT)
            .map(JsonValue::asJsonObject)
            .filter(node -> "createContent".equals(node.getString("handler", null)))
            .findFirst()
            .orElse(null);
    }

    /**
     * The fields listed that a node type declares, in the listed order, with how they are listed.
     *
     * @param types the repository's node types
     * @param type the node type
     * @param fields the fields an activity lists
     * @return the declared ones
     * @throws RepositoryException when the type cannot be read
     */
    private static List<Map.Entry<String, JsonValue>> declaredFields(final NodeTypeManager types, final String type,
        final JsonObject fields) throws RepositoryException
    {
        final List<Map.Entry<String, JsonValue>> declared = new ArrayList<>();
        for (final Map.Entry<String, JsonValue> field : fields.entrySet()) {
            if (field.getValue().getValueType() == JsonValue.ValueType.OBJECT
                && declared(types, List.of(type), field.getKey())) {
                declared.add(field);
            }
        }
        return declared;
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

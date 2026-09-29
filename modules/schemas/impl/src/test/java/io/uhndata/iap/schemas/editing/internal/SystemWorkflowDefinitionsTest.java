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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.conditions.internal.ConditionEvaluatorImpl;
import io.uhndata.iap.conditions.internal.LiteralOperandResolver;
import io.uhndata.iap.conditions.internal.OwnPropertyOperandResolver;
import io.uhndata.iap.conditions.internal.PropertyOperandResolver;
import io.uhndata.iap.conditions.models.Condition;
import io.uhndata.iap.conditions.models.ConditionGroup;
import io.uhndata.iap.conditions.models.ConditionOperand;
import io.uhndata.iap.conditions.models.SingleCondition;
import io.uhndata.iap.content.models.Content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the shipped system workflows: every one is administrative and uses only known steps, and their guards
 * make up the schema lifecycle, each event in each state answered by exactly the workflow meant for it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SystemWorkflowDefinitionsTest
{
    private static final String VERSION = "sch/SchemaVersion";

    private static final String SCHEMA = "sch/Schema";

    private static final String PART = "sch/SchemaPart";

    private static final String OPTION = "sch/AnswerOption";

    private static final String[] NONE = {};

    private static final String UPDATE = "update";

    private static final String[] RETIRED = { "retired" };

    private static final Set<String> BOUND_TYPES =
        Set.of("sch/SchemasHomepage", SCHEMA, VERSION, PART, OPTION);

    private static final Set<String> HANDLERS = Set.of("createEntity", "sendEvent", "addTag", "removeTag", "delete",
        "copyContent", "updateContent", "createContent", "moveContent", "renameContent",
        CreateSchemaVersionHandler.HANDLER_NAME, CheckPublishableHandler.HANDLER_NAME);

    private final SlingContext context = new SlingContext();

    private final ConditionEvaluatorImpl evaluator = new ConditionEvaluatorImpl();

    private int targets;

    @BeforeEach
    void setUp() throws IOException, URISyntaxException, ReflectiveOperationException
    {
        this.context.addModelsForClasses(Content.class, SingleCondition.class, ConditionGroup.class,
            ConditionOperand.class);
        this.context.create().resource("/libs/cond/SingleCondition", "sling:resourceSuperType", "cond/Condition");
        this.context.create().resource("/libs/cond/ConditionGroup", "sling:resourceSuperType", "cond/Condition");
        this.context.create().resource("/libs/sch/SchemaVersion", "sling:resourceSuperType", "data/Entity");
        final Field resolvers = ConditionEvaluatorImpl.class.getDeclaredField("resolvers");
        resolvers.setAccessible(true);
        resolvers.set(this.evaluator, List.of(new LiteralOperandResolver(), new PropertyOperandResolver(),
            new OwnPropertyOperandResolver()));
        for (final Path path : definitions()) {
            final String json = withResourceTypes(read(path)).toString();
            this.context.load().json(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
                "/SystemWorkflows/" + path.getFileName().toString().replace(".json", ""));
        }
    }

    @Test
    void everyDefinitionIsReachableAdministrativeAndPerformable() throws IOException, URISyntaxException
    {
        final List<Path> definitions = definitions();
        assertEquals(23, definitions.size());
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

    @Test
    void aDraftIsPublishedEditedOrDiscarded()
    {
        final String[] draft = { "draft" };
        assertEquals(Set.of("activateSchemaVersion"), answering(VERSION, "activate", draft, NONE));
        assertEquals(Set.of("updateDraftSchemaVersion"), answering(VERSION, "update", draft, NONE));
        assertEquals(Set.of("discardSchemaVersion"), answering(VERSION, "discard", draft, NONE));
        assertEquals(Set.of(), answering(VERSION, "retire", draft, NONE));
        // Not while its schema is retired
        assertEquals(Set.of(), answering(VERSION, "activate", draft, RETIRED));
    }

    @Test
    void anActiveVersionIsRetiredOrReworded()
    {
        final String[] active = { "active" };
        assertEquals(Set.of("retireSchemaVersion"), answering(VERSION, "retire", active, NONE));
        assertEquals(Set.of("updatePublishedSchemaVersion"), answering(VERSION, "update", active, NONE));
        assertEquals(Set.of(), answering(VERSION, "activate", active, NONE));
    }

    @Test
    void aRetiredVersionIsReactivatedWhileItsSchemaIsOpen()
    {
        assertEquals(Set.of("reactivateSchemaVersion"), answering(VERSION, "activate", RETIRED, NONE));
        assertEquals(Set.of(), answering(VERSION, "activate", RETIRED, RETIRED));
        assertEquals(Set.of(), answering(VERSION, "retire", RETIRED, NONE));
        assertEquals(Set.of("updatePublishedSchemaVersion"), answering(VERSION, "update", RETIRED, NONE));
    }

    @Test
    void aSchemaIsRetiredAndReopened()
    {
        assertEquals(Set.of("retireSchema"), answering(SCHEMA, "retire", NONE, NONE));
        assertEquals(Set.of(), answering(SCHEMA, "activate", NONE, NONE));
        assertEquals(Set.of("reopenSchema"), answering(SCHEMA, "activate", RETIRED, NONE));
        assertEquals(Set.of(), answering(SCHEMA, "retire", RETIRED, NONE));
        assertEquals(Set.of("updateSchema"), answering(SCHEMA, "update", RETIRED, NONE));
        assertEquals(Set.of("discardSchema"), answering(SCHEMA, "discard", RETIRED, NONE));
        assertEquals(Set.of("createSchemaVersion"), answering(SCHEMA, "createVersion", NONE, NONE));
        assertEquals(Set.of(), answering(SCHEMA, "createVersion", RETIRED, NONE));
    }

    @Test
    void theContentOfADraftIsEditedAndThatOfAPublishedVersionCorrected()
    {
        assertEquals(Set.of("updateDraftSchemaPart"), answeringPart(PART, UPDATE, new String[] { "draft" }));
        assertEquals(Set.of("updateDraftAnswerOption"), answeringPart(OPTION, UPDATE, new String[] { "draft" }));
        for (final String state : List.of("active", "retired")) {
            assertEquals(Set.of("updatePublishedSchemaPart"), answeringPart(PART, UPDATE, new String[] { state }));
            assertEquals(Set.of("updatePublishedAnswerOption"), answeringPart(OPTION, UPDATE, new String[] { state }));
        }
    }

    @Test
    void partsAreAddedToAndRemovedFromDraftsOnly()
    {
        final String[] draft = { "draft" };
        assertEquals(Set.of("createSchemaRequirement"), answering(VERSION, "create", draft, NONE));
        assertEquals(Set.of("createSchemaPart"), answeringPart(PART, "create", draft));
        assertEquals(Set.of("discardSchemaPart"), answeringPart(PART, "discard", draft));
        assertEquals(Set.of("discardAnswerOption"), answeringPart(OPTION, "discard", draft));
        for (final String state : List.of("active", "retired")) {
            final String[] published = { state };
            assertEquals(Set.of(), answering(VERSION, "create", published, NONE));
            assertEquals(Set.of(), answeringPart(PART, "create", published));
            assertEquals(Set.of(), answeringPart(PART, "discard", published));
            assertEquals(Set.of(), answeringPart(OPTION, "discard", published));
        }
    }

    @Test
    void aQuestionTakingItsOptionsFromElsewhereGainsNoneOfItsOwn()
    {
        final String version = "/content/target" + this.targets++;
        this.context.create().resource(version, Map.of("sling:resourceType", VERSION, "tags",
            new String[] { "draft" }));
        final Content listing = this.context.create().resource(version + "/listing", "sling:resourceType", PART)
            .adaptTo(Content.class);
        final Content elsewhere = this.context.create().resource(version + "/elsewhere",
            Map.of("sling:resourceType", PART, "optionsFrom", "/Vocabularies/sites")).adaptTo(Content.class);

        assertEquals(Set.of("createSchemaPart"), answering(listing, "create"));
        assertEquals(Set.of(), answering(elsewhere, "create"));
    }

    @Test
    void partsAndOptionsAreMovedInDraftsOnly()
    {
        final String[] draft = { "draft" };
        assertEquals(Set.of("moveSchemaPart"), answeringPart(PART, "move", draft));
        assertEquals(Set.of("moveAnswerOption"), answeringPart(OPTION, "move", draft));
        for (final String state : List.of("active", "retired")) {
            final String[] published = { state };
            assertEquals(Set.of(), answeringPart(PART, "move", published));
            assertEquals(Set.of(), answeringPart(OPTION, "move", published));
        }
    }

    @Test
    void partsAreRenamedInDraftsOnly()
    {
        assertEquals(Set.of("renameSchemaPart"), answeringPart(PART, "rename", new String[] { "draft" }));
        for (final String state : List.of("active", "retired")) {
            assertEquals(Set.of(), answeringPart(PART, "rename", new String[] { state }));
        }
        assertEquals(Set.of(), answeringPart(OPTION, "rename", new String[] { "draft" }));
    }

    @Test
    void partsAreCreatedWithTheNamesTheyCanBeRenamedTo() throws IOException, URISyntaxException
    {
        final List<String> patterns = new ArrayList<>();
        final List<String> hints = new ArrayList<>();
        for (final Path path : definitions()) {
            read(path).getJsonObject("v1").values().stream()
                .filter(node -> node.getValueType() == JsonValue.ValueType.OBJECT)
                .map(JsonValue::asJsonObject)
                .filter(node -> Set.of("createContent", "renameContent").contains(node.getString("handler", "")))
                .forEach(activity -> {
                    patterns.add(activity.getString("namePattern", ""));
                    if ("createContent".equals(activity.getString("handler"))) {
                        hints.add(activity.getString("nameHint", ""));
                    }
                });
        }
        assertEquals(3, patterns.size());
        assertEquals(Set.of("^[A-Za-z0-9][A-Za-z0-9_-]*$"), Set.copyOf(patterns));
        // What editors say a name may be, for creating and for renaming alike
        assertEquals(2, hints.size());
        assertEquals(Set.of("Letters, digits, - and _, starting with a letter or a digit."), Set.copyOf(hints));
    }

    @Test
    void anOptionTakesNoNameOfItsOwn() throws IOException, URISyntaxException
    {
        final JsonObject types = read(definitions().get(0).resolveSibling("createSchemaPart.json"))
            .getJsonObject("v1").getJsonObject("create").getJsonObject("types");
        assertFalse(types.getJsonObject("option").getBoolean("named"));
        assertFalse(types.getJsonObject("question").containsKey("named"));
    }

    @Test
    void optionsAreNumberedByTheirPlacesWhereverTheyArePlaced() throws IOException, URISyntaxException
    {
        final Path create = definitions().get(0).resolveSibling("createSchemaPart.json");
        final JsonObject types = read(create).getJsonObject("v1").getJsonObject("create").getJsonObject("types");
        assertEquals("defaultOrder", types.getJsonObject("option").getString("orderProperty"));
        assertFalse(types.getJsonObject("question").containsKey("orderProperty"));
        assertEquals("defaultOrder", read(create.resolveSibling("moveAnswerOption.json")).getJsonObject("v1")
            .getJsonObject("move").getString("orderProperty"));
        assertFalse(read(create.resolveSibling("moveSchemaPart.json")).getJsonObject("v1").getJsonObject("move")
            .containsKey("orderProperty"));
    }

    @Test
    void theUpdatesOfAVersionSayWhatTheyAllow() throws IOException, URISyntaxException
    {
        int updates = 0;
        for (final Path path : definitions()) {
            final JsonObject version = read(path).getJsonObject("v1");
            if (VERSION.equals(version.getString("targetResourceType"))
                && UPDATE.equals(version.getJsonObject("requested").getString("messageName"))) {
                assertFalse(version.getString("notice", "").isBlank(), path.getFileName().toString());
                updates++;
            }
        }
        assertEquals(2, updates);
    }

    @Test
    void aSchemaIsCreatedOnTheHomepage()
    {
        assertEquals(Set.of("createSchema"), answering("sch/SchemasHomepage", "create", NONE, NONE));
    }

    /**
     * The shipped workflows that would take an event aimed at content in a given state.
     *
     * @param type the content's resource type
     * @param event the event
     * @param tags the tags placed on the content itself
     * @param inherited the tags it inherits
     * @return the names of the definitions whose start event catches it and whose guard holds
     */
    private Set<String> answering(final String type, final String event, final String[] tags,
        final String[] inherited)
    {
        return answering(this.context.create().resource("/content/target" + this.targets++, Map.of(
            "sling:resourceType", type, "tags", tags, "inheritedTags", inherited)).adaptTo(Content.class), event);
    }

    /**
     * The shipped workflows that would take an event aimed at something inside a version in a given state.
     *
     * @param type the resource type of what is inside the version
     * @param event the event
     * @param versionTags the tags placed on the version
     * @return the names of the definitions whose start event catches it and whose guard holds
     */
    private Set<String> answeringPart(final String type, final String event, final String[] versionTags)
    {
        final String version = "/content/target" + this.targets++;
        this.context.create().resource(version, Map.of("sling:resourceType", VERSION, "tags", versionTags));
        return answering(this.context.create().resource(version + "/section/question", "sling:resourceType", type)
            .adaptTo(Content.class), event);
    }

    /**
     * The shipped workflows that would take an event aimed at some content.
     *
     * @param target the content
     * @param event the event
     * @return the names of the definitions whose start event catches it and whose guard holds
     */
    private Set<String> answering(final Content target, final String event)
    {
        final String type = target.getType();
        final Set<String> answering = new TreeSet<>();
        for (final Resource definition : this.context.resourceResolver().getResource("/SystemWorkflows")
            .getChildren()) {
            final Resource version = definition.getChild("v1");
            final Resource start = version.getChild("requested");
            if (type.equals(version.getValueMap().get("targetResourceType"))
                && event.equals(start.getValueMap().get("messageName"))) {
                final Resource guard = start.getChild("cond:condition");
                if (guard == null || this.evaluator.isSatisfied(guard.adaptTo(Condition.class), target)) {
                    answering.add(definition.getName());
                }
            }
        }
        return answering;
    }

    /**
     * A definition as the repository would store it, where every node carries the resource type its node type
     * autocreates: the mock repository has no node types to do that.
     *
     * @param node a node of the definition
     * @return the same, with resource types
     */
    private static JsonObject withResourceTypes(final JsonObject node)
    {
        final JsonObjectBuilder builder = Json.createObjectBuilder();
        node.forEach((key, value) -> builder.add(key,
            value.getValueType() == JsonValue.ValueType.OBJECT ? withResourceTypes(value.asJsonObject()) : value));
        if (node.containsKey("jcr:primaryType")) {
            builder.add("sling:resourceType", node.getString("jcr:primaryType").replace(':', '/'));
        }
        return builder.build();
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

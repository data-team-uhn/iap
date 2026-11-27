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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.schemas.editing.internal.handlers.CreateSchemaVersionHandler;
import io.uhndata.iap.workflows.testing.SystemWorkflowDefinitions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the shipped system workflows. Each admits administrators only and uses known steps. Their guards make up the
 * schema lifecycle: in each state, each event starts the one workflow meant for it, or none.
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

    private static final Set<String> HANDLERS = Set.of("createEntity", "callActivity", "addTag", "removeTag", "delete",
        "addLink", "copyContent", "updateContent", "createContent", "moveContent", "renameContent", "replaceContent",
        "retireActiveVersions", CreateSchemaVersionHandler.HANDLER_NAME, "checkValid");

    private final SlingContext context = new SlingContext();

    private SystemWorkflowDefinitions shipped;

    private int versions;

    @BeforeEach
    void setUp() throws IOException
    {
        this.context.create().resource("/libs/sch/SchemaVersion", "sling:resourceSuperType", "data/Entity");
        this.shipped = SystemWorkflowDefinitions.loadIn(this.context, SystemWorkflowDefinitionsTest.class);
    }

    @Test
    void everyDefinitionIsReachableAdministrativeAndPerformable() throws IOException
    {
        final List<String> names = this.shipped.names();
        assertEquals(24, names.size());
        for (final String name : names) {
            final JsonObject version = this.shipped.version(name);
            assertTrue(BOUND_TYPES.contains(version.getString("targetResourceType")), name);
            assertEquals(List.of("iap-administrators"), this.shipped.performers(name), name);
            assertTrue(HANDLERS.containsAll(this.shipped.handlers(name)), name);
        }
    }

    @Test
    void aDraftIsPublishedEditedOrDiscarded()
    {
        final String[] draft = { "draft" };
        assertEquals(Set.of("activateSchemaVersion"), this.shipped.answering(VERSION, "activate", draft, NONE));
        assertEquals(Set.of("updateDraftSchemaVersion"), this.shipped.answering(VERSION, "update", draft, NONE));
        assertEquals(Set.of("discardSchemaVersion"), this.shipped.answering(VERSION, "discard", draft, NONE));
        assertEquals(Set.of(), this.shipped.answering(VERSION, "retire", draft, NONE));
        // Not while its schema is retired
        assertEquals(Set.of(), this.shipped.answering(VERSION, "activate", draft, RETIRED));
    }

    @Test
    void anActiveVersionIsRetiredOrReworded()
    {
        final String[] active = { "active" };
        assertEquals(Set.of("retireSchemaVersion"), this.shipped.answering(VERSION, "retire", active, NONE));
        assertEquals(Set.of("updatePublishedSchemaVersion"), this.shipped.answering(VERSION, "update", active, NONE));
        assertEquals(Set.of(), this.shipped.answering(VERSION, "activate", active, NONE));
    }

    @Test
    void aRetiredVersionIsReactivatedWhileItsSchemaIsOpen()
    {
        assertEquals(Set.of("reactivateSchemaVersion"), this.shipped.answering(VERSION, "activate", RETIRED, NONE));
        assertEquals(Set.of(), this.shipped.answering(VERSION, "activate", RETIRED, RETIRED));
        // Retired only through its schema, which reopening the schema undoes
        assertEquals(Set.of(), this.shipped.answering(VERSION, "activate", NONE, RETIRED));
        assertEquals(Set.of(), this.shipped.answering(VERSION, "retire", RETIRED, NONE));
        assertEquals(Set.of("updatePublishedSchemaVersion"), this.shipped.answering(VERSION, "update", RETIRED, NONE));
    }

    @Test
    void aSchemaIsRetiredAndReopened()
    {
        assertEquals(Set.of("retireSchema"), this.shipped.answering(SCHEMA, "retire", NONE, NONE));
        assertEquals(Set.of(), this.shipped.answering(SCHEMA, "activate", NONE, NONE));
        assertEquals(Set.of("reopenSchema"), this.shipped.answering(SCHEMA, "activate", RETIRED, NONE));
        assertEquals(Set.of(), this.shipped.answering(SCHEMA, "retire", RETIRED, NONE));
        assertEquals(Set.of("updateSchema"), this.shipped.answering(SCHEMA, "update", RETIRED, NONE));
        assertEquals(Set.of("discardSchema"), this.shipped.answering(SCHEMA, "discard", RETIRED, NONE));
        assertEquals(Set.of("createSchemaVersion"), this.shipped.answering(SCHEMA, "createVersion", NONE, NONE));
        assertEquals(Set.of(), this.shipped.answering(SCHEMA, "createVersion", RETIRED, NONE));
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
        assertEquals(Set.of("createSchemaRequirement"), this.shipped.answering(VERSION, "create", draft, NONE));
        assertEquals(Set.of("createSchemaPart"), answeringPart(PART, "create", draft));
        assertEquals(Set.of("discardSchemaPart"), answeringPart(PART, "discard", draft));
        assertEquals(Set.of("discardAnswerOption"), answeringPart(OPTION, "discard", draft));
        for (final String state : List.of("active", "retired")) {
            final String[] published = { state };
            assertEquals(Set.of(), this.shipped.answering(VERSION, "create", published, NONE));
            assertEquals(Set.of(), answeringPart(PART, "create", published));
            assertEquals(Set.of(), answeringPart(PART, "discard", published));
            assertEquals(Set.of(), answeringPart(OPTION, "discard", published));
        }
    }

    @Test
    void aQuestionTakingItsOptionsFromElsewhereGainsNoneOfItsOwn()
    {
        final String version = "/content/version" + this.versions++;
        this.context.create().resource(version, Map.of("sling:resourceType", VERSION, "tags",
            new String[] { "draft" }));
        final Content listing = this.context.create().resource(version + "/listing", "sling:resourceType", PART)
            .adaptTo(Content.class);
        final Content elsewhere = this.context.create().resource(version + "/elsewhere",
            Map.of("sling:resourceType", PART, "optionsFrom", "/Vocabularies/sites")).adaptTo(Content.class);

        assertEquals(Set.of("createSchemaPart"), this.shipped.answering(listing, "create"));
        assertEquals(Set.of(), this.shipped.answering(elsewhere, "create"));
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
    void partsHaveWhenTheyApplySetInDraftsOnly()
    {
        assertEquals(Set.of("updateDraftSchemaCondition"),
            answeringPart(PART, "condition", new String[] { "draft" }));
        for (final String state : List.of("active", "retired")) {
            assertEquals(Set.of(), answeringPart(PART, "condition", new String[] { state }));
        }
        assertEquals(Set.of(), answeringPart(OPTION, "condition", new String[] { "draft" }));
    }

    @Test
    void partsAreCreatedWithTheNamesTheyCanBeRenamedTo() throws IOException
    {
        final List<String> patterns = new ArrayList<>();
        final List<String> hints = new ArrayList<>();
        for (final String name : this.shipped.names()) {
            this.shipped.version(name).values().stream()
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
        // The create workflows say the same rule in words
        assertEquals(2, hints.size());
        assertEquals(Set.of("Letters, digits, - and _, starting with a letter or a digit."), Set.copyOf(hints));
    }

    @Test
    void anOptionTakesNoNameOfItsOwn() throws IOException
    {
        final JsonObject types = this.shipped.version("createSchemaPart").getJsonObject("create")
            .getJsonObject("types");
        assertFalse(types.getJsonObject("option").getBoolean("named"));
        assertFalse(types.getJsonObject("question").containsKey("named"));
    }

    @Test
    void anOptionMovesOnlyAmongTheOptionsOfItsQuestion() throws IOException
    {
        // In another question, it could repeat a value, or join a question that takes its options from elsewhere
        assertEquals("sch/Question",
            this.shipped.version("moveAnswerOption").getJsonObject("move").getString("within"));
        assertEquals("sch/SchemaVersion",
            this.shipped.version("moveSchemaPart").getJsonObject("move").getString("within"));
    }

    @Test
    void theUpdatesOfAVersionSayWhatTheyAllow() throws IOException
    {
        int updates = 0;
        for (final String name : this.shipped.names()) {
            final JsonObject version = this.shipped.version(name);
            if (VERSION.equals(version.getString("targetResourceType"))
                && UPDATE.equals(version.getJsonObject("requested").getString("messageName"))) {
                assertFalse(version.getString("notice", "").isBlank(), name);
                updates++;
            }
        }
        assertEquals(2, updates);
    }

    @Test
    void aSchemaIsCreatedOnTheHomepage()
    {
        assertEquals(Set.of("createSchema"), this.shipped.answering("sch/SchemasHomepage", "create", NONE, NONE));
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
        final String version = "/content/version" + this.versions++;
        this.context.create().resource(version, Map.of("sling:resourceType", VERSION, "tags", versionTags));
        final Resource part = this.context.create().resource(version + "/section/question", "sling:resourceType", type);
        return this.shipped.answering(part.adaptTo(Content.class), event);
    }
}

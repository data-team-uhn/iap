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
package io.uhndata.iap.datarequirement;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.conditions.internal.ConditionEvaluatorImpl;
import io.uhndata.iap.conditions.internal.LiteralOperandResolver;
import io.uhndata.iap.conditions.internal.OwnPropertyOperandResolver;
import io.uhndata.iap.conditions.internal.PropertyOperandResolver;
import io.uhndata.iap.conditions.internal.TagsOperandResolver;
import io.uhndata.iap.conditions.models.Condition;
import io.uhndata.iap.conditions.models.ConditionGroup;
import io.uhndata.iap.conditions.models.ConditionOperand;
import io.uhndata.iap.conditions.models.SingleCondition;
import io.uhndata.iap.content.models.Content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the shipped system workflows of catalogues. Each admits administrators only and uses known steps. Their
 * guards make up the catalogue lifecycle: in each state, each event starts the one workflow meant for it, or none.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class CatalogueSystemWorkflowsTest
{
    private static final String VERSION = "datareq/CatalogueVersion";

    private static final String CATALOGUE = "datareq/Catalogue";

    private static final String ACTIVATE = "activate";

    private static final String RETIRE = "retire";

    private static final String[] NONE = {};

    private static final String[] DRAFT = { "draft" };

    private static final String[] ACTIVE = { "active" };

    private static final String[] RETIRED = { "retired" };

    private static final Set<String> HANDLERS = Set.of("addTag", "removeTag", "retireActiveVersions", "checkValid");

    private final SlingContext context = new SlingContext();

    private ConditionEvaluator evaluator;

    private int targets;

    @BeforeEach
    void setUp() throws IOException, URISyntaxException
    {
        this.context.addModelsForClasses(Content.class, SingleCondition.class, ConditionGroup.class,
            ConditionOperand.class);
        this.context.create().resource("/libs/cond/SingleCondition", "sling:resourceSuperType", "cond/Condition");
        this.context.create().resource("/libs/cond/ConditionGroup", "sling:resourceSuperType", "cond/Condition");
        this.context.registerInjectActivateService(new LiteralOperandResolver());
        this.context.registerInjectActivateService(new PropertyOperandResolver());
        this.context.registerInjectActivateService(new TagsOperandResolver());
        this.context.registerInjectActivateService(new OwnPropertyOperandResolver());
        this.evaluator = this.context.registerInjectActivateService(new ConditionEvaluatorImpl());
        for (final Path path : definitions()) {
            final String json = withResourceTypes(read(path)).toString();
            this.context.load().json(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
                "/SystemWorkflows/" + path.getFileName().toString().replace(".json", ""));
        }
    }

    @Test
    void everyCatalogueDefinitionIsAdministrativeAndPerformable() throws IOException, URISyntaxException
    {
        final List<JsonObject> catalogueDefinitions = definitions().stream()
            .map(CatalogueSystemWorkflowsTest::readVersion)
            .filter(version -> version.getString("targetResourceType").startsWith("datareq/Catalogue"))
            .toList();
        assertEquals(5, catalogueDefinitions.size());
        for (final JsonObject version : catalogueDefinitions) {
            final String description = version.getString("description");
            assertEquals(List.of("iap-administrators"), version.getJsonObject("requested").getJsonArray("performers")
                .getValuesAs(value -> ((JsonString) value).getString()), description);
            version.values().stream()
                .filter(node -> node.getValueType() == JsonValue.ValueType.OBJECT)
                .map(JsonValue::asJsonObject)
                .filter(node -> "wf:Activity".equals(node.getString("jcr:primaryType")))
                .forEach(activity -> assertTrue(HANDLERS.contains(activity.getString("handler")), description));
        }
    }

    @Test
    void aDraftIsPublishedWhileItsCatalogueIsOpen()
    {
        assertEquals(Set.of("activateCatalogueVersion"), answering(VERSION, ACTIVATE, DRAFT, NONE));
        assertEquals(Set.of(), answering(VERSION, ACTIVATE, DRAFT, RETIRED));
        assertEquals(Set.of(), answering(VERSION, RETIRE, DRAFT, NONE));
    }

    @Test
    void anActiveVersionIsRetired()
    {
        assertEquals(Set.of("retireCatalogueVersion"), answering(VERSION, RETIRE, ACTIVE, NONE));
        assertEquals(Set.of(), answering(VERSION, ACTIVATE, ACTIVE, NONE));
    }

    @Test
    void aRetiredVersionIsReactivatedWhileItsCatalogueIsOpen()
    {
        assertEquals(Set.of("reactivateCatalogueVersion"), answering(VERSION, ACTIVATE, RETIRED, NONE));
        assertEquals(Set.of(), answering(VERSION, ACTIVATE, RETIRED, RETIRED));
        // Retired only through its catalogue, which reopening the catalogue undoes
        assertEquals(Set.of(), answering(VERSION, ACTIVATE, NONE, RETIRED));
        assertEquals(Set.of(), answering(VERSION, RETIRE, RETIRED, NONE));
    }

    @Test
    void aCatalogueIsRetiredAndReopened()
    {
        assertEquals(Set.of("retireCatalogue"), answering(CATALOGUE, RETIRE, NONE, NONE));
        assertEquals(Set.of(), answering(CATALOGUE, ACTIVATE, NONE, NONE));
        assertEquals(Set.of("reopenCatalogue"), answering(CATALOGUE, ACTIVATE, RETIRED, NONE));
        assertEquals(Set.of(), answering(CATALOGUE, RETIRE, RETIRED, NONE));
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
        final Content target = this.context.create().resource("/content/target" + this.targets++, Map.of(
            "sling:resourceType", type, "tags", tags, "inheritedTags", inherited)).adaptTo(Content.class);
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
     * A definition as Oak would store it, where every node carries the resource type its node type autocreates. The
     * mock repository has no node types to do that.
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
            CatalogueSystemWorkflowsTest.class.getResource("/SLING-INF/content/SystemWorkflows")).toURI());
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

    private static JsonObject readVersion(final Path path)
    {
        try {
            return read(path).getJsonObject("v1");
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

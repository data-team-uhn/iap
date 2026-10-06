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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
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

import io.uhndata.iap.conditions.internal.ConditionEvaluatorImpl;
import io.uhndata.iap.conditions.internal.LiteralOperandResolver;
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
 * Checks the shipped system workflows: every one is administrative and uses only known steps, and their guards make
 * up the version lifecycle, each event in each lifecycle tag answered by exactly the workflow meant for it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SystemWorkflowDefinitionsTest
{
    private static final String VERSION = "wf/WorkflowVersion";

    private static final String DEFINITION = "wf/WorkflowDefinition";

    private static final Set<String> BOUND_TYPES =
        Set.of("wf/WorkflowsHomepage", "wf/SystemWorkflowsHomepage", DEFINITION, VERSION);

    private static final Set<String> HANDLERS = Set.of("createEntity", "addTag", CopyContentHandler.HANDLER_NAME,
        CreateVersionHandler.NAME, SavePropertiesHandler.NAME, SaveDiagramHandler.NAME,
        RetireActiveVersionsHandler.NAME);

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
        final Field resolvers = ConditionEvaluatorImpl.class.getDeclaredField("resolvers");
        resolvers.setAccessible(true);
        resolvers.set(this.evaluator,
            List.of(new LiteralOperandResolver(), new PropertyOperandResolver(), new TagsOperandResolver()));
        for (final Path path : definitions()) {
            final String json = withResourceTypes(read(path)).toString();
            this.context.load().json(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
                "/SystemWorkflows/" + path.getFileName().toString().replace(".json", ""));
        }
    }

    @Test
    void everyDefinitionIsActiveReachableAdministrativeAndPerformable() throws IOException, URISyntaxException
    {
        final List<Path> definitions = definitions();
        assertEquals(9, definitions.size());
        for (final Path path : definitions) {
            final JsonObject version = read(path).getJsonObject("v1");
            final String name = path.getFileName().toString();
            assertEquals(List.of("active"), strings(version, "tags"), name);
            assertTrue(BOUND_TYPES.contains(version.getString("targetResourceType")), name);
            assertEquals(List.of("iap-administrators"), strings(version.getJsonObject("requested"), "performers"),
                name);
            version.values().stream()
                .filter(node -> node.getValueType() == JsonValue.ValueType.OBJECT)
                .map(JsonValue::asJsonObject)
                .filter(node -> "wf:Activity".equals(node.getString("jcr:primaryType")))
                .forEach(activity -> assertTrue(HANDLERS.contains(activity.getString("handler")), name));
        }
    }

    @Test
    void aDraftIsEditedTriedOrActivated()
    {
        assertEquals(Set.of("saveWorkflowDiagram"), answering(VERSION, "save", "draft"));
        assertEquals(Set.of("startVersionTrial"), answering(VERSION, "startTrial", "draft"));
        assertEquals(Set.of("activateVersion"), answering(VERSION, "activate", "draft"));
        assertEquals(Set.of(), answering(VERSION, "returnToDraft", "draft"));
        assertEquals(Set.of(), answering(VERSION, "retire", "draft"));
    }

    @Test
    void aTrialIsActivatedOrReturnedToDraft()
    {
        assertEquals(Set.of("activateVersion"), answering(VERSION, "activate", "trial"));
        assertEquals(Set.of("returnVersionToDraft"), answering(VERSION, "returnToDraft", "trial"));
        // Frozen like anything past drafting
        assertEquals(Set.of(), answering(VERSION, "save", "trial"));
        assertEquals(Set.of(), answering(VERSION, "startTrial", "trial"));
        assertEquals(Set.of(), answering(VERSION, "retire", "trial"));
    }

    @Test
    void anActiveVersionIsRetired()
    {
        assertEquals(Set.of("retireVersion"), answering(VERSION, "retire", "active"));
        assertEquals(Set.of(), answering(VERSION, "activate", "active"));
        assertEquals(Set.of(), answering(VERSION, "save", "active"));
        assertEquals(Set.of(), answering(VERSION, "returnToDraft", "active"));
    }

    @Test
    void aRetiredVersionIsReactivated()
    {
        assertEquals(Set.of("activateVersion"), answering(VERSION, "activate", "retired"));
        assertEquals(Set.of(), answering(VERSION, "retire", "retired"));
        assertEquals(Set.of(), answering(VERSION, "save", "retired"));
    }

    @Test
    void aVersionInNoLifecycleIsNeitherEditedNorRun()
    {
        // Copying it into a new draft of its workflow is the only way left to carry it forward
        assertEquals(Set.of(), answering(VERSION, "activate"));
        assertEquals(Set.of(), answering(VERSION, "save"));
    }

    @Test
    void aWorkflowIsCreatedRenamedAndGivenVersions()
    {
        assertEquals(Set.of("createWorkflow"), answering("wf/WorkflowsHomepage", "create"));
        assertEquals(Set.of("createSystemWorkflow"), answering("wf/SystemWorkflowsHomepage", "create"));
        assertEquals(Set.of("saveWorkflow"), answering(DEFINITION, "save"));
        assertEquals(Set.of("createVersion"), answering(DEFINITION, "createVersion"));
    }

    /**
     * The shipped workflows that would take an event aimed at content carrying the given tags.
     *
     * @param type the content's resource type
     * @param event the event
     * @param tags the tags placed on the content itself
     * @return the names of the definitions whose start event catches it and whose guard holds
     */
    private Set<String> answering(final String type, final String event, final String... tags)
    {
        final Content target = this.context.create().resource("/content/target" + this.targets++, Map.of(
            "sling:resourceType", type, "tags", tags)).adaptTo(Content.class);
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

    private static List<String> strings(final JsonObject node, final String name)
    {
        return node.getJsonArray(name).getValuesAs(value -> ((JsonString) value).getString());
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

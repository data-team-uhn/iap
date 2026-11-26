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
package io.uhndata.iap.workflows.testing;

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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.jetbrains.annotations.NotNull;

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

/**
 * The system workflows a module ships, loaded into a test's Sling context, for checking which workflow takes an event
 * aimed at content in a given state. A workflow takes it when its version targets the content's resource type, its
 * start event waits for that message, and its guard holds for the content.
 *
 * <p>The definitions are read from the module's own {@code SLING-INF/content/SystemWorkflows}, so a definition added
 * there is checked too. Guards are evaluated by the conditions module's evaluator, with the operand sources a guard
 * on content's own properties and tags uses.</p>
 *
 * <p>A module's tests use it through the {@code iap-workflows} test jar, in the module's {@code tests} profile.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class SystemWorkflowDefinitions
{
    private static final String SHIPPED = "/SLING-INF/content/SystemWorkflows";

    private static final String INSTALLED = "/SystemWorkflows";

    private static final String FIRST_VERSION = "v1";

    private static final String START = "requested";

    private final SlingContext context;

    private final List<Path> files;

    private final ConditionEvaluator evaluator;

    private int targets;

    private SystemWorkflowDefinitions(final SlingContext context, final List<Path> files,
        final ConditionEvaluator evaluator)
    {
        this.context = context;
        this.files = files;
        this.evaluator = evaluator;
    }

    /**
     * Loads every system workflow a module ships into a context, under {@code /SystemWorkflows}.
     *
     * @param context the test's Sling context
     * @param shipper a class of the module under test, whose class path holds its content
     * @return the loaded definitions
     * @throws IOException if a definition cannot be read
     */
    @NotNull
    public static SystemWorkflowDefinitions loadIn(@NotNull final SlingContext context, @NotNull final Class<?> shipper)
        throws IOException
    {
        context.addModelsForClasses(Content.class, SingleCondition.class, ConditionGroup.class,
            ConditionOperand.class);
        context.create().resource("/libs/cond/SingleCondition", "sling:resourceSuperType", "cond/Condition");
        context.create().resource("/libs/cond/ConditionGroup", "sling:resourceSuperType", "cond/Condition");
        context.registerInjectActivateService(new LiteralOperandResolver());
        context.registerInjectActivateService(new PropertyOperandResolver());
        context.registerInjectActivateService(new TagsOperandResolver());
        context.registerInjectActivateService(new OwnPropertyOperandResolver());
        final ConditionEvaluator evaluator = context.registerInjectActivateService(new ConditionEvaluatorImpl());
        final List<Path> files = shippedBy(shipper);
        for (final Path file : files) {
            final byte[] json = withResourceTypes(read(file)).toString().getBytes(StandardCharsets.UTF_8);
            context.load().json(new ByteArrayInputStream(json), INSTALLED + "/" + nameOf(file));
        }
        return new SystemWorkflowDefinitions(context, files, evaluator);
    }

    /**
     * The names of the definitions, as their files and nodes are named.
     *
     * @return the names in alphabetical order, such as {@code retireSchema}
     */
    @NotNull
    public List<String> names()
    {
        return this.files.stream().map(SystemWorkflowDefinitions::nameOf).toList();
    }

    /**
     * A definition's first version, as the module ships it.
     *
     * @param name the definition's name, such as {@code retireSchema}
     * @return the {@code v1} object of its JSON file
     * @throws IOException if the definition cannot be read
     */
    @NotNull
    public JsonObject version(@NotNull final String name) throws IOException
    {
        final Path file = this.files.stream().filter(path -> nameOf(path).equals(name)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No system workflow called " + name));
        return read(file).getJsonObject(FIRST_VERSION);
    }

    /**
     * Who may start a definition.
     *
     * @param name the definition's name, such as {@code retireSchema}
     * @return the performers its start event admits, as the definition lists them
     * @throws IOException if the definition cannot be read
     */
    @NotNull
    public List<String> performers(@NotNull final String name) throws IOException
    {
        return version(name).getJsonObject(START).getJsonArray("performers")
            .getValuesAs(value -> ((JsonString) value).getString());
    }

    /**
     * The steps a definition runs.
     *
     * @param name the definition's name, such as {@code retireSchema}
     * @return the handlers its service tasks name; empty if it has none
     * @throws IOException if the definition cannot be read
     */
    @NotNull
    public Set<String> handlers(@NotNull final String name) throws IOException
    {
        return version(name).values().stream()
            .filter(node -> node.getValueType() == JsonValue.ValueType.OBJECT)
            .map(JsonValue::asJsonObject)
            .filter(node -> "wf:Activity".equals(node.getString("jcr:primaryType")))
            .map(activity -> activity.getString("handler"))
            .collect(Collectors.toSet());
    }

    /**
     * The workflows that would take an event aimed at new content in a given state.
     *
     * @param type the content's resource type, such as {@code sch/Schema}
     * @param event the event's message name, such as {@code retire}
     * @param tags the tags placed on the content itself; empty for none
     * @param inherited the tags it inherits from its ancestors; empty for none
     * @return the names of the definitions taking it; empty if none does
     */
    @NotNull
    public Set<String> answering(@NotNull final String type, @NotNull final String event,
        @NotNull final String[] tags, @NotNull final String[] inherited)
    {
        final Resource target = this.context.create().resource("/content/target" + this.targets++,
            Map.of("sling:resourceType", type, "tags", tags, "inheritedTags", inherited));
        return answering(Objects.requireNonNull(target.adaptTo(Content.class)), event);
    }

    /**
     * The workflows that would take an event aimed at some content.
     *
     * @param target the content the event is aimed at
     * @param event the event's message name, such as {@code move}
     * @return the names of the definitions taking it; empty if none does
     */
    @NotNull
    public Set<String> answering(@NotNull final Content target, @NotNull final String event)
    {
        final Set<String> answering = new TreeSet<>();
        for (final Resource definition : Objects.requireNonNull(
            this.context.resourceResolver().getResource(INSTALLED)).getChildren()) {
            final Resource start = definition.getChild(FIRST_VERSION + "/" + START);
            if (start != null && targets(definition, target) && event.equals(start.getValueMap().get("messageName"))
                && guardHolds(start, target)) {
                answering.add(definition.getName());
            }
        }
        return answering;
    }

    private static boolean targets(final Resource definition, final Content target)
    {
        final Resource version = definition.getChild(FIRST_VERSION);
        return version != null && target.getType().equals(version.getValueMap().get("targetResourceType"));
    }

    private boolean guardHolds(final Resource start, final Content target)
    {
        final Resource guard = start.getChild("cond:condition");
        return guard == null || this.evaluator.isSatisfied(guard.adaptTo(Condition.class), target);
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

    private static List<Path> shippedBy(final Class<?> shipper) throws IOException
    {
        try (Stream<Path> files = Files.list(directoryOf(shipper))) {
            return files.filter(file -> file.toString().endsWith(".json")).sorted().toList();
        }
    }

    private static Path directoryOf(final Class<?> shipper)
    {
        try {
            return Path.of(Objects.requireNonNull(shipper.getResource(SHIPPED),
                () -> shipper.getName() + "'s module ships no system workflows").toURI());
        } catch (final URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nameOf(final Path file)
    {
        return file.getFileName().toString().replace(".json", "");
    }

    private static JsonObject read(final Path path) throws IOException
    {
        try (Reader reader = Files.newBufferedReader(path)) {
            return Json.createReader(reader).readObject();
        }
    }
}

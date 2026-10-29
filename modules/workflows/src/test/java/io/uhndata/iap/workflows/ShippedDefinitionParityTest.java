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
package io.uhndata.iap.workflows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState;
import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import io.uhndata.iap.utils.NodeTypeUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that every system workflow this module ships says the same thing twice.
 *
 * <p>The flow nodes of a shipped definition are written by hand in its {@code <name>.json} descriptor. The
 * definitions are not {@code bpmnAuthoritative}, because a reparse must not be able to throw them away. The
 * {@code v1/bpmn.xml} beside them is what an author opens in the editor. Nothing in the platform forces the two to
 * agree, so this does. For each definition, the vocabulary this module ships is loaded and the diagram is parsed
 * through the same {@link WorkflowDefinitionUtils#parse} the repository uses. The nodes it produces are compared
 * against the descriptor's own.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
class ShippedDefinitionParityTest
{
    private static final String CONTENT_ROOT = "/SLING-INF/content";

    private static final String DEFINITIONS_DIRECTORY = "SystemWorkflows";

    /** The vocabulary ships one directory deeper, so that Sling loads it as {@code /WorkflowTypes}. */
    private static final String VOCABULARY_DIRECTORY = "WorkflowTypes/WorkflowTypes";

    private static final String VOCABULARY_PATH = "/WorkflowTypes";

    private static final String PRIMARY_TYPE = "jcr:primaryType";

    private static final String VERSION_NODETYPE = "wf:WorkflowVersion";

    private static final String BPMN_XML = "bpmn.xml";

    private static final String JSON_SUFFIX = ".json";

    /** The boundary event property whose only source is the diagram's {@code cancelActivity} attribute. */
    private static final String CANCEL_ACTIVITY = "cancelActivity";

    /** How a Sling initial content descriptor writes a JCR reference: the property name, prefixed. */
    private static final String REFERENCE_PREFIX = "jcr:reference:";

    /** The author the parse records, standing in for the service user that loads initial content. */
    private static final String AUTHOR = "admin";

    /**
     * What a descriptor and a parse are allowed to disagree about, each for a reason of its own.
     *
     * <p>{@code jcr:created} is stamped by whichever of the two wrote the node. {@code jcr:uuid} is the
     * repository's, and no descriptor names one. {@code catching} is autocreated and protected by every event node
     * type, so a descriptor never sets it and the parse has nothing to set. {@code bpmnXmlParsedHash} is recorded
     * on the version by the editor that drives the parse, not by the parse.</p>
     *
     * <p>{@code cancelActivity} is the one compared conditionally rather than ignored outright; see
     * {@link #defaultedCancelActivity}. Everything else - the element ids, the labels, the arcs, the typed
     * parameters, and which vocabulary entry each node points at - has to match.</p>
     */
    private static final Set<String> IGNORED_PROPERTIES =
        Set.of("jcr:created", "jcr:uuid", "catching", "bpmnXmlParsedHash");

    /** The diagram is a child of the version in the repository, and is the parse's input rather than its output. */
    private static final Set<String> IGNORED_CHILDREN = Set.of(BPMN_XML);

    @TestFactory
    Stream<DynamicTest> everyShippedDefinitionParsesToItsHandWrittenNodes()
        throws Exception
    {
        final Path content = Path.of(getClass().getResource(CONTENT_ROOT).toURI());
        final List<Path> descriptors = descriptors(content.resolve(DEFINITIONS_DIRECTORY));
        assertFalse(descriptors.isEmpty(), "No shipped system workflow descriptors found under " + content);
        final NodeState repository = repository(content.resolve(VOCABULARY_DIRECTORY));
        return descriptors.stream().map(descriptor -> DynamicTest.dynamicTest(fileName(descriptor),
            () -> assertParity(descriptor, repository)));
    }

    /** The definition descriptors, in a stable order so that a failure report reads the same way twice. */
    private static List<Path> descriptors(final Path directory)
        throws IOException
    {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> file.getFileName().toString().endsWith(JSON_SUFFIX)).sorted().toList();
        }
    }

    /**
     * The repository a parse reads: the vocabulary this module ships, and the node type registry that says what
     * the types it names are.
     */
    private static NodeState repository(final Path vocabulary)
        throws IOException
    {
        final NodeBuilder root = VocabularyFixture.repository();
        final NodeBuilder types = root.child(VocabularyFixture.WORKFLOW_TYPES);
        for (final Path entry : descriptors(vocabulary)) {
            final String name = fileName(entry);
            final NodeBuilder node = types.child(name);
            // The identity a descriptor's jcr:reference: property points at, mapped from the path the same way on
            // both sides. Neither an initial content load nor this test can pick the repository's own uuids.
            node.setProperty("jcr:uuid", VocabularyFixture.id(VOCABULARY_PATH + "/" + name));
            load(read(entry), node);
        }
        return root.getNodeState();
    }

    /**
     * Parses one definition's diagram and reports every way it differs from the nodes the descriptor declares.
     *
     * @param descriptor the definition's {@code <name>.json}
     * @param repository the vocabulary and node type registry to parse against
     * @throws Exception if the descriptor or its diagram cannot be read
     */
    private static void assertParity(final Path descriptor, final NodeState repository)
        throws Exception
    {
        final JsonObject definition = read(descriptor);
        final String name = fileName(descriptor);
        final Path versions = descriptor.resolveSibling(name);
        final List<String> checked = new ArrayList<>();
        final List<String> differences = new ArrayList<>();
        for (final Map.Entry<String, JsonValue> entry : definition.entrySet()) {
            if (!isVersion(entry.getValue())) {
                continue;
            }
            final Path diagram = versions.resolve(entry.getKey()).resolve(BPMN_XML);
            assertTrue(Files.isRegularFile(diagram), "Version " + entry.getKey() + " of " + name
                + " declares flow nodes but ships no " + BPMN_XML + " to derive them from");
            checked.add(entry.getKey());
            compare(entry.getKey(), (JsonObject) entry.getValue(), diagram, repository, differences);
        }
        assertFalse(checked.isEmpty(), name + " declares no " + VERSION_NODETYPE);
        assertTrue(differences.isEmpty(), () -> name + ": the diagram and the hand-written nodes disagree:\n"
            + String.join("\n", differences));
    }

    /**
     * Parses one version's diagram into a fresh version node and compares it with the descriptor's. Whatever it
     * finds is appended to {@code differences}, including anything the parse itself complained about.
     */
    private static void compare(final String version, final JsonObject declared, final Path diagram,
        final NodeState repository, final List<String> differences)
        throws Exception
    {
        final NodeBuilder expected = EmptyNodeState.EMPTY_NODE.builder();
        load(declared, expected);
        // What loading the descriptor through the JCR API would add
        NodeTypeUtils.autoCreateItems(expected, NodeTypeUtils.registry(repository), AUTHOR, Calendar.getInstance());
        final NodeBuilder actual = EmptyNodeState.EMPTY_NODE.builder();
        // A definition that only half translates is a failure too, so what the parse said about the diagram is
        // compared as well as what it produced from it.
        try (RecordedProblems problems = new RecordedProblems()) {
            WorkflowDefinitionUtils.parse(new ByteArrayInputStream(Files.readAllBytes(diagram)), actual, repository,
                AUTHOR, version);
            problems.messages().forEach(problem -> differences.add(version + ": the parse reported " + problem));
        }
        // Only the children: everything else a version carries - its state, its description - is the descriptor's
        // own and no diagram says anything about it.
        compareChildren(expected.getNodeState(), actual.getNodeState(), version, identifiers(repository),
            differences);
    }

    /** Compares the flow nodes and arcs on both sides, by name rather than by position. */
    private static void compareChildren(final NodeState expected, final NodeState actual, final String path,
        final Map<String, String> entryNames, final List<String> differences)
    {
        final Set<String> declared = childNames(expected);
        final Set<String> parsed = childNames(actual);
        declared.stream().filter(name -> !parsed.contains(name))
            .forEach(name -> differences.add(path + "/" + name + " is declared but the diagram produces no such"
                + " node"));
        parsed.stream().filter(name -> !declared.contains(name))
            .forEach(name -> differences.add(path + "/" + name + " is produced by the diagram but not declared"));
        declared.stream().filter(parsed::contains).forEach(name -> {
            final String child = path + "/" + name;
            compareProperties(expected.getChildNode(name), actual.getChildNode(name), child, entryNames,
                differences);
            compareChildren(expected.getChildNode(name), actual.getChildNode(name), child, entryNames, differences);
        });
    }

    private static void compareProperties(final NodeState expected, final NodeState actual, final String path,
        final Map<String, String> entryNames, final List<String> differences)
    {
        final Map<String, String> declared = properties(expected, entryNames);
        final Map<String, String> parsed = properties(actual, entryNames);
        final Set<String> names = new TreeSet<>(declared.keySet());
        names.addAll(parsed.keySet());
        for (final String name : names) {
            if (defaultedCancelActivity(name, expected, actual)) {
                continue;
            }
            if (!Objects.equals(declared.get(name), parsed.get(name))) {
                differences.add(path + " @" + name + ": declared " + declared.getOrDefault(name, "nothing")
                    + ", parsed " + parsed.getOrDefault(name, "nothing"));
            }
        }
    }

    /**
     * Whether a difference about {@code cancelActivity} is only the node type's own default being spelled out. The
     * parse writes it exactly when the diagram declares {@code cancelActivity}. A descriptor restating the default
     * where the diagram says nothing agrees with it. A descriptor claiming anything else does not.
     */
    private static boolean defaultedCancelActivity(final String name, final NodeState expected,
        final NodeState actual)
    {
        return CANCEL_ACTIVITY.equals(name) && !actual.hasProperty(CANCEL_ACTIVITY)
            && expected.getBoolean(CANCEL_ACTIVITY);
    }

    /**
     * A node's properties as comparable text, each carrying its JCR type so that a single value stored where a
     * list belongs is a difference rather than a coincidence. A reference is rendered as the name of the
     * vocabulary entry it points at: a uuid tells nobody which entry a service task ended up on.
     */
    private static Map<String, String> properties(final NodeState node, final Map<String, String> entryNames)
    {
        final Map<String, String> result = new LinkedHashMap<>();
        for (final PropertyState property : node.getProperties()) {
            if (IGNORED_PROPERTIES.contains(property.getName())) {
                continue;
            }
            result.put(property.getName(), render(property, entryNames));
        }
        return result;
    }

    private static String render(final PropertyState property, final Map<String, String> entryNames)
    {
        if (property.isArray()) {
            return property.getType() + " " + property.getValue(Type.STRINGS);
        }
        final String value = property.getValue(Type.STRING);
        if (Type.REFERENCE.equals(property.getType())) {
            return property.getType() + " " + entryNames.getOrDefault(value, "an unknown entry, " + value);
        }
        return property.getType() + " " + value;
    }

    private static Set<String> childNames(final NodeState node)
    {
        final Set<String> result = new TreeSet<>();
        node.getChildNodeNames().forEach(result::add);
        result.removeAll(IGNORED_CHILDREN);
        return result;
    }

    /** Which vocabulary entry each identifier belongs to, for reporting a mismatched reference by name. */
    private static Map<String, String> identifiers(final NodeState repository)
    {
        final Map<String, String> result = new LinkedHashMap<>();
        for (final ChildNodeEntry entry : repository.getChildNode("WorkflowTypes").getChildNodeEntries()) {
            result.put(entry.getNodeState().getString("jcr:uuid"), entry.getName());
        }
        return result;
    }

    private static boolean isVersion(final JsonValue value)
    {
        return value instanceof JsonObject object
            && VERSION_NODETYPE.equals(object.getString(PRIMARY_TYPE, null));
    }

    private static String fileName(final Path file)
    {
        final String name = file.getFileName().toString();
        return name.endsWith(JSON_SUFFIX) ? name.substring(0, name.length() - JSON_SUFFIX.length()) : name;
    }

    /**
     * Reads one initial content descriptor. Line comments are stripped rather than parsed. The loader's own reader
     * accepts them, and the shipped descriptors use them to say why a node is the way it is.
     */
    private static JsonObject read(final Path file)
        throws IOException
    {
        final String content = Arrays.stream(Files.readString(file, StandardCharsets.UTF_8).split("\n"))
            .filter(line -> !line.trim().startsWith("//"))
            .collect(Collectors.joining("\n"));
        try (var reader = Json.createReader(new StringReader(content))) {
            return reader.readObject();
        }
    }

    /**
     * Builds the node a descriptor declares. A nested object is a child node. Everything else is a property, in the
     * JCR type the descriptor's own syntax and JSON's types say it is.
     */
    private static void load(final JsonObject json, final NodeBuilder node)
    {
        for (final Map.Entry<String, JsonValue> entry : json.entrySet()) {
            final String name = entry.getKey();
            final JsonValue value = entry.getValue();
            if (value instanceof JsonObject child) {
                load(child, node.child(name));
            } else if (PRIMARY_TYPE.equals(name)) {
                node.setProperty(name, ((JsonString) value).getString(), Type.NAME);
            } else if (name.startsWith(REFERENCE_PREFIX)) {
                node.setProperty(name.substring(REFERENCE_PREFIX.length()),
                    VocabularyFixture.id(((JsonString) value).getString()), Type.REFERENCE);
            } else if (value instanceof JsonArray array) {
                node.setProperty(name, array.stream().map(ShippedDefinitionParityTest::text).toList(),
                    Type.STRINGS);
            } else {
                setSingleValue(node, name, value);
            }
        }
    }

    private static void setSingleValue(final NodeBuilder node, final String name, final JsonValue value)
    {
        switch (value.getValueType()) {
            case TRUE -> node.setProperty(name, true);
            case FALSE -> node.setProperty(name, false);
            case NUMBER -> node.setProperty(name, ((JsonNumber) value).longValue());
            case NULL -> node.removeProperty(name);
            case STRING, ARRAY, OBJECT -> node.setProperty(name, text(value));
            default -> node.setProperty(name, text(value));
        }
    }

    private static String text(final JsonValue value)
    {
        return value instanceof JsonString string ? string.getString() : value.toString();
    }
}

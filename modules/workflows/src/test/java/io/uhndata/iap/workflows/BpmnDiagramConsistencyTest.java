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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that every authored {@code bpmn.xml} in the repository draws the process it declares.
 *
 * <p>{@link WorkflowDefinitionUtils} never visits {@code bpmndi:*}, which is what makes a diagram safe to edit but
 * also what lets it rot unnoticed: a flow node can lose its shape, or two can end up stacked on the same
 * coordinates, and the workflow still runs exactly as before. Nothing fails, nothing is logged, and the only
 * symptom is that somebody opening the file in a BPMN tool is shown a process that is not the one the engine
 * runs. Nothing else in the build compares the two halves of the file.</p>
 *
 * <p>The checks are deliberately asymmetric about nesting. Everything the process declares at its top level must
 * be drawn, but anything nested — the contents of a collapsed sub-process, say — is merely allowed to be. New
 * BPMN element types are treated as needing a shape unless they are on the short list of things that have no
 * visual form at all, so a node type nobody here has used yet is checked rather than quietly skipped.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
class BpmnDiagramConsistencyTest
{
    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    private static final String DI_NS = "http://www.omg.org/spec/BPMN/20100524/DI";

    private static final String DC_NS = "http://www.omg.org/spec/DD/20100524/DC";

    private static final String BPMN_XML = "bpmn.xml";

    private static final String ID = "id";

    private static final String BPMN_ELEMENT = "bpmnElement";

    private static final String SHAPE = "BPMNShape";

    private static final String EDGE = "BPMNEdge";

    /** How much two shapes may share before it counts as an overlap, so that abutting borders are not flagged. */
    private static final double TOLERANCE = 4;

    /** Never descended into: build output, dependencies, git internals, and any sibling worktree. */
    private static final Set<String> PRUNED = Set.of("target", "node_modules", ".git", ".claude");

    /** Declared inside {@code bpmn:process}, carries an id, but has no visual form of its own. */
    private static final Set<String> NOT_DRAWN =
        Set.of("extensionElements", "documentation", "property", "ioSpecification", "auditing", "monitoring");

    /** Drawn as a connector rather than a box. */
    private static final Set<String> CONNECTORS = Set.of("sequenceFlow", "association", "dataAssociation");

    /** Legitimately drawn around other shapes, so excluded from the overlap check. */
    private static final Set<String> CONTAINERS =
        Set.of("subProcess", "transaction", "adHocSubProcess", "group", "lane", "laneSet", "participant");

    @TestFactory
    Stream<DynamicTest> everyAuthoredDiagramDrawsItsProcess()
    {
        final Path root = reactorRoot();
        return diagrams(root).stream().map(file -> DynamicTest.dynamicTest(root.relativize(file).toString(), () -> {
            final List<String> problems = problems(parse(Files.readString(file, StandardCharsets.UTF_8)));
            assertTrue(problems.isEmpty(), () -> root.relativize(file)
                + " does not draw the process it declares:\n  - " + String.join("\n  - ", problems));
        }));
    }

    @Test
    void theSweepReallyFindsTheAuthoredDiagrams()
    {
        final Path root = reactorRoot();
        assertTrue(Files.isRegularFile(root.resolve("pom.xml")), () -> root + " is not the reactor root");
        assertFalse(diagrams(root).isEmpty(),
            () -> "no " + BPMN_XML + " found anywhere under " + root + ", so the sweep above proved nothing");
    }

    @Test
    void aFlowNodeWithNoShapeIsReported()
    {
        final List<String> problems = problems(parse(diagram(
            task("a") + task("b"),
            shape("a", 100, 100, 100, 80))));
        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains("'b'"), problems::toString);
    }

    @Test
    void aSequenceFlowWithNoEdgeIsReported()
    {
        final List<String> problems = problems(parse(diagram(
            task("a") + task("b") + "    <bpmn:sequenceFlow id=\"f\" sourceRef=\"a\" targetRef=\"b\"/>\n",
            shape("a", 100, 100, 100, 80) + shape("b", 300, 100, 100, 80))));
        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains("'f'"), problems::toString);
    }

    @Test
    void shapesStackedOnEachOtherAreReported()
    {
        final List<String> problems = problems(parse(diagram(
            task("a") + task("b"),
            shape("a", 100, 100, 100, 80) + shape("b", 110, 100, 100, 80))));
        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains("'a'") && problems.get(0).contains("'b'"), problems::toString);
    }

    @Test
    void aBoundaryEventDrawnAwayFromItsHostIsReported()
    {
        final List<String> problems = problems(parse(diagram(
            task("a") + "    <bpmn:boundaryEvent id=\"e\" attachedToRef=\"a\"/>\n",
            shape("a", 100, 100, 100, 80) + shape("e", 600, 600, 36, 36))));
        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains("'e'"), problems::toString);
    }

    @Test
    void aShapeForSomethingTheProcessDoesNotDeclareIsReported()
    {
        final List<String> problems = problems(parse(diagram(
            task("a"),
            shape("a", 100, 100, 100, 80) + shape("ghost", 900, 900, 100, 80))));
        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains("'ghost'"), problems::toString);
    }

    @Test
    void aBoundaryEventOnItsHostIsAccepted()
    {
        assertEquals(List.of(), problems(parse(diagram(
            task("a") + "    <bpmn:boundaryEvent id=\"e\" attachedToRef=\"a\"/>\n",
            shape("a", 100, 100, 100, 80) + shape("e", 130, 162, 36, 36)))));
    }

    /**
     * Collects everything wrong with one parsed diagram, rather than failing at the first problem, because these
     * tend to arrive in groups: one task inserted without re-laying out the plane leaves a missing shape, a stale
     * edge and a stack all at once, and fixing them one build at a time is the slow way to find that out.
     */
    private static List<String> problems(final Document document)
    {
        final List<String> problems = new ArrayList<>();
        final Map<String, String> drawn = new HashMap<>();
        final Map<String, Box> boxes = new HashMap<>();
        elements(document.getElementsByTagNameNS(DI_NS, SHAPE)).forEach(shape -> {
            drawn.put(shape.getAttribute(BPMN_ELEMENT), SHAPE);
            bounds(shape).ifPresent(box -> boxes.put(shape.getAttribute(BPMN_ELEMENT), box));
        });
        elements(document.getElementsByTagNameNS(DI_NS, EDGE))
            .forEach(edge -> drawn.put(edge.getAttribute(BPMN_ELEMENT), EDGE));

        final Map<String, String> kinds = new HashMap<>();
        elements(document.getElementsByTagNameNS(BPMN_NS, "*"))
            .filter(element -> !element.getAttribute(ID).isBlank())
            .forEach(element -> kinds.put(element.getAttribute(ID), element.getLocalName()));

        elements(document.getElementsByTagNameNS(BPMN_NS, "process"))
            .flatMap(BpmnDiagramConsistencyTest::childElements)
            .filter(child -> BPMN_NS.equals(child.getNamespaceURI()))
            .filter(child -> !child.getAttribute(ID).isBlank())
            .filter(child -> !NOT_DRAWN.contains(child.getLocalName()))
            .forEach(child -> {
                final String id = child.getAttribute(ID);
                final String wanted = CONNECTORS.contains(child.getLocalName()) ? EDGE : SHAPE;
                if (!drawn.containsKey(id)) {
                    problems.add(child.getLocalName() + " '" + id + "' has no " + wanted);
                } else if (!wanted.equals(drawn.get(id))) {
                    problems.add(child.getLocalName() + " '" + id + "' is drawn as a " + drawn.get(id)
                        + " instead of a " + wanted);
                }
            });

        drawn.keySet().stream().filter(id -> !kinds.containsKey(id)).sorted()
            .forEach(id -> problems.add("the diagram draws '" + id + "', which the process does not declare"));

        final Map<String, String> hosts = new HashMap<>();
        elements(document.getElementsByTagNameNS(BPMN_NS, "boundaryEvent"))
            .filter(event -> !event.getAttribute(ID).isBlank())
            .forEach(event -> hosts.put(event.getAttribute(ID), event.getAttribute("attachedToRef")));
        hosts.forEach((event, host) -> {
            final Box drawnEvent = boxes.get(event);
            final Box drawnHost = boxes.get(host);
            if (drawnEvent != null && drawnHost != null && !drawnEvent.touches(drawnHost)) {
                problems.add("boundary event '" + event + "' is drawn away from its host '" + host + "'");
            }
        });

        final List<String> ids = boxes.keySet().stream().sorted().toList();
        IntStream.range(0, ids.size()).boxed()
            .flatMap(i -> IntStream.range(i + 1, ids.size()).mapToObj(j -> List.of(ids.get(i), ids.get(j))))
            .filter(pair -> !isAttached(hosts, pair.get(0), pair.get(1)))
            .filter(pair -> pair.stream().noneMatch(id -> CONTAINERS.contains(kinds.getOrDefault(id, ""))))
            .filter(pair -> boxes.get(pair.get(0)).overlaps(boxes.get(pair.get(1))))
            .forEach(pair -> problems.add(
                "'" + pair.get(0) + "' and '" + pair.get(1) + "' are drawn on top of each other"));

        return problems;
    }

    private static boolean isAttached(final Map<String, String> hosts, final String one, final String other)
    {
        return other.equals(hosts.get(one)) || one.equals(hosts.get(other));
    }

    /**
     * The shape's own bounds, which is the first {@code dc:Bounds} child — not the second one nested inside a
     * {@code bpmndi:BPMNLabel}, which positions the caption and routinely sits well outside the shape.
     */
    private static Optional<Box> bounds(final Element shape)
    {
        return childElements(shape)
            .filter(child -> DC_NS.equals(child.getNamespaceURI()) && "Bounds".equals(child.getLocalName()))
            .findFirst()
            .map(box -> new Box(attribute(box, "x"), attribute(box, "y"),
                attribute(box, "width"), attribute(box, "height")));
    }

    private static double attribute(final Element element, final String name)
    {
        return Double.parseDouble(element.getAttribute(name));
    }

    private static Stream<Element> elements(final NodeList nodes)
    {
        return IntStream.range(0, nodes.getLength()).mapToObj(nodes::item)
            .filter(Element.class::isInstance).map(Element.class::cast);
    }

    private static Stream<Element> childElements(final Element parent)
    {
        return elements(parent.getChildNodes());
    }

    /** Walks up to the outermost directory that still has a {@code pom.xml}, which is the reactor root. */
    private static Path reactorRoot()
    {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (candidate.getParent() != null && Files.isRegularFile(candidate.getParent().resolve("pom.xml"))) {
            candidate = candidate.getParent();
        }
        return candidate;
    }

    private static List<Path> diagrams(final Path root)
    {
        final List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>()
            {
                @Override
                public FileVisitResult preVisitDirectory(final Path directory, final BasicFileAttributes attributes)
                {
                    return PRUNED.contains(directory.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes)
                {
                    if (BPMN_XML.equals(file.getFileName().toString())) {
                        found.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        found.sort(Path::compareTo);
        return found;
    }

    private static Document parse(final String xml)
    {
        try {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (final ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalStateException("cannot parse the diagram", e);
        }
    }

    private static String diagram(final String process, final String plane)
    {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<bpmn:definitions xmlns:bpmn=\"" + BPMN_NS + "\" xmlns:bpmndi=\"" + DI_NS + "\""
            + " xmlns:dc=\"" + DC_NS + "\" id=\"defs\">\n"
            + "  <bpmn:process id=\"p\">\n" + process + "  </bpmn:process>\n"
            + "  <bpmndi:BPMNDiagram id=\"d\">\n"
            + "    <bpmndi:BPMNPlane id=\"pl\" bpmnElement=\"p\">\n" + plane
            + "    </bpmndi:BPMNPlane>\n  </bpmndi:BPMNDiagram>\n</bpmn:definitions>\n";
    }

    private static String task(final String id)
    {
        return "    <bpmn:userTask id=\"" + id + "\"/>\n";
    }

    private static String shape(final String element, final int x, final int y, final int width, final int height)
    {
        return "      <bpmndi:BPMNShape id=\"" + element + "Shape\" bpmnElement=\"" + element + "\">\n"
            + "        <dc:Bounds x=\"" + x + "\" y=\"" + y + "\" width=\"" + width + "\" height=\"" + height
            + "\"/>\n      </bpmndi:BPMNShape>\n";
    }

    private record Box(double x, double y, double width, double height)
    {
        boolean overlaps(final Box other)
        {
            return Math.min(this.x + this.width, other.x + other.width) - Math.max(this.x, other.x) > TOLERANCE
                && Math.min(this.y + this.height, other.y + other.height) - Math.max(this.y, other.y) > TOLERANCE;
        }

        boolean touches(final Box other)
        {
            return this.x + this.width >= other.x && this.x <= other.x + other.width
                && this.y + this.height >= other.y && this.y <= other.y + other.height;
        }
    }
}

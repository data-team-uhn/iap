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
package io.uhndata.iap.workflows.models;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WorkflowVersion}, in particular the graph navigation the engine will lean on.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class WorkflowVersionTest
{
    private static final String VERSION_PATH = "/Workflows/timeOff/1.0";

    private static final String BPMN = "<bpmn:definitions/>";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
    }

    @Test
    void exposesVersionProperties() throws IOException
    {
        final Resource resource = this.context.create().resource(VERSION_PATH, Map.of(
            TYPE, WorkflowVersion.RESOURCE_TYPE,
            "version", "1.0",
            "description", "The first cut",
            "active", true,
            "bpmnXmlParsedHash", "abc123"));
        // The source is a file child, not a property, so it is loaded as one
        this.context.load().binaryFile(new ByteArrayInputStream(BPMN.getBytes(StandardCharsets.UTF_8)),
            VERSION_PATH + "/bpmn.xml", "application/xml");
        final WorkflowVersion version = resource.adaptTo(WorkflowVersion.class);

        assertNotNull(version);
        assertEquals("1.0", version.getVersion());
        assertEquals("The first cut", version.getDescription());
        assertTrue(version.isActive());
        assertEquals(BPMN, read(version.getBpmnFile()));
        assertEquals("abc123", version.getBpmnXmlParsedHash());
    }

    @Test
    void exposesTheBpmnSourceAsAFile()
    {
        final Resource resource = this.context.create().resource(VERSION_PATH, TYPE,
            WorkflowVersion.RESOURCE_TYPE);
        this.context.load().binaryFile(new ByteArrayInputStream(BPMN.getBytes(StandardCharsets.UTF_8)),
            VERSION_PATH + "/bpmn.xml", "application/xml");
        final WorkflowVersion version = resource.adaptTo(WorkflowVersion.class);

        assertNotNull(version);
        final Resource file = version.getBpmnFile();
        assertNotNull(file);
        // A file, so that it can be downloaded and re-uploaded as the document it is
        assertEquals("nt:file", file.getValueMap().get("jcr:primaryType", String.class));
        assertEquals(VERSION_PATH + "/bpmn.xml", file.getPath());
    }

    @Test
    void toleratesMissingOptionalProperties()
    {
        final Resource resource = this.context.create().resource(VERSION_PATH, TYPE,
            WorkflowVersion.RESOURCE_TYPE);
        final WorkflowVersion version = resource.adaptTo(WorkflowVersion.class);

        assertNotNull(version);
        assertNull(version.getDescription());
        assertNull(version.getBpmnFile());
        assertNull(version.getBpmnXmlParsedHash());
        assertFalse(version.isActive());
        assertTrue(version.getFlowNodes().isEmpty());
        assertTrue(version.getStartEvents().isEmpty());
        assertNull(version.getFlowNode("nothing"));
    }

    @Test
    void listsFlowNodesUsingTheSpecificModelForEach()
    {
        final WorkflowVersion version = this.createGraph();

        final List<FlowNode> nodes = version.getFlowNodes();

        assertEquals(4, nodes.size());
        assertEquals(StartEvent.class, nodes.get(0).getClass());
        assertEquals(Activity.class, nodes.get(1).getClass());
        assertEquals(ExclusiveGateway.class, nodes.get(2).getClass());
        assertEquals(EndEvent.class, nodes.get(3).getClass());
    }

    @Test
    void listsOnlyTheStartEvents()
    {
        final WorkflowVersion version = this.createGraph();

        final List<StartEvent> starts = version.getStartEvents();

        assertEquals(1, starts.size());
        assertEquals("start_1", starts.get(0).getElementId());
    }

    @Test
    void findsFlowNodesByElementId()
    {
        final WorkflowVersion version = this.createGraph();

        assertEquals("task_1", version.getFlowNode("task_1").getElementId());
        assertEquals(Activity.class, version.getFlowNode("task_1").getClass());
        assertEquals("end_1", version.getFlowNode("end_1").getElementId());
    }

    @Test
    void findsBoundaryEventsNestedInsideAnActivity()
    {
        // A boundary event hangs off its activity rather than off the version, but it is still addressed by the
        // same flat element identifier space, so the search has to descend into the nodes it walks past
        final WorkflowVersion version = this.createGraph();

        final FlowNode boundary = version.getFlowNode("boundary_1");

        assertNotNull(boundary);
        assertEquals(IntermediateCatchingEvent.class, boundary.getClass());
        // Nesting, not the node type, is what makes it a boundary event
        assertEquals("task_1", ((IntermediateCatchingEvent) boundary).getActivity().getElementId());
    }

    @Test
    void returnsNullForAnUnknownElementId()
    {
        final WorkflowVersion version = this.createGraph();

        assertNull(version.getFlowNode("no_such_node"));
    }

    @Test
    void returnsNullWhenNoElementIdIsAskedFor()
    {
        // The callers read the identifier from properties that are mandatory but can be missing on a malformed
        // node, so a null must find nothing rather than fail the search
        final WorkflowVersion version = this.createGraph();

        assertNull(version.getFlowNode(null));
    }

    @Test
    void leavesOutNodesOfTheAbstractTypes()
    {
        // Each abstract base has a /libs/wf entry, since the supertype chains are followed through them, but is the
        // resource type of no model: such a node matches none and would arrive as whichever sorts first, an Activity
        final WorkflowVersion version = this.createGraph();
        this.context.create().resource(VERSION_PATH + "/abstract_1", Map.of(
            TYPE, FlowNode.RESOURCE_TYPE, "elementId", "abstract_1"));
        this.context.create().resource(VERSION_PATH + "/abstract_2", Map.of(
            TYPE, Event.RESOURCE_TYPE, "elementId", "abstract_2"));
        this.context.create().resource(VERSION_PATH + "/abstract_3", Map.of(
            TYPE, Gateway.RESOURCE_TYPE, "elementId", "abstract_3"));
        this.context.create().resource(VERSION_PATH + "/abstract_4", Map.of(
            TYPE, IntermediateEvent.RESOURCE_TYPE, "elementId", "abstract_4"));

        assertEquals(4, version.getFlowNodes().size());
        assertEquals(5, version.getAllFlowNodes().size());
        assertNull(version.getFlowNode("abstract_1"));
        assertNull(version.getFlowNode("abstract_2"));
        assertNull(version.getFlowNode("abstract_3"));
        assertNull(version.getFlowNode("abstract_4"));
    }

    /**
     * Builds a small but representative graph: a start event, an activity carrying a boundary event, a gateway and
     * an end event.
     *
     * @return the model of the version holding it
     */
    private WorkflowVersion createGraph()
    {
        final Resource resource = this.context.create().resource(VERSION_PATH,
            TYPE, WorkflowVersion.RESOURCE_TYPE, "version", "1.0");
        this.context.create().resource(VERSION_PATH + "/start_1", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, "elementId", "start_1"));
        this.context.create().resource(VERSION_PATH + "/task_1", Map.of(
            TYPE, Activity.RESOURCE_TYPE, "elementId", "task_1"));
        this.context.create().resource(VERSION_PATH + "/task_1/boundary_1", Map.of(
            TYPE, IntermediateCatchingEvent.RESOURCE_TYPE, "elementId", "boundary_1"));
        this.context.create().resource(VERSION_PATH + "/gateway_1", Map.of(
            TYPE, ExclusiveGateway.RESOURCE_TYPE, "elementId", "gateway_1"));
        this.context.create().resource(VERSION_PATH + "/end_1", Map.of(
            TYPE, EndEvent.RESOURCE_TYPE, "elementId", "end_1"));
        return resource.adaptTo(WorkflowVersion.class);
    }

    /**
     * Reads a file resource the way a caller of {@link WorkflowVersion#getBpmnFile} would, which is the point of
     * handing back the file rather than its contents. Decoded through the charset rather than a String
     * constructor, which checkstyle forbids.
     *
     * @param file the file resource to read
     * @return its contents, decoded as UTF-8
     * @throws IOException if reading fails, which fails the test
     */
    private static String read(final Resource file) throws IOException
    {
        assertNotNull(file);
        try (InputStream source = file.adaptTo(InputStream.class)) {
            assertNotNull(source);
            return StandardCharsets.UTF_8.decode(ByteBuffer.wrap(source.readAllBytes())).toString();
        }
    }
}

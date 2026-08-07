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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for walking the graph against the direction it is stored in: flattening every node out of the tree,
 * and finding the arcs that lead into a node, which is what a join gateway is defined by.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class GraphNavigationTest
{
    private static final String VERSION_PATH = "/Workflows/timeOff/1.0";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
    }

    @Test
    void flattensEveryNodeIncludingNestedOnes()
    {
        final WorkflowVersion version = this.createJoinGraph();

        final List<FlowNode> all = version.getAllFlowNodes();

        // Four top-level nodes plus the boundary event hanging off the activity
        assertEquals(4, version.getFlowNodes().size());
        assertEquals(5, all.size());
        assertTrue(all.stream().anyMatch(node -> "reminder".equals(node.getElementId())));
    }

    @Test
    void flattensNothingForAnUnparsedVersion()
    {
        final Resource resource = this.context.create().resource(VERSION_PATH, TYPE,
            WorkflowVersion.RESOURCE_TYPE, "version", "1.0");

        assertTrue(resource.adaptTo(WorkflowVersion.class).getAllFlowNodes().isEmpty());
    }

    @Test
    void findsTheArcsLeadingIntoAJoin()
    {
        // The point of the whole exercise: a parallel join has to know how many arcs it is waiting on, and the
        // arcs are stored pointing the other way
        final WorkflowVersion version = this.createJoinGraph();

        final List<SequenceFlow> incoming = version.getFlowNode("join_1").getIncomingFlows();

        assertEquals(2, incoming.size());
        assertEquals("from_task", incoming.get(0).getElementId());
        assertEquals("from_reminder", incoming.get(1).getElementId());
    }

    @Test
    void findsIncomingArcsFromNestedNodesToo()
    {
        // "from_reminder" leaves a boundary event, which is not a direct child of the version, so a search that
        // only walked the top level would miss it
        final WorkflowVersion version = this.createJoinGraph();

        assertTrue(version.getFlowNode("join_1").getIncomingFlows().stream()
            .anyMatch(flow -> "from_reminder".equals(flow.getElementId())));
    }

    @Test
    void findsNoIncomingArcsForAStartEvent()
    {
        final WorkflowVersion version = this.createJoinGraph();

        assertTrue(version.getFlowNode("start_1").getIncomingFlows().isEmpty());
    }

    @Test
    void findsNoIncomingArcsForANodeStoredOutsideAVersion()
    {
        final Resource orphan = this.context.create().resource("/elsewhere/task_9", Map.of(
            TYPE, Activity.RESOURCE_TYPE, "elementId", "task_9"));

        assertTrue(orphan.adaptTo(FlowNode.class).getIncomingFlows().isEmpty());
    }

    @Test
    void distinguishesATerminatingEndEventFromAnOrdinaryOne()
    {
        final Resource ordinary = this.context.create().resource(VERSION_PATH + "/end_1", Map.of(
            TYPE, EndEvent.RESOURCE_TYPE, "elementId", "end_1"));
        final Resource terminating = this.context.create().resource(VERSION_PATH + "/end_2", Map.of(
            TYPE, EndEvent.RESOURCE_TYPE, "elementId", "end_2", "terminate", true));

        assertFalse(((EndEvent) ordinary.adaptTo(FlowNode.class)).isTerminate());
        assertTrue(((EndEvent) terminating.adaptTo(FlowNode.class)).isTerminate());
    }

    /**
     * Builds a graph that forks and joins: a start event leading to an activity, the activity carrying a
     * non-interrupting boundary event, and both the activity and that boundary event leading into a parallel join.
     *
     * @return the model of the version holding it
     */
    private WorkflowVersion createJoinGraph()
    {
        final Resource resource = this.context.create().resource(VERSION_PATH,
            TYPE, WorkflowVersion.RESOURCE_TYPE, "version", "1.0");
        this.context.create().resource(VERSION_PATH + "/start_1", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, "elementId", "start_1"));
        this.context.create().resource(VERSION_PATH + "/start_1/to_task", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, "elementId", "to_task", "targetRef", "task_1"));
        this.context.create().resource(VERSION_PATH + "/task_1", Map.of(
            TYPE, Activity.RESOURCE_TYPE, "elementId", "task_1"));
        this.context.create().resource(VERSION_PATH + "/task_1/from_task", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, "elementId", "from_task", "targetRef", "join_1"));
        this.context.create().resource(VERSION_PATH + "/task_1/reminder", Map.of(
            TYPE, IntermediateCatchingEvent.RESOURCE_TYPE, "elementId", "reminder", "interrupting", false));
        this.context.create().resource(VERSION_PATH + "/task_1/reminder/from_reminder", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, "elementId", "from_reminder", "targetRef", "join_1"));
        this.context.create().resource(VERSION_PATH + "/join_1", Map.of(
            TYPE, ParallelGateway.RESOURCE_TYPE, "elementId", "join_1"));
        this.context.create().resource(VERSION_PATH + "/end_1", Map.of(
            TYPE, EndEvent.RESOURCE_TYPE, "elementId", "end_1"));
        return resource.adaptTo(WorkflowVersion.class);
    }
}

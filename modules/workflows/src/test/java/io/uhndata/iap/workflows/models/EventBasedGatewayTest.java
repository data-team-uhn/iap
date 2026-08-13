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
 * Unit tests for {@link EventBasedGateway}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class EventBasedGatewayTest
{
    private static final String VERSION_PATH = "/Workflows/timeOff/1.0";

    private static final String GATEWAY_PATH = VERSION_PATH + "/gateway_1";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
        this.context.create().resource(VERSION_PATH, TYPE, WorkflowVersion.RESOURCE_TYPE, "version", "1.0");
    }

    @Test
    void doesNotInstantiateByDefault()
    {
        final Resource resource = this.createGateway();

        assertFalse(((EventBasedGateway) resource.adaptTo(FlowNode.class)).isInstantiate());
    }

    @Test
    void mayInstantiateTheWorkflow()
    {
        final Resource resource = this.context.create().resource(GATEWAY_PATH, Map.of(
            TYPE, EventBasedGateway.RESOURCE_TYPE, "elementId", "gateway_1", "instantiate", true));

        assertTrue(((EventBasedGateway) resource.adaptTo(FlowNode.class)).isInstantiate());
    }

    @Test
    void listsTheCatchingEventsItWaitsFor()
    {
        final Resource resource = this.createGateway();
        this.arc("to_approval", "approval");
        this.arc("to_timeout", "timeout");
        this.context.create().resource(VERSION_PATH + "/approval", Map.of(
            TYPE, IntermediateCatchingEvent.RESOURCE_TYPE, "elementId", "approval", "catching", true));
        this.context.create().resource(VERSION_PATH + "/timeout", Map.of(
            TYPE, IntermediateCatchingEvent.RESOURCE_TYPE, "elementId", "timeout", "catching", true));

        final List<Event> awaited = ((EventBasedGateway) resource.adaptTo(FlowNode.class)).getAwaitedEvents();

        assertEquals(2, awaited.size());
        assertEquals("approval", awaited.get(0).getElementId());
        assertEquals("timeout", awaited.get(1).getElementId());
    }

    @Test
    void ignoresArcsThatDoNotLeadToACatchingEvent()
    {
        // A gateway wired to a task or to a throwing event is malformed BPMN; it is reported as not waiting for
        // those rather than being taken to wait for something it cannot catch
        final Resource resource = this.createGateway();
        this.arc("to_task", "task_1");
        this.arc("to_throw", "throw_1");
        this.arc("to_nowhere", "no_such_node");
        this.context.create().resource(VERSION_PATH + "/task_1", Map.of(
            TYPE, Activity.RESOURCE_TYPE, "elementId", "task_1"));
        this.context.create().resource(VERSION_PATH + "/throw_1", Map.of(
            TYPE, IntermediateThrowingEvent.RESOURCE_TYPE, "elementId", "throw_1", "catching", false));

        assertTrue(((EventBasedGateway) resource.adaptTo(FlowNode.class)).getAwaitedEvents().isEmpty());
    }

    @Test
    void ignoresArcsLeadingToANodeOfATypeNoModelClaims()
    {
        // A node of a resource type no model claims is built as whichever implementation sorts first, without
        // being one, so arriving as an Event is not the same as being one and the node's own type has to decide.
        // Which implementation sorts first is nothing this code should depend on, which is why the check is
        // explicit rather than left to the ordering
        this.context.create().resource("/libs/wf/Custom", Map.of(
            WorkflowFixture.SUPER_TYPE, FlowNode.RESOURCE_TYPE));
        final Resource resource = this.createGateway();
        this.arc("to_custom", "custom_1");
        this.context.create().resource(VERSION_PATH + "/custom_1", Map.of(
            TYPE, "wf/Custom", "elementId", "custom_1", "catching", true));

        assertTrue(((EventBasedGateway) resource.adaptTo(FlowNode.class)).getAwaitedEvents().isEmpty());
    }

    @Test
    void reportsAMarkedDefaultFlowThoughItCannotActOnOne()
    {
        // getDefaultFlow is inherited and still reports whatever the data says, but a default is meaningless on
        // this kind of gateway — the events are the choice. The engine ignores it rather than the model hiding it.
        final Resource resource = this.createGateway();
        this.context.create().resource(GATEWAY_PATH + "/to_approval", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, "elementId", "to_approval", "targetRef", "approval",
            "isDefault", true));

        assertEquals("to_approval",
            ((EventBasedGateway) resource.adaptTo(FlowNode.class)).getDefaultFlow().getElementId());
    }

    private Resource createGateway()
    {
        return this.context.create().resource(GATEWAY_PATH, Map.of(
            TYPE, EventBasedGateway.RESOURCE_TYPE, "elementId", "gateway_1"));
    }

    private void arc(final String elementId, final String targetRef)
    {
        this.context.create().resource(GATEWAY_PATH + "/" + elementId, Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, "elementId", elementId, "targetRef", targetRef));
    }
}

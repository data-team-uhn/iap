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

import javax.jcr.RepositoryException;

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
 * Unit tests for {@link FlowNode} and the concrete node models on top of it, exercised through the subtypes since
 * the bases are not adaptable on their own.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class FlowNodeTest
{
    private static final String ELEMENT_ID = "elementId";

    private static final String VERSION_PATH = "/Workflows/timeOff/1.0";

    private static final String TYPE_ID = "activity-type-uuid";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
        this.context.create().resource(VERSION_PATH, TYPE, WorkflowVersion.RESOURCE_TYPE, "version", "1.0");
    }

    @Test
    void exposesFlowNodeProperties()
    {
        final Resource resource = this.context.create().resource(VERSION_PATH + "/task_1", Map.of(
            TYPE, Activity.RESOURCE_TYPE, ELEMENT_ID, "task_1", "label", "Approve the request"));
        final FlowNode node = resource.adaptTo(FlowNode.class);

        assertNotNull(node);
        assertEquals("task_1", node.getElementId());
        assertEquals("Approve the request", node.getLabel());
    }

    @Test
    void toleratesAnUnlabelledNodeWithNoType()
    {
        final Resource resource = this.context.create().resource(VERSION_PATH + "/task_1", Map.of(
            TYPE, Activity.RESOURCE_TYPE, ELEMENT_ID, "task_1"));
        final FlowNode node = resource.adaptTo(FlowNode.class);

        assertNull(node.getLabel());
        assertNull(node.getFlowNodeType());
        assertTrue(node.getOutgoingFlows().isEmpty());
        assertTrue(node.getNestedNodes().isEmpty());
    }

    @Test
    void resolvesItsTypeInTheVocabulary()
        throws RepositoryException
    {
        this.context.create().resource("/WorkflowTypes/UserTask", Map.of(
            TYPE, ActivityType.RESOURCE_TYPE, "label", "User Task", "xmlElement", "bpmn:userTask",
            "jcrNodeType", "wf:Activity"));
        WorkflowFixture.resolveReference(this.context, TYPE_ID, "/WorkflowTypes/UserTask");

        final Resource resource = this.context.create().resource(VERSION_PATH + "/task_1", Map.of(
            TYPE, Activity.RESOURCE_TYPE, ELEMENT_ID, "task_1", "flowNodeType", TYPE_ID));
        final FlowNodeType type = resource.adaptTo(FlowNode.class).getFlowNodeType();

        assertNotNull(type);
        assertEquals(ActivityType.class, type.getClass());
        assertEquals("User Task", type.getDocumentationLabel());
    }

    @Test
    void listsItsOutgoingFlows()
    {
        final Resource resource = this.context.create().resource(VERSION_PATH + "/gateway_1", Map.of(
            TYPE, ExclusiveGateway.RESOURCE_TYPE, ELEMENT_ID, "gateway_1"));
        this.context.create().resource(VERSION_PATH + "/gateway_1/flow_1", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "flow_1", "targetRef", "end_1"));
        this.context.create().resource(VERSION_PATH + "/gateway_1/flow_2", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "flow_2", "targetRef", "task_1"));

        final List<SequenceFlow> flows = resource.adaptTo(FlowNode.class).getOutgoingFlows();

        assertEquals(2, flows.size());
        assertEquals("flow_1", flows.get(0).getElementId());
        assertEquals("flow_2", flows.get(1).getElementId());
    }

    @Test
    void findsItsOwningVersionFromAnyDepth()
    {
        final Resource activity = this.context.create().resource(VERSION_PATH + "/task_1", Map.of(
            TYPE, Activity.RESOURCE_TYPE, ELEMENT_ID, "task_1"));
        final Resource boundary = this.context.create().resource(VERSION_PATH + "/task_1/boundary_1", Map.of(
            TYPE, IntermediateCatchingEvent.RESOURCE_TYPE, ELEMENT_ID, "boundary_1"));

        // A direct child finds it as its parent, a boundary event has to walk one level further up
        assertEquals("1.0", activity.adaptTo(FlowNode.class).getWorkflowVersion().getVersion());
        assertEquals("1.0", boundary.adaptTo(FlowNode.class).getWorkflowVersion().getVersion());
    }

    @Test
    void hasNoOwningVersionWhenStoredOutsideOne()
    {
        final Resource resource = this.context.create().resource("/somewhere/else/task_1", Map.of(
            TYPE, Activity.RESOURCE_TYPE, ELEMENT_ID, "task_1"));

        assertNull(resource.adaptTo(FlowNode.class).getWorkflowVersion());
    }

    @Test
    void listsTheBoundaryEventsOfAnActivity()
    {
        final Resource resource = this.context.create().resource(VERSION_PATH + "/task_1", Map.of(
            TYPE, Activity.RESOURCE_TYPE, ELEMENT_ID, "task_1"));
        this.context.create().resource(VERSION_PATH + "/task_1/timeout", Map.of(
            TYPE, IntermediateCatchingEvent.RESOURCE_TYPE, ELEMENT_ID, "timeout", "catching", true));
        // An outgoing flow is a child too, but it is not a boundary event
        this.context.create().resource(VERSION_PATH + "/task_1/flow_1", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "flow_1", "targetRef", "end_1"));

        final Activity activity = (Activity) resource.adaptTo(FlowNode.class);
        final List<IntermediateCatchingEvent> boundaries = activity.getBoundaryEvents();

        assertEquals(1, boundaries.size());
        assertEquals("timeout", boundaries.get(0).getElementId());
        assertTrue(boundaries.get(0).isCatching());
        // Nesting is the whole of what makes it a boundary event: it reports the activity it watches
        assertEquals("task_1", boundaries.get(0).getActivity().getElementId());
        // The same children, seen as plain nested nodes
        assertEquals(1, activity.getNestedNodes().size());
    }

    @Test
    void exposesTheMessageNameOfMessageFlavoredEvents()
    {
        final Resource plain = this.context.create().resource(VERSION_PATH + "/start_0", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, ELEMENT_ID, "start_0"));
        final Resource message = this.context.create().resource(VERSION_PATH + "/start_m", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, ELEMENT_ID, "start_m", "messageName", "create"));

        assertNull(((Event) plain.adaptTo(FlowNode.class)).getMessageName());
        assertEquals("create", ((Event) message.adaptTo(FlowNode.class)).getMessageName());
    }

    @Test
    void exposesTheHandlerOfServiceActivities()
    {
        final Resource manual = this.context.create().resource(VERSION_PATH + "/task_m", Map.of(
            TYPE, Activity.RESOURCE_TYPE, ELEMENT_ID, "task_m"));
        final Resource service = this.context.create().resource(VERSION_PATH + "/task_s", Map.of(
            TYPE, Activity.RESOURCE_TYPE, ELEMENT_ID, "task_s", "handler", "createEntity"));

        assertNull(((Activity) manual.adaptTo(FlowNode.class)).getHandler());
        assertEquals("createEntity", ((Activity) service.adaptTo(FlowNode.class)).getHandler());
    }

    @Test
    void exposesThePerformersANodeAdmits()
    {
        final Resource unrestricted = this.context.create().resource(VERSION_PATH + "/start_o", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, ELEMENT_ID, "start_o"));
        final Resource restricted = this.context.create().resource(VERSION_PATH + "/start_p", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, ELEMENT_ID, "start_p",
            "performers", new String[] {"iap-administrators", "reviewers"}));

        // A node that names nobody admits nobody — the reading that makes a forgotten declaration safe rather
        // than wide open
        assertEquals(List.of(), unrestricted.adaptTo(FlowNode.class).getPerformers());
        assertEquals(List.of("iap-administrators", "reviewers"), restricted.adaptTo(FlowNode.class).getPerformers());
    }

    @Test
    void exposesWhatReachingAnEndEventMeansToTheHost()
    {
        final Resource plain = this.context.create().resource(VERSION_PATH + "/end_p", Map.of(
            TYPE, EndEvent.RESOURCE_TYPE, ELEMENT_ID, "end_p"));
        final Resource meaningful = this.context.create().resource(VERSION_PATH + "/end_m", Map.of(
            TYPE, EndEvent.RESOURCE_TYPE, ELEMENT_ID, "end_m", "hostStatus", "approved"));

        assertNull(((EndEvent) plain.adaptTo(FlowNode.class)).getHostStatus());
        assertEquals("approved", ((EndEvent) meaningful.adaptTo(FlowNode.class)).getHostStatus());
    }

    @Test
    void distinguishesCatchingFromThrowingEvents()
    {
        final Resource start = this.context.create().resource(VERSION_PATH + "/start_1", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, ELEMENT_ID, "start_1", "catching", true));
        final Resource end = this.context.create().resource(VERSION_PATH + "/end_1", Map.of(
            TYPE, EndEvent.RESOURCE_TYPE, ELEMENT_ID, "end_1", "catching", false));
        final Resource throwing = this.context.create().resource(VERSION_PATH + "/throw_1", Map.of(
            TYPE, IntermediateThrowingEvent.RESOURCE_TYPE, ELEMENT_ID, "throw_1", "catching", false));

        assertTrue(((Event) start.adaptTo(FlowNode.class)).isCatching());
        assertFalse(((Event) end.adaptTo(FlowNode.class)).isCatching());
        assertFalse(((Event) throwing.adaptTo(FlowNode.class)).isCatching());
    }

    @Test
    void adaptsEveryConcreteNodeTypeToItsOwnModel()
    {
        assertEquals(StartEvent.class, this.adaptNode("n1", StartEvent.RESOURCE_TYPE));
        assertEquals(EndEvent.class, this.adaptNode("n2", EndEvent.RESOURCE_TYPE));
        assertEquals(IntermediateCatchingEvent.class,
            this.adaptNode("n3", IntermediateCatchingEvent.RESOURCE_TYPE));
        assertEquals(IntermediateThrowingEvent.class,
            this.adaptNode("n4", IntermediateThrowingEvent.RESOURCE_TYPE));
        assertEquals(Activity.class, this.adaptNode("n5", Activity.RESOURCE_TYPE));
        assertEquals(ExclusiveGateway.class, this.adaptNode("n6", ExclusiveGateway.RESOURCE_TYPE));
        assertEquals(ParallelGateway.class, this.adaptNode("n7", ParallelGateway.RESOURCE_TYPE));
        assertEquals(InclusiveGateway.class, this.adaptNode("n8", InclusiveGateway.RESOURCE_TYPE));
    }

    @Test
    void adaptsThroughEveryAbstractBaseInTheChain()
    {
        // Asking for any of the bases a start event answers for must still yield the start event itself, which is
        // what the two-level adapters declaration buys and what the /libs registry makes resolvable
        final Resource resource = this.context.create().resource(VERSION_PATH + "/start_1", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, ELEMENT_ID, "start_1"));

        assertEquals(StartEvent.class, resource.adaptTo(FlowNode.class).getClass());
        assertEquals(StartEvent.class, resource.adaptTo(Event.class).getClass());
        assertEquals(StartEvent.class, resource.adaptTo(StartEvent.class).getClass());
    }

    private Class<?> adaptNode(final String name, final String resourceType)
    {
        final Resource resource = this.context.create().resource(VERSION_PATH + "/" + name, Map.of(
            TYPE, resourceType, ELEMENT_ID, name));
        return resource.adaptTo(FlowNode.class).getClass();
    }
}

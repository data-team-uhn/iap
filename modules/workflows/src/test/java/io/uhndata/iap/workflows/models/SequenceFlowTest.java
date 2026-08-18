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
 * Unit tests for {@link SequenceFlow}, in particular resolving the node an arc leads to.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SequenceFlowTest
{
    private static final String VERSION_PATH = "/Workflows/timeOff/1.0";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
        this.context.create().resource(VERSION_PATH, TYPE, WorkflowVersion.RESOURCE_TYPE, "version", "1.0");
        this.context.create().resource(VERSION_PATH + "/start_1", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, "elementId", "start_1"));
        this.context.create().resource(VERSION_PATH + "/task_1", Map.of(
            TYPE, Activity.RESOURCE_TYPE, "elementId", "task_1", "label", "Approve the request"));
    }

    @Test
    void exposesFlowProperties()
    {
        final Resource resource = this.context.create().resource(VERSION_PATH + "/start_1/flow_1", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE,
            "elementId", "flow_1",
            "targetRef", "task_1",
            "label", "Approved",
            "isDefault", true));
        // The guard is the cond:condition child the cond:Conditionable supertype brings, the same way a schema
        // item carries the condition saying when it applies
        this.context.create().resource(resource.getPath() + "/cond:condition",
            TYPE, "cond/SingleCondition");
        final SequenceFlow flow = resource.adaptTo(SequenceFlow.class);

        assertNotNull(flow);
        assertEquals("flow_1", flow.getElementId());
        assertEquals("task_1", flow.getTargetRef());
        assertEquals("Approved", flow.getLabel());
        assertNotNull(flow.getCondition());
        assertTrue(flow.isDefault());
    }

    @Test
    void toleratesAnUnlabelledUnconditionalFlow()
    {
        final Resource resource = this.context.create().resource(VERSION_PATH + "/start_1/flow_1", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, "elementId", "flow_1", "targetRef", "task_1"));
        final SequenceFlow flow = resource.adaptTo(SequenceFlow.class);

        assertNull(flow.getLabel());
        assertNull(flow.getCondition());
        assertFalse(flow.isDefault());
    }

    @Test
    void resolvesItsSourceAndTarget()
    {
        final Resource resource = this.context.create().resource(VERSION_PATH + "/start_1/flow_1", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, "elementId", "flow_1", "targetRef", "task_1"));
        final SequenceFlow flow = resource.adaptTo(SequenceFlow.class);

        assertEquals("start_1", flow.getSource().getElementId());
        assertEquals(StartEvent.class, flow.getSource().getClass());
        assertEquals("task_1", flow.getTarget().getElementId());
        assertEquals(Activity.class, flow.getTarget().getClass());
    }

    @Test
    void hasNoTargetWhenItNamesAnUnknownNode()
    {
        final Resource resource = this.context.create().resource(VERSION_PATH + "/start_1/flow_1", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, "elementId", "flow_1", "targetRef", "no_such_node"));

        assertNull(resource.adaptTo(SequenceFlow.class).getTarget());
    }

    @Test
    void hasNoTargetWhenItNamesNoNodeAtAll()
    {
        // targetRef is mandatory in the node type, but an arc that got past that names nowhere to go, which is an
        // absent target rather than something to fail the whole lookup over
        final Resource resource = this.context.create().resource(VERSION_PATH + "/start_1/flow_1", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, "elementId", "flow_1"));

        assertNull(resource.adaptTo(SequenceFlow.class).getTarget());
    }

    @Test
    void hasNoSourceWhenItsParentIsOfAnAbstractType()
    {
        // wf:FlowNode is abstract, so a node carrying it is of no kind; it matches no model and would otherwise be
        // handed back as whichever implementation sorts first
        this.context.create().resource(VERSION_PATH + "/abstract_1", Map.of(
            TYPE, FlowNode.RESOURCE_TYPE, "elementId", "abstract_1"));
        final Resource resource = this.context.create().resource(VERSION_PATH + "/abstract_1/flow_1", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, "elementId", "flow_1", "targetRef", "task_1"));
        final SequenceFlow flow = resource.adaptTo(SequenceFlow.class);

        assertNull(flow.getSource());
        assertNull(flow.getTarget());
    }

    @Test
    void hasNoSourceOrTargetWhenStoredOutsideAGraph()
    {
        final Resource orphan = this.context.create().resource("/loose/flow_1", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, "elementId", "flow_1", "targetRef", "task_1"));
        final SequenceFlow flow = orphan.adaptTo(SequenceFlow.class);

        // The parent exists but is not a flow node, so there is nothing to walk up from
        assertNull(flow.getSource());
        assertNull(flow.getTarget());
    }

    @Test
    void hasNoTargetWhenItsSourceIsOutsideAVersion()
    {
        this.context.create().resource("/elsewhere/task_9", Map.of(
            TYPE, Activity.RESOURCE_TYPE, "elementId", "task_9"));
        final Resource resource = this.context.create().resource("/elsewhere/task_9/flow_1", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, "elementId", "flow_1", "targetRef", "task_1"));
        final SequenceFlow flow = resource.adaptTo(SequenceFlow.class);

        assertNotNull(flow.getSource());
        assertNull(flow.getTarget());
    }
}

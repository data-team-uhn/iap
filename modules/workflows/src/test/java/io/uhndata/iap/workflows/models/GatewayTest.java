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
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link Gateway} and its concrete subtypes.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class GatewayTest
{
    private static final String GATEWAY_PATH = "/Workflows/timeOff/1.0/gateway_1";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
        this.context.create().resource("/Workflows/timeOff/1.0", TYPE, WorkflowVersion.RESOURCE_TYPE,
            "version", "1.0");
    }

    @Test
    void findsTheDefaultOutgoingFlow()
    {
        final Resource resource = this.context.create().resource(GATEWAY_PATH, Map.of(
            TYPE, ExclusiveGateway.RESOURCE_TYPE, "elementId", "gateway_1"));
        this.context.create().resource(GATEWAY_PATH + "/flow_approved", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, "elementId", "flow_approved", "targetRef", "end_1"));
        this.context.create().resource(GATEWAY_PATH + "/flow_approved/cond:condition",
            TYPE, "cond/SingleCondition");
        this.context.create().resource(GATEWAY_PATH + "/flow_otherwise", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, "elementId", "flow_otherwise", "targetRef", "task_2",
            "isDefault", true));

        final Gateway gateway = (Gateway) resource.adaptTo(FlowNode.class);

        assertEquals("flow_otherwise", gateway.getDefaultFlow().getElementId());
    }

    @Test
    void hasNoDefaultFlowWhenNoneIsMarked()
    {
        final Resource resource = this.context.create().resource(GATEWAY_PATH, Map.of(
            TYPE, InclusiveGateway.RESOURCE_TYPE, "elementId", "gateway_1"));
        this.context.create().resource(GATEWAY_PATH + "/flow_1", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, "elementId", "flow_1", "targetRef", "end_1"));

        assertNull(((Gateway) resource.adaptTo(FlowNode.class)).getDefaultFlow());
    }

    @Test
    void hasNoDefaultFlowWhenItHasNoOutgoingFlowsAtAll()
    {
        final Resource resource = this.context.create().resource(GATEWAY_PATH, Map.of(
            TYPE, ParallelGateway.RESOURCE_TYPE, "elementId", "gateway_1"));

        assertNull(((Gateway) resource.adaptTo(FlowNode.class)).getDefaultFlow());
    }

    @Test
    void adaptsThroughTheGatewayBase()
    {
        final Resource resource = this.context.create().resource(GATEWAY_PATH, Map.of(
            TYPE, ParallelGateway.RESOURCE_TYPE, "elementId", "gateway_1"));

        assertEquals(ParallelGateway.class, resource.adaptTo(Gateway.class).getClass());
    }
}

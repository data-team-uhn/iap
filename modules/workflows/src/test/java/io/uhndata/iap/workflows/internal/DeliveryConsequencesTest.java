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

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.notifications.api.NotificationService;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.models.FlowNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link DeliveryConsequences}: what a delivery leaves for its history.
 *
 * @version $Id$
 * @since 0.1.0
 */
class DeliveryConsequencesTest
{
    private static final WorkflowEvent EVENT = new WorkflowEvent("create", Map.of());

    private static final Actor JDOE = Actor.of("jdoe");

    private final DeliveryConsequences consequences =
        new DeliveryConsequences(new PendingNotifications(Mockito.mock(NotificationService.class)));

    @Test
    void namesTheFirstInstanceTheDeliveryStarted()
    {
        this.consequences.noteStartedInstance("/Requests/one/wf:instances/first");
        this.consequences.noteStartedInstance("/Requests/one/wf:instances/second");

        assertEquals("/Requests/one/wf:instances/first",
            this.consequences.recordedAsSystemWorkflow(JDOE, EVENT, Mockito.mock(FlowNode.class))
                .getWorkflowInstance());
    }

    @Test
    void namesNoVersionForAStartEventStoredOutsideOne()
    {
        assertNull(this.consequences.recordedAsSystemWorkflow(JDOE, EVENT, Mockito.mock(FlowNode.class))
            .getWorkflowVersion());
    }
}

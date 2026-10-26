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

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.EndEvent;
import io.uhndata.iap.workflows.models.Gateway;
import io.uhndata.iap.workflows.models.StartEvent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FlowRouting}, asked directly about the nodes the engine settles before it asks.
 *
 * @version $Id$
 * @since 0.1.0
 */
class FlowRoutingTest
{
    private final FlowRouting routing = new FlowRouting(Mockito.mock(ConditionEvaluator.class));

    @Test
    void passesThroughAServiceTaskButStopsAtAUserTask()
    {
        final Activity serviceTask = Mockito.mock(Activity.class);
        Mockito.when(serviceTask.getHandler()).thenReturn("createEntity");
        final Activity userTask = Mockito.mock(Activity.class);

        assertTrue(this.routing.canPassThrough(serviceTask));
        assertFalse(this.routing.canPassThrough(userTask));
    }

    @Test
    void stopsAtAnEndEvent()
    {
        assertFalse(this.routing.canPassThrough(Mockito.mock(EndEvent.class)));
    }

    @Test
    void passesThroughAStartEventAndAGateway()
    {
        assertTrue(this.routing.canPassThrough(Mockito.mock(StartEvent.class)));
        assertTrue(this.routing.canPassThrough(Mockito.mock(Gateway.class)));
    }
}

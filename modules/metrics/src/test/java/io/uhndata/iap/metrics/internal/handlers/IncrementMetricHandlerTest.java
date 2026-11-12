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
package io.uhndata.iap.metrics.internal.handlers;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.uhndata.iap.metrics.api.Metric;
import io.uhndata.iap.metrics.api.MetricsManager;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link IncrementMetricHandler}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class IncrementMetricHandlerTest
{
    private static final String METRIC = "requestsSubmitted";

    private final MetricsManager metrics = Mockito.mock(MetricsManager.class);

    private final Metric metric = Mockito.mock(Metric.class);

    private final Activity activity = Mockito.mock(Activity.class);

    private final WorkflowTaskContext context = Mockito.mock(WorkflowTaskContext.class);

    private final IncrementMetricHandler handler = new IncrementMetricHandler(this.metrics);

    @BeforeEach
    void setUp()
    {
        Mockito.when(this.context.getActivity()).thenReturn(this.activity);
        Mockito.when(this.activity.getPath()).thenReturn("/Workflows/request/v1/count");
        Mockito.when(this.activity.get("metric")).thenReturn(METRIC);
        Mockito.when(this.metrics.getMetric(METRIC)).thenReturn(Optional.of(this.metric));
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("incrementMetric", this.handler.getName());
    }

    @Test
    void countsOneOnceTheDeliveryHasCommitted() throws Exception
    {
        this.handler.execute(this.context);
        Mockito.verifyNoInteractions(this.metric);

        workLeftForAfterTheCommit().run();

        Mockito.verify(this.metric).increment(1);
    }

    @Test
    void countsTheAmountTheActivityGives() throws Exception
    {
        Mockito.when(this.activity.get("amount")).thenReturn(-2L);

        this.handler.execute(this.context);
        workLeftForAfterTheCommit().run();

        Mockito.verify(this.metric).increment(-2);
    }

    @Test
    void refusesAnActivityNamingNoMetric()
    {
        Mockito.when(this.activity.get("metric")).thenReturn(null);

        assertThrows(WorkflowDefinitionException.class, () -> this.handler.execute(this.context));
    }

    @Test
    void refusesAnActivityNamingABlankMetric()
    {
        Mockito.when(this.activity.get("metric")).thenReturn(" ");

        assertThrows(WorkflowDefinitionException.class, () -> this.handler.execute(this.context));
    }

    @Test
    void refusesAMetricThatDoesNotExist()
    {
        Mockito.when(this.metrics.getMetric(METRIC)).thenReturn(Optional.empty());

        assertThrows(WorkflowDefinitionException.class, () -> this.handler.execute(this.context));
        Mockito.verify(this.context, Mockito.never()).afterCommit(Mockito.any());
    }

    @Test
    void refusesAnAmountThatIsNotAWholeNumber()
    {
        Mockito.when(this.activity.get("amount")).thenReturn("two");

        assertThrows(WorkflowDefinitionException.class, () -> this.handler.execute(this.context));
        Mockito.verify(this.context, Mockito.never()).afterCommit(Mockito.any());
    }

    private Runnable workLeftForAfterTheCommit()
    {
        final ArgumentCaptor<Runnable> work = ArgumentCaptor.forClass(Runnable.class);
        Mockito.verify(this.context).afterCommit(work.capture());
        return work.getValue();
    }
}

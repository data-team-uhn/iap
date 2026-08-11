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
package io.uhndata.iap.demos.timeoff.internal;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TimeOffBudgetHandler}: the answer it gives, and its refusal to fall over on
 * configuration somebody mistyped.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class TimeOffBudgetHandlerTest
{
    private static final String REQUESTER = "demo-requester";

    private static final String REQUEST_PATH = "/Submissions/aLongWeekend";

    private final SlingContext context = new SlingContext();

    @Test
    void namesItselfSoAnActivityCanPointAtIt()
    {
        assertEquals("checkTimeOffBudget", handler(new String[0], 0).getName());
    }

    @Test
    void recordsTheConfiguredAnswerOnTheRequest() throws Exception
    {
        final Resource request = request();
        final WorkflowTaskContext task = taskFor(REQUESTER, request);

        handler(new String[] { REQUESTER + "=12" }, 0).execute(task);

        final ValueMap recorded = request.adaptTo(ValueMap.class);
        assertEquals(12L, recorded.get(TimeOffBudgetHandler.REMAINING_DAYS, Long.class));
        // Whose budget it is, recorded beside the number: an approver reading it days later has no other way
        // of telling that it is the requester's and not their own.
        assertEquals(REQUESTER, recorded.get(TimeOffBudgetHandler.CHECKED_FOR, String.class));
        verify(task).setVariable(TimeOffBudgetHandler.REMAINING_DAYS, 12L);
    }

    @Test
    void fallsBackToTheDefaultForSomebodyItKnowsNothingAbout() throws Exception
    {
        final Resource request = request();

        handler(new String[] { REQUESTER + "=12" }, 3).execute(taskFor("a-new-hire", request));

        assertEquals(3L, request.adaptTo(ValueMap.class).get(TimeOffBudgetHandler.REMAINING_DAYS, Long.class));
    }

    @Test
    void ignoresEntriesThatDoNotNameAPersonAndANumber() throws Exception
    {
        final Resource request = request();
        // Neither of these says anything usable, so the default stands rather than the demo refusing to start
        final String[] mistyped = { "no-equals-sign", REQUESTER + "=a fortnight" };

        handler(mistyped, 7).execute(taskFor(REQUESTER, request));

        assertEquals(7L, request.adaptTo(ValueMap.class).get(TimeOffBudgetHandler.REMAINING_DAYS, Long.class));
    }

    @Test
    void letsTheLastEntryWinWhenAPersonIsNamedTwice() throws Exception
    {
        final Resource request = request();

        handler(new String[] { REQUESTER + "=5", REQUESTER + "=12" }, 0).execute(taskFor(REQUESTER, request));

        assertEquals(12L, request.adaptTo(ValueMap.class).get(TimeOffBudgetHandler.REMAINING_DAYS, Long.class));
    }

    private TimeOffBudgetHandler handler(final String[] budgets, final int defaultRemainingDays)
    {
        final TimeOffBudgetConfiguration configuration = Mockito.mock(TimeOffBudgetConfiguration.class);
        when(configuration.budgets()).thenReturn(budgets);
        when(configuration.defaultRemainingDays()).thenReturn(defaultRemainingDays);
        return new TimeOffBudgetHandler(configuration);
    }

    private Resource request()
    {
        return this.context.create().resource(REQUEST_PATH);
    }

    private WorkflowTaskContext taskFor(final String actor, final Resource request)
    {
        final WorkflowTaskContext task = Mockito.mock(WorkflowTaskContext.class);
        when(task.getActor()).thenReturn(actor);
        when(task.getTarget()).thenReturn(request);
        return task;
    }
}

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

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.errortracking.api.ErrorLoggerService;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TimeOffBudgetHandler}: the answer it gives, and what it does with mistyped
 * configuration.
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

    private final ErrorLoggerService recorder = Mockito.mock(ErrorLoggerService.class);

    @BeforeEach
    void setUp()
    {
        ErrorLogger.setService(this.recorder);
    }

    @AfterEach
    void tearDown()
    {
        ErrorLogger.unsetService(this.recorder);
    }

    @Test
    void namesItselfSoAnActivityCanPointAtIt()
    {
        assertEquals("checkTimeOffBudget", handler(new String[0], 0).getName());
    }

    @Test
    void recordsTheConfiguredAnswerOnTheRequest() throws Exception
    {
        final Resource request = request();
        handler(new String[] { REQUESTER + "=12" }, 0).execute(taskFor(REQUESTER, request));

        assertEquals(12L, remainingOn(request));
    }

    @Test
    void fallsBackToTheDefaultForSomebodyItKnowsNothingAbout() throws Exception
    {
        final Resource request = request();

        handler(new String[] { REQUESTER + "=12" }, 3).execute(taskFor("a-new-hire", request));

        assertEquals(3L, remainingOn(request));
    }

    @Test
    void ignoresEntriesThatDoNotNameAPersonAndANumber() throws Exception
    {
        final Resource request = request();
        final String[] mistyped = { "no-equals-sign", REQUESTER + "=a fortnight" };

        handler(mistyped, 7).execute(taskFor(REQUESTER, request));

        assertEquals(7L, remainingOn(request));
    }

    @Test
    void recordsEachEntryItIgnores()
    {
        handler(new String[] { "no-equals-sign", REQUESTER + "=a fortnight", REQUESTER + "=12" }, 0);

        final ArgumentCaptor<ErrorContext> contexts = ArgumentCaptor.forClass(ErrorContext.class);
        verify(this.recorder, Mockito.times(2)).logProblem(Mockito.anyString(), contexts.capture());
        assertEquals(List.of("no-equals-sign", REQUESTER + "=a fortnight"),
            contexts.getAllValues().stream().map(recorded -> recorded.getDetails().get("entry")).toList());
    }

    @Test
    void letsTheLastEntryWinWhenAPersonIsNamedTwice() throws Exception
    {
        final Resource request = request();

        handler(new String[] { REQUESTER + "=5", REQUESTER + "=12" }, 0).execute(taskFor(REQUESTER, request));

        assertEquals(12L, remainingOn(request));
    }

    private TimeOffBudgetHandler handler(final String[] budgets, final int defaultRemainingDays)
    {
        final TimeOffBudgetConfiguration configuration = Mockito.mock(TimeOffBudgetConfiguration.class);
        when(configuration.budgets()).thenReturn(budgets);
        when(configuration.defaultRemainingDays()).thenReturn(defaultRemainingDays);
        return new TimeOffBudgetHandler(configuration);
    }

    /** The balance recorded on a request, under the name the budget rule and the nightly sweep read it by. */
    private static Long remainingOn(final Resource request)
    {
        return request.getValueMap().get("budgetRemainingDays", Long.class);
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

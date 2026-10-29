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
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.uhndata.iap.demos.timeoff.internal.TimeOffQuestions.DURATION_QUESTION;
import static io.uhndata.iap.demos.timeoff.internal.TimeOffQuestions.END_DATE_QUESTION;
import static io.uhndata.iap.demos.timeoff.internal.TimeOffQuestions.START_DATE_QUESTION;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link TimeOffDatesEvaluator}: which requests it holds back, and the answer it points at.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class TimeOffDatesEvaluatorTest
{
    private static final String REQUEST_PATH = "/Submissions/aRequest";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final TimeOffDatesEvaluator evaluator = new TimeOffDatesEvaluator();

    private TimeOffRequests requests;

    @BeforeEach
    void setUp()
    {
        this.requests = TimeOffRequests.installIn(this.context);
    }

    @Test
    void pointsAtTheLastDayWhenItComesBeforeTheFirst()
    {
        final Resource request = spanRequest("2026-11-06", "2026-11-02");

        assertEquals(List.of(REQUEST_PATH + "/endDate"),
            this.evaluator.evaluate(request).stream().map(Resource::getPath).toList());
    }

    @Test
    void passesASpanTheRightWayRound()
    {
        assertEquals(List.of(), this.evaluator.evaluate(spanRequest("2026-11-02", "2026-11-06")));
    }

    @Test
    void passesASpanOfOneDay()
    {
        assertEquals(List.of(), this.evaluator.evaluate(spanRequest("2026-11-02", "2026-11-02")));
    }

    @Test
    void passesASpanStillMissingADay()
    {
        final Resource request = budgetedRequest();
        this.requests.answer(request, DURATION_QUESTION, "multiple-days");
        this.requests.answer(request, START_DATE_QUESTION, "2026-11-06");

        assertEquals(List.of(), this.evaluator.evaluate(request));
    }

    // The last day is no longer asked, so what it still holds does not count
    @Test
    void passesBackwardsDaysLeftOverOnceTheRequestIsForOneDay()
    {
        final Resource request = spanRequest("2026-11-06", "2026-11-02");
        this.requests.answer(request, DURATION_QUESTION, "full-day");

        assertEquals(List.of(), this.evaluator.evaluate(request));
    }

    @Test
    void passesASubmissionThatIsNotATimeOffRequest()
    {
        final Resource request = this.requests.request(REQUEST_PATH, Map.of());
        this.requests.answer(request, DURATION_QUESTION, "multiple-days");
        this.requests.answer(request, START_DATE_QUESTION, "2026-11-06");
        this.requests.answer(request, END_DATE_QUESTION, "2026-11-02");

        assertEquals(List.of(), this.evaluator.evaluate(request));
    }

    private Resource spanRequest(final String firstDay, final String lastDay)
    {
        final Resource request = budgetedRequest();
        this.requests.answer(request, DURATION_QUESTION, "multiple-days");
        this.requests.answer(request, START_DATE_QUESTION, firstDay);
        this.requests.answer(request, END_DATE_QUESTION, lastDay);
        return request;
    }

    private Resource budgetedRequest()
    {
        return this.requests.request(REQUEST_PATH, Map.of(TimeOffBudgetHandler.REMAINING_DAYS_PROPERTY, 10L));
    }
}

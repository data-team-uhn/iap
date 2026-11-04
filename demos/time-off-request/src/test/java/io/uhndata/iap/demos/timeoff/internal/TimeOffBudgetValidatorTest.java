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

import java.util.Map;
import java.util.Objects;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.submissions.models.Submission;

import static io.uhndata.iap.demos.timeoff.internal.TimeOffQuestions.DURATION_QUESTION;
import static io.uhndata.iap.demos.timeoff.internal.TimeOffQuestions.END_DATE_QUESTION;
import static io.uhndata.iap.demos.timeoff.internal.TimeOffQuestions.START_DATE_QUESTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link TimeOffBudgetValidator}: what it counts as a day, and the difference between a request it
 * refuses and one it cannot yet judge.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class TimeOffBudgetValidatorTest
{
    private static final String REQUESTER = "demo-requester";

    private static final String SEVERAL_DAYS = "multiple-days";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final TimeOffBudgetValidator validator = new TimeOffBudgetValidator();

    private TimeOffRequests requests;

    private int created;

    @BeforeEach
    void setUp()
    {
        this.requests = TimeOffRequests.installIn(this.context);
    }

    @Test
    void countsAHalfDayAsHalfADay()
    {
        assertNull(this.validate(1, Map.of(DURATION_QUESTION, "half-day")));
    }

    @Test
    void countsAFullDayAsOne()
    {
        assertNull(this.validate(1, Map.of(DURATION_QUESTION, "full-day")));
        assertNotNull(this.validate(0, Map.of(DURATION_QUESTION, "full-day")));
    }

    // Both ends count: away Monday to Friday is five days, not four
    @Test
    void countsASpanInclusiveOfBothEnds()
    {
        final Map<String, String> week = Map.of(
            DURATION_QUESTION, SEVERAL_DAYS, START_DATE_QUESTION, "2026-11-02", END_DATE_QUESTION, "2026-11-06");

        assertNull(this.validate(5, week));
        assertNotNull(this.validate(4, week));
    }

    @Test
    void refusesWithBothNumbers()
    {
        final String refusal = this.validate(2, Map.of(
            DURATION_QUESTION, SEVERAL_DAYS, START_DATE_QUESTION, "2026-11-02", END_DATE_QUESTION, "2026-11-06"));

        assertEquals("This asks for more time off than you have left. Requested: 5. Remaining: 2.", refusal);
    }

    @Test
    void writesAHalfDayAsAHalf()
    {
        assertEquals("This asks for more time off than you have left. Requested: 0.5. Remaining: 0.",
            this.validate(0, Map.of(DURATION_QUESTION, "half-day")));
    }

    @Test
    void acceptsARequestWithNothingAnswered()
    {
        assertNull(this.validate(0, Map.of()));
    }

    @Test
    void acceptsASpanWithoutItsDays()
    {
        assertNull(this.validate(0, Map.of(DURATION_QUESTION, SEVERAL_DAYS)));
    }

    @Test
    void acceptsASpanWithoutItsLastDay()
    {
        assertNull(this.validate(0, Map.of(DURATION_QUESTION, SEVERAL_DAYS, START_DATE_QUESTION, "2026-11-02")));
    }

    @Test
    void acceptsADurationItCannotCount()
    {
        assertNull(this.validate(0, Map.of(DURATION_QUESTION, "a fortnight")));
    }

    @Test
    void acceptsDatesItCannotRead()
    {
        assertNull(this.validate(0, Map.of(
            DURATION_QUESTION, SEVERAL_DAYS, START_DATE_QUESTION, "the second", END_DATE_QUESTION, "2026-11-06")));
    }

    // Holding such a request back is the dates evaluator's job, which keeps every save along the way possible
    @Test
    void acceptsASpanGivenBackwards()
    {
        assertNull(this.validate(0, Map.of(
            DURATION_QUESTION, SEVERAL_DAYS, START_DATE_QUESTION, "2026-11-06", END_DATE_QUESTION, "2026-11-02")));
    }

    @Test
    void countsNoDaysLeftOverFromASpanOnceTheRequestIsForOneDay()
    {
        assertNull(this.validate(1, Map.of(
            DURATION_QUESTION, "full-day", START_DATE_QUESTION, "2026-11-02", END_DATE_QUESTION, "2026-12-31")));
    }

    @Test
    void leavesASubmissionWithoutABalanceAlone()
    {
        final Resource request = this.requests.request("/Submissions/somethingElse", Map.of());
        this.requests.answer(request, DURATION_QUESTION, "full-day");

        assertNull(this.validator.validate(Objects.requireNonNull(request.adaptTo(Submission.class)), REQUESTER));
    }

    /**
     * Validates a new request against a recorded balance. The answers are keyed by question path within the schema
     * version.
     */
    private String validate(final long remaining, final Map<String, String> answers)
    {
        // A request of its own each time, so that no answer carries over from an earlier case
        this.created++;
        final Resource request = this.requests.request("/Submissions/request" + this.created,
            Map.of(TimeOffBudgetHandler.REMAINING_DAYS_PROPERTY, remaining));
        answers.forEach((question, value) -> this.requests.answer(request, question, value));
        return this.validator.validate(Objects.requireNonNull(request.adaptTo(Submission.class)), REQUESTER);
    }
}

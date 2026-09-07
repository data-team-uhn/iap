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

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.AnswerSet;
import io.uhndata.iap.submissions.models.Submission;

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
    private static final String TYPE = "sling:resourceType";

    private static final String REQUESTER = "demo-requester";

    private static final String VERSION_PATH = "/Schemas/timeOffRequest/v1";

    private static final String REQUEST_PATH = "/Submissions/aLongWeekend";

    // JCR-backed: an answer points at its question with a real REFERENCE, resolved by identifier
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final TimeOffBudgetValidator validator = new TimeOffBudgetValidator();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Submission.class,
            Answer.class, AnswerSet.class, Question.class);
        for (final String name : new String[] {"duration", "startDate", "endDate"}) {
            this.context.create().resource(VERSION_PATH + "/" + name, Map.of(
                TYPE, Question.RESOURCE_TYPE, "text", name));
        }
    }

    @Test
    void countsAHalfDayAsHalfADay()
    {
        assertNull(this.validate(1, Map.of("duration", "half-day")));
    }

    @Test
    void countsAFullDayAsOne()
    {
        assertNull(this.validate(1, Map.of("duration", "full-day")));
        assertNotNull(this.validate(0, Map.of("duration", "full-day")));
    }

    // Both ends count: away Monday to Friday is five days, not four
    @Test
    void countsASpanInclusiveOfBothEnds()
    {
        final Map<String, String> week = Map.of(
            "duration", "multiple-days", "startDate", "2026-11-02", "endDate", "2026-11-06");

        assertNull(this.validate(5, week));
        assertNotNull(this.validate(4, week));
    }

    @Test
    void refusesWithBothNumbers()
    {
        final String refusal = this.validate(2, Map.of(
            "duration", "multiple-days", "startDate", "2026-11-02", "endDate", "2026-11-06"));

        assertEquals("This asks for more time off than you have left. Requested: 5. Remaining: 2.", refusal);
    }

    // A day count is written without the trailing zero a whole number would otherwise carry
    @Test
    void writesAHalfDayAsAHalf()
    {
        assertEquals("This asks for more time off than you have left. Requested: 0.5. Remaining: 0.",
            this.validate(0, Map.of("duration", "half-day")));
    }

    // Answers arrive one at a time, so a request is half-answered for most of its life and that is not an error
    @Test
    void acceptsWhatItCannotYetJudge()
    {
        assertNull(this.validate(0, Map.of()), "nothing answered");
        assertNull(this.validate(0, Map.of("duration", "multiple-days")), "no dates yet");
        assertNull(this.validate(0, Map.of("duration", "multiple-days", "startDate", "2026-11-02")), "no end yet");
        assertNull(this.validate(0, Map.of("duration", "a fortnight")), "a duration it cannot count");
    }

    @Test
    void acceptsDatesItCannotRead()
    {
        assertNull(this.validate(0, Map.of(
            "duration", "multiple-days", "startDate", "the second", "endDate", "2026-11-06")));
    }

    // A return before the departure is a request to correct rather than one to price
    @Test
    void acceptsAReturnBeforeTheDeparture()
    {
        assertNull(this.validate(0, Map.of(
            "duration", "multiple-days", "startDate", "2026-11-06", "endDate", "2026-11-02")));
    }

    // The balance is looked up by the workflow's first step; without it there is nothing to compare against
    @Test
    void acceptsWhenNoBalanceHasBeenRecorded()
    {
        final Resource request = this.context.create().resource(REQUEST_PATH, Map.of(
            TYPE, Submission.RESOURCE_TYPE, "title", "A long weekend"));
        this.answer(request, "duration", "full-day");

        assertNull(this.validator.validate(request.adaptTo(Submission.class), REQUESTER));
    }

    // An answer whose question is gone, or which holds nothing, says nothing about the duration
    @Test
    void passesOverAnswersThatNameNothing()
    {
        final Resource request = this.context.create().resource(REQUEST_PATH, Map.of(
            TYPE, Submission.RESOURCE_TYPE, "title", "A long weekend",
            TimeOffBudgetHandler.REMAINING_DAYS, 0L));
        this.context.create().resource(this.answers(request).getPath() + "/orphan", Map.of(
            TYPE, Answer.RESOURCE_TYPE, "value", new String[] {"full-day"}));
        this.reference(this.context.create().resource(this.answers(request).getPath() + "/empty", Map.of(
            TYPE, Answer.RESOURCE_TYPE, "value", new String[0])), VERSION_PATH + "/duration");

        assertNull(this.validator.validate(request.adaptTo(Submission.class), REQUESTER));
    }

    /**
     * Validates a request carrying the given answers against a recorded balance.
     */
    private String validate(final long remaining, final Map<String, String> answers)
    {
        final Resource request = this.context.create().resource(REQUEST_PATH, Map.of(
            TYPE, Submission.RESOURCE_TYPE, "title", "A long weekend",
            TimeOffBudgetHandler.REMAINING_DAYS, remaining));
        answers.forEach((question, value) -> this.answer(request, question, value));
        return this.validator.validate(request.adaptTo(Submission.class), REQUESTER);
    }

    private void answer(final Resource request, final String question, final String value)
    {
        final Resource answer = this.context.create().resource(
            this.answers(request).getPath() + "/" + question,
            Map.of(TYPE, Answer.RESOURCE_TYPE, "value", new String[] {value}));
        this.reference(answer, VERSION_PATH + "/" + question);
    }

    /** The set a request's answers live in, created the first time one is filed. */
    private Resource answers(final Resource request)
    {
        final String path = request.getPath() + "/answers";
        final Resource existing = this.context.resourceResolver().getResource(path);
        return existing != null ? existing
            : this.context.create().resource(path, Map.of(TYPE, AnswerSet.RESOURCE_TYPE));
    }

    /** Points an answer at its question the way the save handler does: a real REFERENCE, not a path. */
    private void reference(final Resource answer, final String questionPath)
    {
        try {
            final Node source = Objects.requireNonNull(answer.adaptTo(Node.class));
            source.setProperty("question", Objects.requireNonNull(
                this.context.resourceResolver().getResource(questionPath)).adaptTo(Node.class));
            this.context.resourceResolver().commit();
        } catch (final RepositoryException | PersistenceException e) {
            throw new IllegalStateException(e);
        }
    }
}

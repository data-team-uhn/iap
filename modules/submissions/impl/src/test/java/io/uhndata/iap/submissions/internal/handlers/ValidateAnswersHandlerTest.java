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
package io.uhndata.iap.submissions.internal.handlers;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.AnswerValidator;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link ValidateAnswersHandler}: that every registered rule is asked, and that the first objection
 * is what the submitter is told.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ValidateAnswersHandlerTest
{
    private static final String SUBMISSION_PATH = "/Submissions/ab/cd/ef/aRequest";

    private static final String REQUESTER = "demo-requester";

    private final SlingContext context = new SlingContext();

    private Resource submission;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Submission.class);
        this.submission = this.context.create().resource(SUBMISSION_PATH, Map.of(
            "sling:resourceType", Submission.RESOURCE_TYPE, "title", "A long weekend",
            "createdBy", REQUESTER, "tags", new String[] {"draft"}));
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(ValidateAnswersHandler.HANDLER_NAME, new ValidateAnswersHandler().getName());
    }

    // Nothing registered is the ordinary case for a deployment with no rules of its own
    @Test
    void acceptsWhenNothingIsRegistered()
    {
        final ValidateAnswersHandler handler = handlerWith();

        assertDoesNotThrow(() -> handler.execute(savingTo(this.submission)));
    }

    @Test
    void refusesToRunOnAnythingButASubmission()
    {
        final Resource schema = this.context.create().resource("/Schemas/timeOffRequest",
            "sling:resourceType", "sch/Schema");
        final ValidateAnswersHandler handler = handlerWith(RecordingValidator.accepting());

        assertThrows(WorkflowDefinitionException.class, () -> handler.execute(savingTo(schema)));
    }

    @Test
    void acceptsWhenEveryRuleIsContent()
    {
        final ValidateAnswersHandler handler =
            handlerWith(RecordingValidator.accepting(), RecordingValidator.accepting());

        assertDoesNotThrow(() -> handler.execute(savingTo(this.submission)));
    }

    @Test
    void refusesWithTheReasonTheRuleGave()
    {
        final ValidateAnswersHandler handler =
            handlerWith(RecordingValidator.accepting(), RecordingValidator.refusing("You have 2 days left"));

        final InvalidPayloadException refusal = assertThrows(InvalidPayloadException.class,
            () -> handler.execute(savingTo(this.submission)));

        assertEquals("You have 2 days left", refusal.getMessage());
    }

    // Which rule objects first is not promised, only that the others are not asked once one has
    @Test
    void stopsAtTheFirstObjection()
    {
        final RecordingValidator first = RecordingValidator.refusing("first");
        final RecordingValidator second = RecordingValidator.refusing("second");
        final ValidateAnswersHandler handler = handlerWith(first, second);

        final InvalidPayloadException refusal = assertThrows(InvalidPayloadException.class,
            () -> handler.execute(savingTo(this.submission)));

        final RecordingValidator asked = first.timesAsked > 0 ? first : second;
        assertEquals(1, first.timesAsked + second.timesAsked);
        assertEquals(asked.refusal, refusal.getMessage());
    }

    @Test
    void handsEachRuleTheSubmissionAndWhoIsSaving()
    {
        final RecordingValidator validator = RecordingValidator.accepting();
        final ValidateAnswersHandler handler = handlerWith(validator);

        assertDoesNotThrow(() -> handler.execute(savingTo(this.submission)));

        assertEquals("A long weekend", validator.askedAbout.getTitle());
        assertEquals(REQUESTER, validator.askedBy);
    }

    private static ValidateAnswersHandler handlerWith(final AnswerValidator... validators)
    {
        final ValidateAnswersHandler handler = new ValidateAnswersHandler();
        try {
            // The house idiom for a component under unit test: DS metadata only exists in the packaged bundle, so
            // the references are set by reflection rather than by registerInjectActivateService
            final Field field = ValidateAnswersHandler.class.getDeclaredField("validators");
            field.setAccessible(true);
            field.set(handler, List.of(validators));
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return handler;
    }

    private TaskContext savingTo(final Resource target)
    {
        return new TaskContext(target, REQUESTER, Map.of());
    }

    /** A rule that gives a fixed answer, and remembers what it was asked. */
    private static final class RecordingValidator implements AnswerValidator
    {
        private final String refusal;

        private int timesAsked;

        private Submission askedAbout;

        private String askedBy;

        private RecordingValidator(final String refusal)
        {
            this.refusal = refusal;
        }

        static RecordingValidator accepting()
        {
            return new RecordingValidator(null);
        }

        static RecordingValidator refusing(final String reason)
        {
            return new RecordingValidator(reason);
        }

        @Override
        public String validate(final Submission submission, final String actor)
        {
            this.timesAsked++;
            this.askedAbout = submission;
            this.askedBy = actor;
            return this.refusal;
        }

    }
}

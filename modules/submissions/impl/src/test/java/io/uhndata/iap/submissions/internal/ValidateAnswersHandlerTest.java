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
package io.uhndata.iap.submissions.internal;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
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
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private final ValidateAnswersHandler handler = new ValidateAnswersHandler();

    private Resource target;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Submission.class);
        this.target = this.context.create().resource(SUBMISSION_PATH, Map.of(
            "sling:resourceType", Submission.RESOURCE_TYPE, "title", "A long weekend",
            "createdBy", REQUESTER, "tags", new String[] {"draft"}));
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(ValidateAnswersHandler.NAME, this.handler.getName());
    }

    // Nothing registered is the ordinary case for a deployment with no rules of its own
    @Test
    void acceptsWhenNothingIsRegistered()
    {
        assertDoesNotThrow(() -> this.handler.execute(context()));
    }

    @Test
    void acceptsWhenEveryRuleIsContent()
    {
        this.register(accepting(), accepting());

        assertDoesNotThrow(() -> this.handler.execute(context()));
    }

    // The refusal is an InvalidPayloadException on purpose: that is what the servlet answers as a 400 carrying the
    // message, which the editor puts on the answer the submitter just gave
    @Test
    void refusesWithTheReasonTheRuleGave()
    {
        this.register(accepting(), refusing("You have 2 days left"));

        final InvalidPayloadException refusal = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context()));

        assertEquals("You have 2 days left", refusal.getMessage());
    }

    @Test
    void stopsAtTheFirstObjection()
    {
        final boolean[] asked = { false };
        this.register(refusing("first"), (submission, actor) -> {
            asked[0] = true;
            return "second";
        });

        final InvalidPayloadException refusal = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context()));

        assertEquals("first", refusal.getMessage());
        assertFalse(asked[0], "a rule after the one that objected was still asked");
    }

    @Test
    void handsEachRuleTheSubmissionAndWhoIsSaving()
    {
        final String[] seen = new String[2];
        this.register((submission, actor) -> {
            seen[0] = submission.getTitle();
            seen[1] = actor;
            return null;
        });

        assertDoesNotThrow(() -> this.handler.execute(context()));

        assertEquals("A long weekend", seen[0]);
        assertEquals(REQUESTER, seen[1]);
    }

    private AnswerValidator accepting()
    {
        return (submission, actor) -> null;
    }

    private AnswerValidator refusing(final String reason)
    {
        return (submission, actor) -> reason;
    }

    private void register(final AnswerValidator... validators)
    {
        try {
            final Field field = ValidateAnswersHandler.class.getDeclaredField("validators");
            field.setAccessible(true);
            field.set(this.handler, List.of(validators));
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private WorkflowTaskContext context()
    {
        final Resource resource = this.target;
        final ResourceResolver resolver = this.context.resourceResolver();
        return new WorkflowTaskContext()
        {
            @Override
            public Resource getTarget()
            {
                return resource;
            }

            @Override
            public String getActor()
            {
                return REQUESTER;
            }

            @Override
            public WorkflowEvent getEvent()
            {
                return null;
            }

            @Override
            public Activity getActivity()
            {
                return null;
            }

            @Override
            public ResourceResolver getResourceResolver()
            {
                return resolver;
            }

            @Override
            public Object getVariable(final String name)
            {
                return null;
            }

            @Override
            public void setVariable(final String name, final Object value)
            {
                // Nothing this handler sets
            }
        };
    }
}

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.PartCreator;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CreatePartsHandler}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class CreatePartsHandlerTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String SUBMISSION_PATH = "/Submissions/ab/cd/ef/aRequest";

    /** Where the create workflow's own event lands, which is not a submission. */
    private static final String HOMEPAGE_PATH = "/Submissions";

    private static final String REQUESTER = "demo-requester";

    private final SlingContext context = new SlingContext();

    private final CreatePartsHandler handler = new CreatePartsHandler();

    /** The submissions each creator was asked about, in order. */
    private final List<String> asked = new ArrayList<>();

    @BeforeEach
    void setUp()
    {
        this.context.create().resource(SUBMISSION_PATH, Map.of(TYPE, Submission.RESOURCE_TYPE));
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(CreatePartsHandler.HANDLER_NAME, this.handler.getName());
    }

    @Test
    void asksEveryCreator() throws Exception
    {
        creators(recording("answers"), recording("documents"));

        this.handler.execute(context());

        assertEquals(List.of("answers " + SUBMISSION_PATH, "documents " + SUBMISSION_PATH), this.asked);
    }

    @Test
    void givesPartsToWhatTheCreateWorkflowJustMadeRatherThanItsOwnTarget() throws Exception
    {
        creators(recording("answers"));

        this.handler.execute(context(homepage(), SUBMISSION_PATH));

        assertEquals(List.of("answers " + SUBMISSION_PATH), this.asked);
    }

    @Test
    void refusesSomethingThatIsNotASubmission()
    {
        final Resource homepage = homepage();

        final WorkflowDefinitionException refusal = assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(context(homepage, null)));

        assertTrue(refusal.getMessage().contains("sub/SubmissionsHomepage"));
    }

    /**
     * The homepage the submission lives under, which the mock repository created along with it.
     *
     * @return the homepage
     */
    private Resource homepage()
    {
        final Resource homepage = Objects.requireNonNull(this.context.resourceResolver().getResource(HOMEPAGE_PATH));
        Objects.requireNonNull(homepage.adaptTo(ModifiableValueMap.class)).put(TYPE, "sub/SubmissionsHomepage");
        return homepage;
    }

    private void creators(final PartCreator... creators) throws ReflectiveOperationException
    {
        final var reference = CreatePartsHandler.class.getDeclaredField("creators");
        reference.setAccessible(true);
        reference.set(this.handler, List.of(creators));
    }

    private PartCreator recording(final String kind)
    {
        return submission -> this.asked.add(kind + " " + submission.getPath());
    }

    private WorkflowTaskContext context()
    {
        return context(Objects.requireNonNull(this.context.resourceResolver().getResource(SUBMISSION_PATH)), null);
    }

    /**
     * A context for a task acting on what an earlier activity created rather than on its own target.
     *
     * @param target the event's target, which for the create workflow is the homepage
     * @param createdPath the path recorded as created, or {@code null} when the target is itself the subject
     * @return the context to hand the handler
     */
    private WorkflowTaskContext context(final Resource target, final String createdPath)
    {
        final ResourceResolver resolver = this.context.resourceResolver();
        return new WorkflowTaskContext()
        {
            @Override
            public Resource getTarget()
            {
                return target;
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
                return WorkflowResult.CREATED_PATH_VARIABLE.equals(name) ? createdPath : null;
            }

            @Override
            public void setVariable(final String name, final Object value)
            {
                // Nothing this handler records is a variable
            }

            @Override
            public void sendEvent(final Resource to, final WorkflowEvent sent)
            {
                throw new IllegalStateException("No event was expected to be sent here");
            }

            @Override
            public void startWorkflow(final Resource host, final WorkflowVersion version)
            {
                throw new IllegalStateException("No workflow was expected to be started here");
            }
        };
    }
}

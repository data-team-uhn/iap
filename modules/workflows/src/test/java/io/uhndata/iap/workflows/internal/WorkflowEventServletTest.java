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

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.NoApplicableWorkflowException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowFixture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WorkflowEventServlet}: the POST-to-event translation, and the mapping of each acceptance
 * layer's failure onto its HTTP status.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class WorkflowEventServletTest
{
    private final SlingContext context = new SlingContext();

    private WorkflowEngine engine;

    private WorkflowEventServlet servlet;

    private Resource target;

    @BeforeEach
    void setUp() throws Exception
    {
        WorkflowFixture.setUp(this.context);
        this.target = EngineFixture.createTarget(this.context);
        this.engine = Mockito.mock(WorkflowEngine.class);
        this.servlet = new WorkflowEventServlet();
        // Wired by reflection, the way the other component tests do it: the SCR metadata the OSGi mocks would
        // need only exists in the packaged bundle
        final Field reference = WorkflowEventServlet.class.getDeclaredField("engine");
        reference.setAccessible(true);
        reference.set(this.servlet, this.engine);
    }

    @Test
    void redirectsToTheCreatedEntity() throws WorkflowException, IOException
    {
        Mockito.when(this.engine.receiveEvent(Mockito.any(), Mockito.any()))
            .thenReturn(new WorkflowResult(Map.of(WorkflowResult.CREATED_PATH, "/Workflows/myCoolWorkflow")));
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.servlet.doPost(request(Map.of("title", "My cool workflow")), response);

        assertEquals(302, response.getStatus());
        assertEquals("/Workflows/myCoolWorkflow", response.getHeader("Location"));
        assertTrue(response.getOutputAsString().contains("/Workflows/myCoolWorkflow"));
    }

    @Test
    void answersPlainCompletionWhenNothingWasCreated() throws WorkflowException, IOException
    {
        Mockito.when(this.engine.receiveEvent(Mockito.any(), Mockito.any()))
            .thenReturn(new WorkflowResult(Map.of()));
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.servlet.doPost(request(Map.of("title", "My cool workflow")), response);

        assertEquals(200, response.getStatus());
        assertTrue(response.getOutputAsString().contains("completed"));
    }

    @Test
    void translatesThePostIntoACreateEvent() throws WorkflowException, IOException
    {
        Mockito.when(this.engine.receiveEvent(Mockito.any(), Mockito.any()))
            .thenReturn(new WorkflowResult(Map.of()));
        final ArgumentCaptor<WorkflowEvent> sent = ArgumentCaptor.forClass(WorkflowEvent.class);

        this.servlet.doPost(request(Map.of(
            "title", "My cool workflow",
            "tags", new String[] { "a", "b" },
            ":operation", "sling:internal",
            "_charset_", "utf-8")), new MockSlingJakartaHttpServletResponse());

        Mockito.verify(this.engine).receiveEvent(Mockito.any(), sent.capture());
        final WorkflowEvent event = sent.getValue();
        assertEquals(WorkflowEventServlet.CREATE_EVENT, event.getName());
        assertEquals("My cool workflow", event.get("title"));
        assertArrayEquals(new String[] { "a", "b" }, (String[]) event.get("tags"));
        // The transport's own control parameters are not part of the domain event
        assertFalse(event.getPayload().containsKey(":operation"));
        assertFalse(event.getPayload().containsKey("_charset_"));
    }

    @Test
    void translatesAPostToATaskIntoACompleteEvent() throws WorkflowException, IOException
    {
        // What a POST means is decided by what it was aimed at: a homepage is asked to create something, a user
        // task is being told it has been decided. The servlet names the event and hands it over; that is all.
        Mockito.when(this.engine.receiveEvent(Mockito.any(), Mockito.any()))
            .thenReturn(new WorkflowResult(Map.of()));
        final Resource task = this.context.create().resource(
            "/Submissions/x/wf:instances/timeOffRequest/approveRequest", WorkflowFixture.TYPE,
            TaskInstance.RESOURCE_TYPE);
        final MockSlingJakartaHttpServletRequest request = request(Map.of("outcome", "approved"));
        request.setResource(task);
        final ArgumentCaptor<WorkflowEvent> sent = ArgumentCaptor.forClass(WorkflowEvent.class);

        this.servlet.doPost(request, new MockSlingJakartaHttpServletResponse());

        Mockito.verify(this.engine).receiveEvent(Mockito.any(), sent.capture());
        assertEquals(TaskCompletion.COMPLETE_EVENT, sent.getValue().getName());
        assertEquals("approved", sent.getValue().get(TaskCompletion.OUTCOME));
    }

    @Test
    void mapsNoApplicableWorkflowToConflict() throws WorkflowException, IOException
    {
        assertEquals(409, statusFor(new NoApplicableWorkflowException("nothing waiting")));
    }

    @Test
    void mapsNotAuthorizedToForbidden() throws WorkflowException, IOException
    {
        assertEquals(403, statusFor(new NotAuthorizedException("not allowed")));
    }

    @Test
    void mapsInvalidPayloadToBadRequest() throws WorkflowException, IOException
    {
        assertEquals(400, statusFor(new InvalidPayloadException("a title is required")));
    }

    @Test
    void mapsBrokenDefinitionsToServerError() throws WorkflowException, IOException
    {
        assertEquals(500, statusFor(new WorkflowDefinitionException("two workflows compete")));
    }

    /**
     * Fires a POST against an engine that fails with the given exception, and reports the resulting HTTP status.
     *
     * @param failure what the engine throws
     * @return the mapped status code
     * @throws WorkflowException never, only declared by the mocked engine
     * @throws IOException when the mock response cannot be written
     */
    private int statusFor(final WorkflowException failure) throws WorkflowException, IOException
    {
        Mockito.when(this.engine.receiveEvent(Mockito.any(), Mockito.any())).thenThrow(failure);
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.servlet.doPost(request(Map.of("title", "irrelevant")), response);

        assertTrue(response.getOutputAsString().contains(failure.getMessage()));
        return response.getStatus();
    }

    private MockSlingJakartaHttpServletRequest request(final Map<String, Object> parameters)
    {
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(), this.context.bundleContext());
        request.setResource(this.target);
        request.setParameterMap(parameters);
        return request;
    }
}

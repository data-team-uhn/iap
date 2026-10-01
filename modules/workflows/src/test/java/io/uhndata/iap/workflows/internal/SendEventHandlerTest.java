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

import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link SendEventHandler}: what it sends, and to where. Running the workflow waiting for the event
 * is the engine's, and is tested through the engine.
 *
 * @version $Id$
 * @since 0.1.0
 */
class SendEventHandlerTest
{
    private static final String MESSAGE = "message";

    private final SendEventHandler handler = new SendEventHandler();

    private final WorkflowTaskContext context = Mockito.mock(WorkflowTaskContext.class);

    private final Activity activity = Mockito.mock(Activity.class);

    private final Resource target = Mockito.mock(Resource.class);

    @BeforeEach
    void setUp()
    {
        Mockito.when(this.context.getActivity()).thenReturn(this.activity);
        Mockito.when(this.context.getTarget()).thenReturn(this.target);
        Mockito.when(this.context.getEvent()).thenReturn(new WorkflowEvent("create", Map.of("title", "Consent")));
        Mockito.when(this.activity.getPath()).thenReturn("/SystemWorkflows/createSchema/v1/send");
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(SendEventHandler.HANDLER_NAME, this.handler.getName());
    }

    @Test
    void sendsTheConfiguredEventWithTheTriggeringPayload() throws Exception
    {
        Mockito.when(this.activity.get(MESSAGE)).thenReturn("createVersion");

        this.handler.execute(this.context);

        final ArgumentCaptor<WorkflowEvent> sent = ArgumentCaptor.forClass(WorkflowEvent.class);
        Mockito.verify(this.context).sendEvent(Mockito.same(this.target), sent.capture());
        assertEquals("createVersion", sent.getValue().getName());
        assertEquals(Map.of("title", "Consent"), sent.getValue().getPayload());
    }

    @Test
    void sendsItToWhatTheExecutionCreated() throws Exception
    {
        final Resource created = Mockito.mock(Resource.class);
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        Mockito.when(this.activity.get(MESSAGE)).thenReturn("createVersion");
        Mockito.when(this.context.getVariable(WorkflowResult.CREATED_PATH_VARIABLE)).thenReturn("/Schemas/consent");
        Mockito.when(this.context.getResourceResolver()).thenReturn(resolver);
        Mockito.when(resolver.getResource("/Schemas/consent")).thenReturn(created);

        this.handler.execute(this.context);

        final ArgumentCaptor<Resource> to = ArgumentCaptor.forClass(Resource.class);
        Mockito.verify(this.context).sendEvent(to.capture(), Mockito.any());
        assertSame(created, to.getValue());
    }

    @Test
    void refusesAnActivityNamingNoEvent()
    {
        assertThrows(WorkflowDefinitionException.class, () -> this.handler.execute(this.context));
    }

    @Test
    void refusesABlankEvent()
    {
        Mockito.when(this.activity.get(MESSAGE)).thenReturn(" ");

        assertThrows(WorkflowDefinitionException.class, () -> this.handler.execute(this.context));
    }
}

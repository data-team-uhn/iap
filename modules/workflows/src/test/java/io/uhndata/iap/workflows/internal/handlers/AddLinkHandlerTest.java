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
package io.uhndata.iap.workflows.internal.handlers;

import java.util.Map;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.links.models.Linkable;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link AddLinkHandler}: what the task acts on linked to the content the event names, as the activity
 * configures, nothing linked when the event names nothing, and what cannot be linked refused.
 *
 * @version $Id$
 * @since 0.1.0
 */
class AddLinkHandlerTest
{
    private static final String SOURCE = "/Schemas/study/v1";

    private static final String COPIED_FROM = "copiedFrom";

    private final AddLinkHandler handler = new AddLinkHandler();

    private final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);

    private final Activity activity = Mockito.mock(Activity.class);

    private final Resource host = Mockito.mock(Resource.class);

    private final Linkable linkable = Mockito.mock(Linkable.class);

    private final Content source = Mockito.mock(Content.class);

    @BeforeEach
    void setUp()
    {
        final Resource resource = Mockito.mock(Resource.class);
        Mockito.when(resource.adaptTo(Content.class)).thenReturn(this.source);
        Mockito.when(this.resolver.getResource(SOURCE)).thenReturn(resource);
        Mockito.when(this.host.adaptTo(Linkable.class)).thenReturn(this.linkable);
        Mockito.when(this.host.getPath()).thenReturn("/Schemas/study/v2");
        Mockito.when(this.activity.get("to")).thenReturn("source");
        Mockito.when(this.activity.get("linkType")).thenReturn(COPIED_FROM);
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(AddLinkHandler.HANDLER_NAME, this.handler.getName());
    }

    @Test
    void linksToWhatTheEventNames() throws WorkflowException, PersistenceException
    {
        this.handler.execute(context(Map.of("source", SOURCE)));

        Mockito.verify(this.linkable).addLink(this.source, COPIED_FROM, null);
    }

    @Test
    void labelsTheLinkAsTheActivitySays() throws WorkflowException, PersistenceException
    {
        Mockito.when(this.activity.get("linkLabel")).thenReturn("first draft");

        this.handler.execute(context(Map.of("source", SOURCE)));

        Mockito.verify(this.linkable).addLink(this.source, COPIED_FROM, "first draft");
    }

    @Test
    void linksNothingWhenTheEventNamesNothing() throws WorkflowException, PersistenceException
    {
        this.handler.execute(context(Map.of()));
        // Blank, or something other than text, as for any entry an event gives
        this.handler.execute(context(Map.of("source", " ")));
        this.handler.execute(context(Map.of("source", 12)));

        Mockito.verifyNoInteractions(this.linkable);
    }

    @Test
    void needsALinkTypeAndWhatItLinksTo()
    {
        Mockito.when(this.activity.get("linkType")).thenReturn(null);
        assertThrows(WorkflowDefinitionException.class, () -> this.handler.execute(context(Map.of("source", SOURCE))));

        Mockito.when(this.activity.get("linkType")).thenReturn(COPIED_FROM);
        Mockito.when(this.activity.get("to")).thenReturn(null);
        assertThrows(WorkflowDefinitionException.class, () -> this.handler.execute(context(Map.of("source", SOURCE))));
    }

    @Test
    void refusesToLinkToWhatIsNotThere()
    {
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(Map.of("source", "/Schemas/study/v9"))));
    }

    @Test
    void failsWhileWhatIsThereCannotBeRead()
    {
        // As every model does for a moment while the models are registered again
        final Resource unreadable = Mockito.mock(Resource.class);
        Mockito.when(this.resolver.getResource("/elsewhere")).thenReturn(unreadable);
        assertThrows(WorkflowFailedException.class,
            () -> this.handler.execute(context(Map.of("source", "/elsewhere"))));

        Mockito.when(this.host.adaptTo(Linkable.class)).thenReturn(null);
        assertThrows(WorkflowFailedException.class, () -> this.handler.execute(context(Map.of("source", SOURCE))));
    }

    @Test
    void refusesALinkItsTypeForbids()
    {
        Mockito.when(this.linkable.addLink(this.source, COPIED_FROM, null))
            .thenThrow(new IllegalArgumentException("Unknown link type"));

        assertThrows(InvalidPayloadException.class, () -> this.handler.execute(context(Map.of("source", SOURCE))));
    }

    @Test
    void failsWhileLinksCannotBeWritten()
    {
        Mockito.when(this.linkable.addLink(this.source, COPIED_FROM, null))
            .thenThrow(new IllegalStateException("No links service"));

        assertThrows(PersistenceException.class, () -> this.handler.execute(context(Map.of("source", SOURCE))));
    }

    private WorkflowTaskContext context(final Map<String, Object> payload)
    {
        final WorkflowTaskContext context = Mockito.mock(WorkflowTaskContext.class);
        Mockito.when(context.getEvent()).thenReturn(new WorkflowEvent("createVersion", payload));
        Mockito.when(context.getActivity()).thenReturn(this.activity);
        Mockito.when(context.getResourceResolver()).thenReturn(this.resolver);
        Mockito.when(context.getTarget()).thenReturn(this.host);
        return context;
    }
}

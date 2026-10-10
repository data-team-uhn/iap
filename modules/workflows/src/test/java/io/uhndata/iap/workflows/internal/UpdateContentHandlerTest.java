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

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link UpdateContentHandler}: a patch applied to the fields the activity lists and the content's
 * type declares, checked whole before anything is written.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class UpdateContentHandlerTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private final UpdateContentHandler handler = new UpdateContentHandler();

    private FieldsFixture fixture;

    @BeforeEach
    void setUp() throws RepositoryException
    {
        this.fixture = new FieldsFixture(this.context);
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(UpdateContentHandler.HANDLER_NAME, this.handler.getName());
    }

    @Test
    void changesTheListedFields() throws WorkflowException, PersistenceException, RepositoryException
    {
        update("{\"title\": \" Renamed \", \"note\": \"A note\"}");

        assertEquals("Renamed", this.fixture.item().getProperty("title").getString());
        assertEquals("A note", this.fixture.item().getProperty("note").getString());

        update("{\"note\": null}");
        assertFalse(this.fixture.item().hasProperty("note"));
        update("{\"note\": \" \"}");
        assertFalse(this.fixture.item().hasProperty("note"));
    }

    @Test
    void pointsAReferenceAtWhatAPathNames() throws WorkflowException, PersistenceException, RepositoryException
    {
        update("{\"link\": \"/other\"}");

        assertEquals(this.fixture.other().getIdentifier(), this.fixture.item().getProperty("link").getString());
        assertThrows(InvalidPayloadException.class, () -> update("{\"link\": \"/nowhere\"}"));
        assertThrows(InvalidPayloadException.class, () -> update("{\"link\": \"/update\"}"));
    }

    @Test
    void refusesWhatThePatchCannotChange() throws RepositoryException
    {
        assertThrows(InvalidPayloadException.class, () -> update("{\"title\": null}"));
        // Listed, but only allowed by the type's residual definition
        assertThrows(InvalidPayloadException.class, () -> update("{\"extra\": \"x\"}"));
        assertThrows(InvalidPayloadException.class, () -> update("{\"unlisted\": \"x\"}"));
        assertThrows(InvalidPayloadException.class, () -> update("{\"note\": 3}"));
        // Nothing of a refused patch is applied
        assertThrows(InvalidPayloadException.class, () -> update("{\"note\": \"Kept?\", \"title\": \"\"}"));
        assertFalse(this.fixture.item().hasProperty("note"));
    }

    @Test
    void refusesAMissingOrMalformedPatch()
    {
        assertThrows(InvalidPayloadException.class, () -> this.handler.execute(context(Map.of())));
        assertThrows(InvalidPayloadException.class, () -> update("[1, 2]"));
        assertThrows(InvalidPayloadException.class, () -> update("{"));
    }

    @Test
    void needsTheActivityToListTheFields()
    {
        Mockito.when(this.fixture.activity().getChild("fields", Content.class)).thenReturn(null);

        assertThrows(WorkflowDefinitionException.class, () -> update("{\"title\": \"x\"}"));
    }

    @Test
    void checksOutContentThatWasCheckedIn() throws WorkflowException, PersistenceException, RepositoryException
    {
        this.fixture.session().getWorkspace().getVersionManager().checkin("/item");

        update("{\"note\": \"After the checkin\"}");

        assertEquals("After the checkin", this.fixture.item().getProperty("note").getString());
    }

    @Test
    void reportsContentThatCannotBeWritten() throws RepositoryException
    {
        final Resource host = Mockito.mock(Resource.class);
        final Node node = Mockito.mock(Node.class);
        Mockito.when(host.adaptTo(Node.class)).thenReturn(node);
        Mockito.when(node.getMixinNodeTypes()).thenThrow(new RepositoryException("gone"));
        final WorkflowTaskContext task = context(Map.of("patch", "{}"));
        Mockito.when(task.getTarget()).thenReturn(host);

        assertThrows(PersistenceException.class, () -> this.handler.execute(task));
    }

    private void update(final String patch) throws WorkflowException, PersistenceException, RepositoryException
    {
        this.handler.execute(context(Map.of("patch", patch)));
        this.fixture.session().save();
    }

    private WorkflowTaskContext context(final Map<String, Object> payload)
    {
        final ResourceResolver resolver = this.context.resourceResolver();
        final WorkflowTaskContext task = Mockito.mock(WorkflowTaskContext.class);
        Mockito.when(task.getEvent()).thenReturn(new WorkflowEvent("update", payload));
        Mockito.when(task.getActivity()).thenReturn(this.fixture.activity());
        Mockito.when(task.getResourceResolver()).thenReturn(resolver);
        Mockito.when(task.getTarget()).thenReturn(resolver.getResource("/item"));
        return task;
    }
}

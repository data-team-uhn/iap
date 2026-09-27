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

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Workspace;
import javax.jcr.version.VersionManager;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.tags.api.TagManager;
import io.uhndata.iap.tags.models.TagDefinition;
import io.uhndata.iap.utils.copy.ContentCopier;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link CopyContentHandler}: the event's source copied into what the task acts on, as the activity
 * configures, and refused when it is not something the activity accepts.
 *
 * @version $Id$
 * @since 0.1.0
 */
class CopyContentHandlerTest
{
    private static final String SOURCE = "/Schemas/study/v1";

    private static final String VERSION_TYPE = "sch/SchemaVersion";

    private final CopyContentHandler handler = new CopyContentHandler();

    private final ContentCopier copier = Mockito.mock(ContentCopier.class);

    private final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);

    private final Activity activity = Mockito.mock(Activity.class);

    private final Node sourceNode = Mockito.mock(Node.class);

    private final Node hostNode = Mockito.mock(Node.class);

    private final Resource host = Mockito.mock(Resource.class);

    @BeforeEach
    void setUp() throws ReflectiveOperationException, RepositoryException
    {
        final TagManager tags = Mockito.mock(TagManager.class);
        final TagDefinition active = Mockito.mock(TagDefinition.class);
        Mockito.when(active.getName()).thenReturn("active");
        Mockito.when(tags.findDefinitions("lifecycle", null)).thenReturn(List.of(active));
        inject("copier", this.copier);
        inject("tags", tags);
        final Resource source = Mockito.mock(Resource.class);
        Mockito.when(source.isResourceType(VERSION_TYPE)).thenReturn(true);
        Mockito.when(source.adaptTo(Node.class)).thenReturn(this.sourceNode);
        Mockito.when(source.getPath()).thenReturn(SOURCE);
        Mockito.when(this.resolver.getResource(SOURCE)).thenReturn(source);
        Mockito.when(this.host.adaptTo(Node.class)).thenReturn(this.hostNode);
        Mockito.when(this.host.getPath()).thenReturn("/Schemas/study/v2");
        Mockito.when(this.hostNode.isCheckedOut()).thenReturn(true);
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(CopyContentHandler.HANDLER_NAME, this.handler.getName());
    }

    @Test
    void copiesNothingWithoutASource() throws WorkflowException, PersistenceException
    {
        this.handler.execute(context(Map.of()));

        Mockito.verifyNoInteractions(this.copier);
    }

    @Test
    void copiesTheSourceAsTheActivityConfigures() throws WorkflowException, PersistenceException, RepositoryException
    {
        Mockito.when(this.activity.get("sourceType")).thenReturn(VERSION_TYPE);
        Mockito.when(this.activity.get("skipProperties")).thenReturn("version");
        Mockito.when(this.activity.get("dropTagCategories")).thenReturn(new String[] { "lifecycle" });

        this.handler.execute(context(Map.of("source", SOURCE)));

        Mockito.verify(this.copier).copy(this.sourceNode, this.hostNode, Set.of("version"),
            Map.of("tags", Set.of("active")));
    }

    @Test
    void copiesWholeWithoutSettings() throws WorkflowException, PersistenceException, RepositoryException
    {
        this.handler.execute(context(Map.of("source", SOURCE)));

        Mockito.verify(this.copier).copy(this.sourceNode, this.hostNode, Set.of(), Map.of("tags", Set.of()));
    }

    @Test
    void checksOutWhatReceivesTheCopy() throws WorkflowException, PersistenceException, RepositoryException
    {
        final Node versionable = Mockito.mock(Node.class);
        final VersionManager versions = Mockito.mock(VersionManager.class);
        final Session session = Mockito.mock(Session.class);
        final Workspace workspace = Mockito.mock(Workspace.class);
        Mockito.when(this.hostNode.isCheckedOut()).thenReturn(false);
        Mockito.when(this.hostNode.getParent()).thenReturn(versionable);
        Mockito.when(versionable.isNodeType("mix:versionable")).thenReturn(true);
        Mockito.when(versionable.getPath()).thenReturn("/Schemas/study");
        Mockito.when(versionable.getSession()).thenReturn(session);
        Mockito.when(session.getWorkspace()).thenReturn(workspace);
        Mockito.when(workspace.getVersionManager()).thenReturn(versions);

        this.handler.execute(context(Map.of("source", SOURCE)));

        Mockito.verify(versions).checkout("/Schemas/study");
    }

    @Test
    void refusesWhatItDoesNotAccept()
    {
        Mockito.when(this.activity.get("sourceType")).thenReturn("sub/Submission");

        assertThrows(InvalidPayloadException.class, () -> this.handler.execute(context(Map.of("source", SOURCE))));
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(Map.of("source", "/nowhere"))));
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(Map.of("source", new String[] { SOURCE }))));
    }

    @Test
    void reportsACopyThatFails() throws RepositoryException
    {
        Mockito.when(this.copier.copy(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
            .thenThrow(new RepositoryException("full"));

        assertThrows(PersistenceException.class, () -> this.handler.execute(context(Map.of("source", SOURCE))));
    }

    private WorkflowTaskContext context(final Map<String, Object> payload)
    {
        final WorkflowTaskContext context = Mockito.mock(WorkflowTaskContext.class);
        Mockito.when(context.getEvent()).thenReturn(new WorkflowEvent("create", payload));
        Mockito.when(context.getActivity()).thenReturn(this.activity);
        Mockito.when(context.getResourceResolver()).thenReturn(this.resolver);
        Mockito.when(context.getTarget()).thenReturn(this.host);
        return context;
    }

    private void inject(final String name, final Object value) throws ReflectiveOperationException
    {
        final Field field = CopyContentHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(this.handler, value);
    }
}

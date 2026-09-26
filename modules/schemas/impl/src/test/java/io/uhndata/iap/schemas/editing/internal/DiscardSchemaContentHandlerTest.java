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
package io.uhndata.iap.schemas.editing.internal;

import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.workflows.api.NoApplicableWorkflowException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DiscardSchemaContentHandler}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class DiscardSchemaContentHandlerTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private final DiscardSchemaContentHandler handler = new DiscardSchemaContentHandler();

    private SchemaFixture fixture;

    private Resource schema;

    @BeforeEach
    void setUp() throws PersistenceException
    {
        this.fixture = new SchemaFixture(this.context);
        this.schema = this.fixture.schema("study");
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(DiscardSchemaContentHandler.HANDLER_NAME, this.handler.getName());
    }

    @Test
    void discardsADraft() throws WorkflowException, PersistenceException
    {
        final Resource draft = this.fixture.version(this.schema, "v2", "draft");
        this.fixture.create(draft.getPath(), "form", "sch:FormRequirement", Map.of("label", "Form"));

        this.handler.execute(task(draft));

        assertNull(this.fixture.get("/Schemas/study/v2"));
    }

    @Test
    void keepsWhatHasBeenPublished() throws PersistenceException
    {
        final Resource active = this.fixture.version(this.schema, "v1", "active");
        final Resource retired = this.fixture.version(this.schema, "v0", "retired");

        assertThrows(NoApplicableWorkflowException.class, () -> this.handler.execute(task(active)));
        assertThrows(NoApplicableWorkflowException.class, () -> this.handler.execute(task(retired)));
        assertThrows(NoApplicableWorkflowException.class, () -> this.handler.execute(task(this.schema)));
        assertNotNull(this.fixture.get("/Schemas/study/v1"));
    }

    @Test
    void keepsADraftSomethingElseRefersTo() throws PersistenceException, RepositoryException
    {
        final Resource draft = this.fixture.version(this.schema, "v2", "draft");
        final Session session = this.context.resourceResolver().adaptTo(Session.class);
        final Node binding = session.getRootNode().addNode("binding", "nt:unstructured");
        binding.setProperty("schemaVersion", draft.adaptTo(Node.class));
        session.save();

        final NoApplicableWorkflowException refusal =
            assertThrows(NoApplicableWorkflowException.class, () -> this.handler.execute(task(draft)));

        assertTrue(refusal.getMessage().contains("/binding"), refusal.getMessage());
        assertNotNull(this.fixture.get("/Schemas/study/v2"));
    }

    @Test
    void ignoresReferencesFromInsideWhatIsDiscarded() throws WorkflowException, PersistenceException,
        RepositoryException
    {
        final Resource draft = this.fixture.version(this.schema, "v2", "draft");
        final Resource form = this.fixture.create(draft.getPath(), "form", "sch:FormRequirement",
            Map.of("label", "Form"));
        final Session session = this.context.resourceResolver().adaptTo(Session.class);
        final Node note = session.getNode(draft.getPath()).addNode("note", "nt:unstructured");
        note.setProperty("about", form.adaptTo(Node.class));
        note.setProperty("self", session.getNode(draft.getPath()));
        session.save();

        this.handler.execute(task(draft));

        assertNull(this.fixture.get("/Schemas/study/v2"));
    }

    @Test
    void discardsASchemaThatWasNeverPublished() throws WorkflowException, PersistenceException
    {
        final Resource unpublished = this.fixture.schema("idea");
        this.fixture.version(unpublished, "v1", "draft");

        this.handler.execute(task(unpublished));

        assertNull(this.fixture.get("/Schemas/idea"));
    }

    @Test
    void servesOnlySchemasAndVersions()
    {
        assertThrows(WorkflowDefinitionException.class, () -> this.handler.execute(task(this.fixture.get("/Schemas"))));
    }

    @Test
    void reportsReferencesThatCannotBeRead() throws RepositoryException
    {
        final Resource target = Mockito.mock(Resource.class);
        Mockito.when(target.isResourceType(SchemaVersion.RESOURCE_TYPE)).thenReturn(true);
        final SchemaVersion version = Mockito.mock(SchemaVersion.class);
        Mockito.when(version.isDraft()).thenReturn(true);
        Mockito.when(target.adaptTo(SchemaVersion.class)).thenReturn(version);
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.getPath()).thenThrow(new RepositoryException("gone"));
        Mockito.when(target.adaptTo(Node.class)).thenReturn(node);

        assertThrows(PersistenceException.class, () -> this.handler.execute(task(target)));
    }

    private TaskContext task(final Resource target)
    {
        return new TaskContext(target, Map.of(), Map.of());
    }
}

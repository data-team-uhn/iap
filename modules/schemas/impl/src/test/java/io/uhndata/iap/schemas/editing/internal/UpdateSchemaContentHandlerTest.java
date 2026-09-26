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

import org.apache.sling.api.resource.ModifiableValueMap;
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

import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.NoApplicableWorkflowException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link UpdateSchemaContentHandler}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class UpdateSchemaContentHandlerTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private final UpdateSchemaContentHandler handler = new UpdateSchemaContentHandler();

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
        assertEquals(UpdateSchemaContentHandler.HANDLER_NAME, this.handler.getName());
    }

    @Test
    void renamesASchema() throws WorkflowException, PersistenceException
    {
        this.handler.execute(patch(this.schema, "{\"title\": \"Clinical study\"}"));

        assertEquals("Clinical study", this.schema.getValueMap().get("title"));
    }

    @Test
    void refusesToLeaveAMandatoryFieldEmpty()
    {
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(patch(this.schema, "{\"title\": null}")));
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(patch(this.schema, "{\"title\": \"  \"}")));
    }

    @Test
    void refusesWhatIsNotAField()
    {
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(patch(this.schema, "{\"sling:resourceType\": \"x\"}")));
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(patch(this.schema, "{\"title\": 3}")));
    }

    @Test
    void refusesAMissingOrMalformedPatch()
    {
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(new TaskContext(this.schema, Map.of(), Map.of())));
        assertThrows(InvalidPayloadException.class, () -> this.handler.execute(patch(this.schema, "[1, 2]")));
        assertThrows(InvalidPayloadException.class, () -> this.handler.execute(patch(this.schema, "{")));
    }

    @Test
    void editsAnythingOnADraft() throws WorkflowException, PersistenceException
    {
        final Resource draft = this.fixture.version(this.schema, "v2", "draft");

        this.handler.execute(patch(draft, "{\"version\": \"2.0\", \"description\": \"Second\"}"));

        assertEquals("2.0", draft.getValueMap().get("version"));
        assertEquals("Second", draft.getValueMap().get("description"));
    }

    @Test
    void onlyRewordsAPublishedVersion() throws WorkflowException, PersistenceException
    {
        final Resource active = this.fixture.version(this.schema, "v1", "active");

        this.handler.execute(patch(active, "{\"description\": \"Fixed a typo\"}"));
        assertEquals("Fixed a typo", active.getValueMap().get("description"));

        final NoApplicableWorkflowException refusal = assertThrows(NoApplicableWorkflowException.class,
            () -> this.handler.execute(patch(active, "{\"description\": \"x\", \"version\": \"9\"}")));
        assertTrue(refusal.getMessage().contains("version"));
        // Nothing of a refused patch is applied
        assertEquals("Fixed a typo", active.getValueMap().get("description"));
    }

    @Test
    void removesAnOptionalField() throws WorkflowException, PersistenceException
    {
        final Resource draft = this.fixture.create("/Schemas/study", "v2", "sch:SchemaVersion",
            Map.of("version", "2", "description", "Old"), "draft");

        this.handler.execute(patch(draft, "{\"description\": null}"));
        assertFalse(draft.getValueMap().containsKey("description"));

        this.fixture.create("/Schemas/study", "v3", "sch:SchemaVersion", Map.of("version", "3", "description", "x"),
            "draft");
        this.handler.execute(patch(this.fixture.get("/Schemas/study/v3"), "{\"description\": \"\"}"));
        assertFalse(this.fixture.get("/Schemas/study/v3").getValueMap().containsKey("description"));
    }

    @Test
    void pointsAVersionAtAWorkflow() throws WorkflowException, PersistenceException
    {
        this.fixture.create("/", "Workflows", "nt:unstructured", Map.of());
        this.fixture.create("/Workflows", "review", "wf:WorkflowDefinition", Map.of("title", "Review"));
        final Resource workflow = this.fixture.create("/Workflows/review", "v1", "wf:WorkflowVersion",
            Map.of("version", "1"));
        final Resource draft = this.fixture.version(this.schema, "v1", "draft");

        this.handler.execute(patch(draft, "{\"workflow\": \"/Workflows/review/v1\"}"));

        assertEquals(workflow.getValueMap().get("jcr:uuid"), draft.getValueMap().get("workflow"));
    }

    @Test
    void refusesAWorkflowThatIsNotThere() throws PersistenceException
    {
        final Resource draft = this.fixture.version(this.schema, "v1", "draft");

        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(patch(draft, "{\"workflow\": \"/Schemas\"}")));
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(patch(draft, "{\"workflow\": \"/nowhere\"}")));
    }

    @Test
    void servesOnlySchemasAndVersions()
    {
        assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(patch(this.fixture.get("/Schemas"), "{}")));
    }

    @Test
    void reportsContentThatCannotBeWritten() throws RepositoryException
    {
        final Resource target = Mockito.mock(Resource.class);
        Mockito.when(target.isResourceType(SchemaVersion.RESOURCE_TYPE)).thenReturn(true);
        final SchemaVersion version = Mockito.mock(SchemaVersion.class);
        Mockito.when(version.isDraft()).thenReturn(true);
        Mockito.when(target.adaptTo(SchemaVersion.class)).thenReturn(version);
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        Mockito.when(target.getResourceResolver()).thenReturn(resolver);
        final Resource workflow = Mockito.mock(Resource.class);
        Mockito.when(workflow.isResourceType("wf/WorkflowVersion")).thenReturn(true);
        Mockito.when(resolver.getResource("/w")).thenReturn(workflow);

        assertThrows(PersistenceException.class, () -> this.handler.execute(patch(target, "{\"version\": \"2\"}")));

        Mockito.when(target.adaptTo(ModifiableValueMap.class)).thenReturn(Mockito.mock(ModifiableValueMap.class));
        assertThrows(PersistenceException.class, () -> this.handler.execute(patch(target, "{\"workflow\": \"/w\"}")));

        final Node node = Mockito.mock(Node.class);
        Mockito.when(target.adaptTo(Node.class)).thenReturn(node);
        Mockito.when(workflow.adaptTo(Node.class)).thenReturn(Mockito.mock(Node.class));
        Mockito.when(node.setProperty(Mockito.anyString(), Mockito.any(Node.class)))
            .thenThrow(new RepositoryException("locked"));
        assertThrows(PersistenceException.class, () -> this.handler.execute(patch(target, "{\"workflow\": \"/w\"}")));
    }

    private TaskContext patch(final Resource target, final String patch)
    {
        return new TaskContext(target, Map.of("patch", patch), Map.of());
    }
}

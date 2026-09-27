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

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.version.VersionManager;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CreateSchemaVersionHandler}: a new, empty version under the target schema, labelled as
 * asked, and reported as what the execution created.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class CreateSchemaVersionHandlerTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private final CreateSchemaVersionHandler handler = new CreateSchemaVersionHandler();

    private SchemaFixture fixture;

    @BeforeEach
    void setUp() throws PersistenceException
    {
        this.fixture = new SchemaFixture(this.context);
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(CreateSchemaVersionHandler.HANDLER_NAME, this.handler.getName());
    }

    @Test
    void givesTheSchemaAFirstVersion() throws WorkflowException, PersistenceException
    {
        final TaskContext task = task(this.fixture.schema("study"), Map.of());

        this.handler.execute(task);

        assertEquals("1.0", this.fixture.get("/Schemas/study/v1").getValueMap().get("version"));
        // What follows, e.g. marking it as a draft, acts on the new version
        assertEquals("/Schemas/study/v1", task.getVariable(WorkflowResult.CREATED_PATH_VARIABLE));
    }

    @Test
    void numbersTheNextVersion() throws WorkflowException, PersistenceException
    {
        final Resource schema = this.fixture.schema("study");
        this.fixture.version(schema, "v1", "active");

        this.handler.execute(task(schema, Map.of()));

        assertEquals("2.0", this.fixture.get("/Schemas/study/v2").getValueMap().get("version"));
    }

    @Test
    void labelsTheVersionAsAsked() throws WorkflowException, PersistenceException
    {
        this.handler.execute(task(this.fixture.schema("study"), Map.of("version", " 2026 ")));

        assertEquals("2026", this.fixture.get("/Schemas/study/v1").getValueMap().get("version"));
    }

    @Test
    void refusesAnUnusableLabel() throws PersistenceException
    {
        final Resource schema = this.fixture.schema("study");

        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(task(schema, Map.of("version", "  "))));
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(task(schema, Map.of("version", new String[] { "1", "2" }))));
    }

    @Test
    void checksOutTheVersionableNodeHoldingAPart() throws PersistenceException, RepositoryException
    {
        final Resource version = this.fixture.version(this.fixture.schema("study"), "v1", "draft");
        final Resource form = this.fixture.create(version.getPath(), "form", "sch:FormRequirement",
            Map.of("label", "Form"));
        final VersionManager versions =
            this.context.resourceResolver().adaptTo(Session.class).getWorkspace().getVersionManager();
        versions.checkin(version.getPath());

        SchemaContent.checkOut(form);

        assertTrue(versions.isCheckedOut(version.getPath()));
    }

    @Test
    void servesOnlySchemas()
    {
        assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(task(this.fixture.get("/Schemas"), Map.of())));
    }

    private TaskContext task(final Resource target, final Map<String, Object> payload)
    {
        return new TaskContext(target, payload, Map.of());
    }
}

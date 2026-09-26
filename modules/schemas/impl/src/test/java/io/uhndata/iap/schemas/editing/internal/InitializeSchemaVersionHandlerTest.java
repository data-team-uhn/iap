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

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.schemas.models.LifecycleState;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link InitializeSchemaVersionHandler}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class InitializeSchemaVersionHandlerTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private final InitializeSchemaVersionHandler handler = new InitializeSchemaVersionHandler();

    private SchemaFixture fixture;

    @BeforeEach
    void setUp() throws PersistenceException
    {
        this.fixture = new SchemaFixture(this.context);
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(InitializeSchemaVersionHandler.HANDLER_NAME, this.handler.getName());
    }

    @Test
    void givesTheNewSchemaADraftFirstVersion() throws WorkflowException, PersistenceException
    {
        final Resource schema = this.fixture.schema("study");
        final TaskContext task = task(schema, Map.of());

        this.handler.execute(task);

        final Resource version = this.fixture.get("/Schemas/study/v1");
        assertEquals("1.0", version.getValueMap().get("version"));
        assertArrayEquals(new String[] { "draft" }, version.getValueMap().get("tags", String[].class));
        assertEquals(LifecycleState.DRAFT, version.adaptTo(SchemaVersion.class).getState());
        // The redirect still goes to the schema
        assertEquals("/Schemas/study", task.getVariable(WorkflowResult.CREATED_PATH_VARIABLE));
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
    void mustFollowTheStepCreatingTheSchema() throws PersistenceException
    {
        final Resource schema = this.fixture.schema("study");
        final TaskContext nothingCreated = new TaskContext(schema, Map.of(), Map.of());
        final TaskContext notASchema = new TaskContext(schema, Map.of(), Map.of());
        notASchema.setVariable(WorkflowResult.CREATED_PATH_VARIABLE, "/Schemas");

        assertThrows(WorkflowDefinitionException.class, () -> this.handler.execute(nothingCreated));
        assertThrows(WorkflowDefinitionException.class, () -> this.handler.execute(notASchema));
    }

    private TaskContext task(final Resource schema, final Map<String, Object> payload)
    {
        final TaskContext task = new TaskContext(this.fixture.get("/Schemas"), payload, Map.of());
        task.setVariable(WorkflowResult.CREATED_PATH_VARIABLE, schema.getPath());
        return task;
    }
}

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

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.FlowNode;
import io.uhndata.iap.workflows.models.WorkflowVersion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CreatableContentProcessor}: {@code @creatable} lists what the create workflow that would run
 * makes in a container, with the fields it starts with.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class CreatableContentProcessorTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private final CreatableContentProcessor processor = new CreatableContentProcessor();

    private final WorkflowEngine engine = Mockito.mock(WorkflowEngine.class);

    private FieldsFixture fixture;

    @BeforeEach
    void setUp() throws RepositoryException, ReflectiveOperationException
    {
        this.fixture = new FieldsFixture(this.context);
        final Field field = CreatableContentProcessor.class.getDeclaredField("engine");
        field.setAccessible(true);
        field.set(this.processor, this.engine);
    }

    @Test
    void isTheOptInCreatableProcessor()
    {
        assertEquals("creatable", this.processor.getName());
        assertEquals(50, this.processor.getPriority());
    }

    @Test
    void listsWhatTheCreateWorkflowWouldMakeHere() throws Exception
    {
        final WorkflowVersion version = Mockito.mock(WorkflowVersion.class);
        Mockito.when(version.getFlowNodes()).thenReturn(
            List.of(Mockito.mock(FlowNode.class), this.fixture.creating(), this.fixture.activity()));
        FieldsFixture.inspecting(this.engine, "create", version);

        final JsonArray creatable = serialize(this.fixture.session().getNode("/box")).getJsonArray("@creatable");

        // A box holds items only, not boxes
        assertEquals(1, creatable.size());
        final JsonObject item = creatable.getJsonObject(0);
        assertEquals("test:Item", item.getString("type"));
        assertEquals("Item", item.getString("label"));
        assertEquals("item", item.getString("defaultName"));
        assertFalse(item.containsKey("namePattern"));
        final JsonObject title = item.getJsonArray("fields").getJsonObject(0);
        assertEquals("title", title.getString("name"));
        assertEquals(true, title.getBoolean("mandatory"));
        assertEquals(15, item.getJsonArray("fields").size());
        // An item tolerates children of any type, which is not holding any
        assertEquals(0, serialize(this.fixture.item()).getJsonArray("@creatable").size());
    }

    @Test
    void saysWhatNamesTheCreateWouldAccept() throws Exception
    {
        final WorkflowVersion version = Mockito.mock(WorkflowVersion.class);
        final Activity other = Mockito.mock(Activity.class);
        Mockito.when(other.getHandler()).thenReturn(CreateContentHandler.HANDLER_NAME);
        Mockito.when(version.getFlowNodes()).thenReturn(List.of(other, this.fixture.creating()));
        Mockito.when(this.fixture.creating().get(ContentNames.NAME_PATTERN, String.class)).thenReturn("^[a-z]+$");
        Mockito.when(this.fixture.creating().get(ContentNames.NAME_HINT, String.class)).thenReturn("Small letters.");
        FieldsFixture.inspecting(this.engine, "create", version);

        final JsonObject item =
            serialize(this.fixture.session().getNode("/box")).getJsonArray("@creatable").getJsonObject(0);
        assertTrue(item.getBoolean("named"));
        assertEquals("^[a-z]+$", item.getString("namePattern"));
        assertEquals("Small letters.", item.getString("nameHint"));

        // A type that takes no name of its own has no rule for one
        this.fixture.session().getNode("/create/types/item").setProperty(ContentNames.NAMED, false);
        this.fixture.session().save();
        final JsonObject unnamed =
            serialize(this.fixture.session().getNode("/box")).getJsonArray("@creatable").getJsonObject(0);
        assertFalse(unnamed.getBoolean("named"));
        assertFalse(unnamed.containsKey("namePattern"));
        assertFalse(unnamed.containsKey("nameHint"));
    }

    @Test
    void listsNothingWhereNoCreateWouldRun() throws Exception
    {
        assertFalse(serialize(this.fixture.session().getNode("/box")).containsKey("@creatable"));
        assertFalse(serialize(this.fixture.session().getNode("/create")).containsKey("@creatable"));
    }

    @Test
    void listsNothingWhenTheEngineCannotAnswer() throws Exception
    {
        Mockito.when(this.engine.findApplicableWorkflow(Mockito.any(), Mockito.any()))
            .thenThrow(new WorkflowDefinitionException("broken"));

        assertFalse(serialize(this.fixture.session().getNode("/box")).containsKey("@creatable"));
    }

    private JsonObject serialize(final Node node) throws RepositoryException
    {
        final Resource resource = this.context.resourceResolver().getResource(node.getPath());
        final JsonObjectBuilder json = Json.createObjectBuilder();
        this.processor.start(resource);
        this.processor.leave(node, json, child -> null);
        this.processor.end(resource);
        return json.build();
    }
}

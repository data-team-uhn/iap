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

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.FlowNode;
import io.uhndata.iap.workflows.models.StartEvent;
import io.uhndata.iap.workflows.models.WorkflowVersion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Unit tests for {@link SchemaFieldsProcessor}: each schema and version lists the fields the update that would run
 * lets the requesting user change, described for an editor.
 *
 * @version $Id$
 * @since 0.1.0
 */
class SchemaFieldsProcessorTest
{
    private static final String PATH = "/Schemas/study/v1";

    private final SchemaFieldsProcessor processor = new SchemaFieldsProcessor();

    private final WorkflowEngine engine = Mockito.mock(WorkflowEngine.class);

    private final Resource resource = Mockito.mock(Resource.class);

    private final Node node = Mockito.mock(Node.class);

    @BeforeEach
    void setUp() throws ReflectiveOperationException, RepositoryException
    {
        final Field reference = SchemaFieldsProcessor.class.getDeclaredField("engine");
        reference.setAccessible(true);
        reference.set(this.processor, this.engine);
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        Mockito.when(this.resource.getResourceResolver()).thenReturn(resolver);
        Mockito.when(this.resource.getResourceType()).thenReturn(SchemaVersion.RESOURCE_TYPE);
        Mockito.when(resolver.getResource(PATH)).thenReturn(this.resource);
        Mockito.when(this.node.getPath()).thenReturn(PATH);
        Mockito.when(this.node.isNodeType("sch:SchemaVersion")).thenReturn(true);
    }

    @Test
    void isTheOptInFieldsProcessor()
    {
        assertEquals("fields", this.processor.getName());
        assertEquals(50, this.processor.getPriority());
        assertFalse(this.processor.isEnabledByDefault(this.resource));
    }

    @Test
    void listsTheFieldsTheUpdateWouldChange() throws Exception
    {
        final Activity update =
            activity(UpdateSchemaContentHandler.HANDLER_NAME, new String[] { "description", "nope" });
        final Activity other = activity("addTag", "version");
        updateRunning(List.of(Mockito.mock(StartEvent.class), other, update));

        final JsonArray fields = serialize().getJsonArray("@fields");

        assertEquals(1, fields.size());
        final JsonObject description = fields.getJsonObject(0);
        assertEquals("description", description.getString("name"));
        assertEquals("Description", description.getString("label"));
        assertEquals("text", description.getString("kind"));
        assertFalse(description.getBoolean("mandatory"));
        assertEquals(true, description.getBoolean("multiline"));
    }

    @Test
    void listsNothingWhenNoUpdateWouldRun() throws Exception
    {
        assertEquals(Json.createArrayBuilder().build(), serialize().getJsonArray("@fields"));
    }

    @Test
    void leavesOutWhatIsNotASchemaOrAVersion() throws Exception
    {
        Mockito.when(this.node.isNodeType("sch:SchemaVersion")).thenReturn(false);
        Mockito.when(this.node.isNodeType("sch:Schema")).thenThrow(new RepositoryException("gone"));

        assertFalse(serialize().containsKey("@fields"));
        Mockito.verifyNoInteractions(this.engine);
    }

    @Test
    void listsNothingWhenTheEngineCannotAnswer() throws Exception
    {
        Mockito.when(this.engine.inspectWorkflow(Mockito.any(), Mockito.any(), Mockito.any()))
            .thenThrow(new WorkflowFailedException("no service user"));

        assertFalse(serialize().containsKey("@fields"));
    }

    @SuppressWarnings("unchecked")
    private void updateRunning(final List<FlowNode> nodes) throws Exception
    {
        final WorkflowVersion version = Mockito.mock(WorkflowVersion.class);
        Mockito.when(version.getFlowNodes()).thenReturn(nodes);
        Mockito.when(this.engine.inspectWorkflow(Mockito.eq(this.resource), Mockito.eq("update"), Mockito.any()))
            .thenAnswer(call -> ((Function<WorkflowVersion, Object>) call.getArgument(2)).apply(version));
    }

    private static Activity activity(final String handler, final Object fields)
    {
        final Activity activity = Mockito.mock(Activity.class);
        Mockito.when(activity.getHandler()).thenReturn(handler);
        Mockito.when(activity.get("fields")).thenReturn(fields);
        return activity;
    }

    private JsonObject serialize()
    {
        final JsonObjectBuilder json = Json.createObjectBuilder();
        this.processor.start(this.resource);
        this.processor.leave(this.node, json, child -> null);
        this.processor.end(this.resource);
        return json.build();
    }
}

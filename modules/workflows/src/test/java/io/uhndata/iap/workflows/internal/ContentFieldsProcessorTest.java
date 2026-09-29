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
 * Unit tests for {@link ContentFieldsProcessor}: {@code @fields} lists what the update that would run lets change,
 * as the content's type declares it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ContentFieldsProcessorTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private final ContentFieldsProcessor processor = new ContentFieldsProcessor();

    private final WorkflowEngine engine = Mockito.mock(WorkflowEngine.class);

    private FieldsFixture fixture;

    @BeforeEach
    void setUp() throws RepositoryException, ReflectiveOperationException
    {
        this.fixture = new FieldsFixture(this.context);
        final Field field = ContentFieldsProcessor.class.getDeclaredField("engine");
        field.setAccessible(true);
        field.set(this.processor, this.engine);
    }

    @Test
    void isTheOptInFieldsProcessor()
    {
        assertEquals("fields", this.processor.getName());
        assertEquals(50, this.processor.getPriority());
    }

    @Test
    void listsTheFieldsTheUpdateWouldChange() throws Exception
    {
        final WorkflowVersion version = Mockito.mock(WorkflowVersion.class);
        final List<FlowNode> nodes = List.of(Mockito.mock(FlowNode.class), this.fixture.activity(), otherActivity());
        Mockito.when(version.getFlowNodes()).thenReturn(nodes);
        FieldsFixture.inspecting(this.engine, "update", version);

        final JsonObject json = serialize(this.fixture.item());

        final JsonArray fields = json.getJsonArray("@fields");
        assertEquals(List.of("title", "note", "link", "weakLink", "related", "shape", "count", "ratio", "flag",
            "keywords", "sizes", "level", "weight", "visible", "colours"),
            fields.stream().map(field -> field.asJsonObject().getString("name")).toList());
        assertEquals(field("title", "Title", "text", false, true, false).build(), fields.get(0));
        assertEquals(field("note", "Note", "text", false, false, true).build(), fields.get(1));
        assertEquals(field("link", "link", "reference", false, false, false).add("referenceType", "test/Item").build(),
            fields.get(2));
        assertEquals(field("related", "related", "reference", true, false, false).add("referenceRoot", "/").build(),
            fields.get(4));
        assertEquals(field("shape", "shape", "text", false, false, false)
            .add("help", "What it looks like.")
            .add("choices", Json.createArrayBuilder()
                .add(Json.createObjectBuilder().add("value", "round").add("label", "Round"))
                .add(Json.createObjectBuilder().add("value", "square").add("label", "square")))
            .build(), fields.get(5));
        assertEquals(field("count", "count", "long", false, false, false)
            .add("appliesWhen", Json.createObjectBuilder().add("property", "shape")
                .add("values", Json.createArrayBuilder().add("round").add("square")))
            .build(), fields.get(6));
        assertEquals("double", fields.getJsonObject(7).getString("kind"));
        assertEquals("boolean", fields.getJsonObject(8).getString("kind"));
        assertTrue(fields.getJsonObject(9).getBoolean("multiple"));
        // What new content starts with, as the field's kind
        assertEquals(1, fields.getJsonObject(11).getInt("default"));
        assertEquals(0.5, fields.getJsonObject(12).getJsonNumber("default").doubleValue());
        assertTrue(fields.getJsonObject(13).getBoolean("default"));
        assertEquals(Json.createArrayBuilder().add("red").add("blue").build(), fields.getJsonObject(14).get("default"));
        assertFalse(fields.getJsonObject(0).containsKey("default"));
        assertFalse(json.containsKey("@notice"));
        assertFalse(fields.getJsonObject(0).containsKey("unique"));
    }

    @Test
    void saysWhichFieldsMustHoldAValueAlone() throws Exception
    {
        this.fixture.session().getNode("/update/fields/title").setProperty("unique", true);
        this.fixture.session().save();
        final WorkflowVersion version = Mockito.mock(WorkflowVersion.class);
        Mockito.when(version.getFlowNodes()).thenReturn(List.of(this.fixture.activity()));
        FieldsFixture.inspecting(this.engine, "update", version);

        assertTrue(serialize(this.fixture.item()).getJsonArray("@fields").getJsonObject(0).getBoolean("unique"));
    }

    @Test
    void saysWhatTheUpdateAllowsWhenItsWorkflowSays() throws Exception
    {
        final WorkflowVersion version = Mockito.mock(WorkflowVersion.class);
        Mockito.when(version.getFlowNodes()).thenReturn(List.of(this.fixture.activity()));
        Mockito.when(version.getNotice()).thenReturn("Only the wording can change.");
        FieldsFixture.inspecting(this.engine, "update", version);

        assertEquals("Only the wording can change.", serialize(this.fixture.item()).getString("@notice"));
    }

    @Test
    void listsNothingWhereNoUpdateWouldRun() throws Exception
    {
        final JsonObject json = serialize(this.fixture.item());
        assertFalse(json.containsKey("@fields"));
        assertFalse(json.containsKey("@notice"));
        assertFalse(serialize(this.fixture.session().getNode("/update")).containsKey("@fields"));
    }

    @Test
    void listsNothingWhenTheEngineCannotAnswer() throws Exception
    {
        Mockito.when(this.engine.inspectWorkflow(Mockito.any(), Mockito.any(), Mockito.any()))
            .thenThrow(new WorkflowDefinitionException("broken"));

        assertFalse(serialize(this.fixture.item()).containsKey("@fields"));
    }

    private Activity otherActivity()
    {
        final Activity other =
            Mockito.mock(Activity.class);
        Mockito.when(other.getHandler()).thenReturn("addTag");
        return other;
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

    private static JsonObjectBuilder field(final String name, final String label, final String kind,
        final boolean multiple, final boolean mandatory, final boolean multiline)
    {
        return Json.createObjectBuilder().add("name", name).add("label", label).add("kind", kind)
            .add("multiple", multiple).add("mandatory", mandatory).add("multiline", multiline);
    }
}

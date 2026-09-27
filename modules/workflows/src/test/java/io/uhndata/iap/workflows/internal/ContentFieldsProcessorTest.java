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
        Mockito.when(this.engine.findApplicableWorkflow(Mockito.any(), Mockito.eq("update"))).thenReturn(version);

        final JsonObject json = serialize(this.fixture.item());

        assertEquals(Json.createArrayBuilder()
            .add(field("title", "Title", "text", true, false))
            .add(field("note", "Note", "text", false, true))
            .add(field("link", "link", "reference", false, false))
            .build(), json.getJsonArray("@fields"));
    }

    @Test
    void listsNothingWhereNoUpdateWouldRun() throws Exception
    {
        assertFalse(serialize(this.fixture.item()).containsKey("@fields"));
        assertFalse(serialize(this.fixture.session().getNode("/update")).containsKey("@fields"));
    }

    @Test
    void listsNothingWhenTheEngineCannotAnswer() throws Exception
    {
        Mockito.when(this.engine.findApplicableWorkflow(Mockito.any(), Mockito.any()))
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

    private static JsonObject field(final String name, final String label, final String kind,
        final boolean mandatory, final boolean multiline)
    {
        return Json.createObjectBuilder().add("name", name).add("label", label).add("kind", kind)
            .add("mandatory", mandatory).add("multiline", multiline).build();
    }
}

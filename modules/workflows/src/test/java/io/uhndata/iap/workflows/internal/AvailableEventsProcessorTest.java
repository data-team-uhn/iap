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

import java.util.Set;
import java.util.TreeSet;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextBuilder;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.testing.WorkflowPlugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Unit tests for {@link AvailableEventsProcessor}: listing on each node what the engine says the requesting user
 * could send it, and never failing the serialization over it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class AvailableEventsProcessorTest
{
    private static final String PATH = "/Schemas/intake";

    private final SlingContext context = new SlingContextBuilder().plugin(WorkflowPlugins.MOCK_ENGINE).build();

    private AvailableEventsProcessor processor;

    private WorkflowEngine engine;

    private final Resource resource = Mockito.mock(Resource.class);

    private final Node node = Mockito.mock(Node.class);

    @BeforeEach
    void setUp() throws RepositoryException
    {
        this.processor = this.context.registerInjectActivateService(AvailableEventsProcessor.class);
        this.engine = this.context.getService(WorkflowEngine.class);
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        Mockito.when(this.resource.getResourceResolver()).thenReturn(resolver);
        Mockito.when(resolver.getResource(PATH)).thenReturn(this.resource);
        Mockito.when(this.node.getPath()).thenReturn(PATH);
        Mockito.when(this.node.isNodeType("data:Content")).thenReturn(true);
    }

    @Test
    void isTheOptInEventsProcessor()
    {
        assertEquals("events", this.processor.getName());
        assertEquals(50, this.processor.getPriority());
        assertFalse(this.processor.isEnabledByDefault(this.resource));
    }

    @Test
    void listsTheAvailableEvents() throws Exception
    {
        Mockito.when(this.engine.getAvailableEvents(this.resource)).thenReturn(new TreeSet<>(Set.of("update",
            "activate")));

        assertEquals(Json.createArrayBuilder().add("activate").add("update").build(),
            serialize().getJsonArray("@events"));
    }

    @Test
    void listsNothingWhenTheEngineCannotAnswer() throws Exception
    {
        Mockito.when(this.engine.getAvailableEvents(this.resource))
            .thenThrow(new WorkflowFailedException("no service user"));

        assertFalse(serialize().containsKey("@events"));
    }

    @Test
    void listsNothingWhenTheEngineFailsUnexpectedly() throws Exception
    {
        Mockito.when(this.engine.getAvailableEvents(this.resource))
            .thenThrow(new IllegalStateException("the engine's session was closed"));

        assertFalse(serialize().containsKey("@events"));
    }

    @Test
    void leavesOutWhatIsNotContent() throws Exception
    {
        Mockito.when(this.node.isNodeType("data:Content")).thenReturn(false);

        assertFalse(serialize().containsKey("@events"));
        Mockito.verifyNoInteractions(this.engine);
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

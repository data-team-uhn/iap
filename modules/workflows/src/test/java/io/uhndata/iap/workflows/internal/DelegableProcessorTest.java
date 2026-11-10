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

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowInstance;
import io.uhndata.iap.workflows.models.WorkflowInstances;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DelegableProcessor}: telling the reader of a task whether they may decide it for someone
 * else, asking once per serialization, and never failing the serialization over it.
 *
 * @version $Id$
 * @since 0.1.0
 */
class DelegableProcessorTest
{
    private static final String READER = "alice";

    private final Delegation delegation = Mockito.mock(Delegation.class);

    private final DelegableProcessor processor = new DelegableProcessor(this.delegation);

    private final Node task = Mockito.mock(Node.class);

    private final ResourceResolver reader = Mockito.mock(ResourceResolver.class);

    private final Resource root = Mockito.mock(Resource.class);

    @BeforeEach
    void setUp() throws RepositoryException
    {
        Mockito.when(this.task.isNodeType("wf:TaskInstance")).thenReturn(true);
        Mockito.when(this.reader.getUserID()).thenReturn(READER);
        Mockito.when(this.root.getResourceResolver()).thenReturn(this.reader);
    }

    @Test
    void isOnByDefault()
    {
        assertEquals("delegable", this.processor.getName());
        assertEquals(20, this.processor.getPriority());
        assertTrue(this.processor.isEnabledByDefault(this.root));
    }

    @Test
    void appliesToWorkflowTreesOnly()
    {
        assertTrue(this.processor.canProcess(ofType(WorkflowInstances.RESOURCE_TYPE)));
        assertTrue(this.processor.canProcess(ofType(WorkflowInstance.RESOURCE_TYPE)));
        assertTrue(this.processor.canProcess(ofType(TaskInstance.RESOURCE_TYPE)));
        assertFalse(this.processor.canProcess(ofType("sub/Submission")));
    }

    @Test
    void marksATaskADelegateReads() throws Exception
    {
        Mockito.when(this.delegation.isDelegate(this.reader, READER)).thenReturn(true);

        assertTrue(serialize(this.task).getBoolean(DelegableProcessor.DELEGABLE_KEY));
    }

    @Test
    void marksATaskSomeoneElseReads() throws Exception
    {
        Mockito.when(this.delegation.isDelegate(this.reader, READER)).thenReturn(false);

        assertFalse(serialize(this.task).getBoolean(DelegableProcessor.DELEGABLE_KEY));
    }

    @Test
    void asksAboutTheReaderOnceForAllTheTasksServed() throws Exception
    {
        Mockito.when(this.delegation.isDelegate(this.reader, READER)).thenReturn(true);

        this.processor.start(this.root);
        this.processor.leave(this.task, Json.createObjectBuilder(), child -> null);
        this.processor.leave(this.task, Json.createObjectBuilder(), child -> null);
        this.processor.end(this.root);

        Mockito.verify(this.delegation, Mockito.times(1)).isDelegate(this.reader, READER);
    }

    @Test
    void leavesOutWhatIsNotATask() throws Exception
    {
        Mockito.when(this.delegation.isDelegate(this.reader, READER)).thenReturn(true);
        final Node instance = Mockito.mock(Node.class);

        assertFalse(serialize(instance).containsKey(DelegableProcessor.DELEGABLE_KEY));
    }

    @Test
    void saysNothingWhenTheReaderCannotBeTold() throws Exception
    {
        Mockito.when(this.delegation.isDelegate(this.reader, READER))
            .thenThrow(new WorkflowFailedException("the user store is unreadable"));

        assertFalse(serialize(this.task).containsKey(DelegableProcessor.DELEGABLE_KEY));
    }

    @Test
    void saysNothingWhenTheNodeCannotBeTold() throws Exception
    {
        Mockito.when(this.delegation.isDelegate(this.reader, READER)).thenReturn(true);
        Mockito.when(this.task.isNodeType("wf:TaskInstance")).thenThrow(new RepositoryException("session closed"));

        assertFalse(serialize(this.task).containsKey(DelegableProcessor.DELEGABLE_KEY));
    }

    @Test
    void forgetsTheReaderOnceTheSerializationEnds() throws Exception
    {
        Mockito.when(this.delegation.isDelegate(this.reader, READER)).thenReturn(true);
        serialize(this.task);
        final JsonObjectBuilder afterwards = Json.createObjectBuilder();

        this.processor.leave(this.task, afterwards, child -> null);

        assertFalse(afterwards.build().containsKey(DelegableProcessor.DELEGABLE_KEY));
    }

    private static Resource ofType(final String type)
    {
        final Resource resource = Mockito.mock(Resource.class);
        Mockito.when(resource.isResourceType(type)).thenReturn(true);
        return resource;
    }

    private JsonObject serialize(final Node node)
    {
        final JsonObjectBuilder json = Json.createObjectBuilder();
        this.processor.start(this.root);
        this.processor.leave(node, json, child -> null);
        this.processor.end(this.root);
        return json.build();
    }
}

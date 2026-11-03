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

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowInstance;
import io.uhndata.iap.workflows.models.WorkflowInstances;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TaskPerformabilityProcessor}: telling the reader of a task whether they may decide it for
 * someone else, and never failing the serialization over it.
 *
 * @version $Id$
 * @since 0.1.0
 */
class TaskPerformabilityProcessorTest
{
    private final TaskPerformabilityProcessor processor = new TaskPerformabilityProcessor();

    private final Delegation delegation = Mockito.mock(Delegation.class);

    private final Node node = Mockito.mock(Node.class);

    private final Session session = Mockito.mock(Session.class);

    @BeforeEach
    void setUp() throws ReflectiveOperationException, RepositoryException
    {
        final Field reference = TaskPerformabilityProcessor.class.getDeclaredField("delegation");
        reference.setAccessible(true);
        reference.set(this.processor, this.delegation);
        Mockito.when(this.node.isNodeType("wf:TaskInstance")).thenReturn(true);
        Mockito.when(this.node.getSession()).thenReturn(this.session);
    }

    @Test
    void isOnByDefaultAfterDeep()
    {
        final Resource resource = Mockito.mock(Resource.class);

        assertEquals("taskPerformability", this.processor.getName());
        assertEquals(20, this.processor.getPriority());
        assertTrue(this.processor.isEnabledByDefault(resource));
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
        Mockito.when(this.delegation.isDelegate(this.session)).thenReturn(true);

        assertTrue(serialize().getBoolean(TaskPerformabilityProcessor.DELEGABLE_KEY));
    }

    @Test
    void marksATaskSomeoneElseReads() throws Exception
    {
        Mockito.when(this.delegation.isDelegate(this.session)).thenReturn(false);

        assertFalse(serialize().getBoolean(TaskPerformabilityProcessor.DELEGABLE_KEY));
    }

    @Test
    void leavesOutWhatIsNotATask() throws Exception
    {
        Mockito.when(this.node.isNodeType("wf:TaskInstance")).thenReturn(false);

        assertFalse(serialize().containsKey(TaskPerformabilityProcessor.DELEGABLE_KEY));
        Mockito.verifyNoInteractions(this.delegation);
    }

    @Test
    void saysNothingWhenTheReaderCannotBeTold() throws Exception
    {
        Mockito.when(this.delegation.isDelegate(this.session)).thenThrow(new RepositoryException("session closed"));

        assertFalse(serialize().containsKey(TaskPerformabilityProcessor.DELEGABLE_KEY));
    }

    private static Resource ofType(final String type)
    {
        final Resource resource = Mockito.mock(Resource.class);
        Mockito.when(resource.isResourceType(type)).thenReturn(true);
        return resource;
    }

    private JsonObject serialize()
    {
        final JsonObjectBuilder json = Json.createObjectBuilder();
        this.processor.leave(this.node, json, child -> null);
        return json.build();
    }
}

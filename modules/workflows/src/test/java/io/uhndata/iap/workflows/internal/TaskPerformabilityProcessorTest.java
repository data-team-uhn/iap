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

import java.security.Principal;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Value;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowInstances;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TaskPerformabilityProcessor}.
 *
 * <p>The fixtures deliberately make the reader and the task's performers disagree in each direction: a test where
 * everything names the same person would pass whether or not the comparison happens at all.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
class TaskPerformabilityProcessorTest
{
    private static final String TASK_TYPE = "wf:TaskInstance";

    private static final String READER = "priya";

    private final TaskPerformabilityProcessor processor = new TaskPerformabilityProcessor();

    private final Function<Node, JsonValue> serializeNode = node -> JsonValue.NULL;

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("taskPerformability", this.processor.getName());
    }

    @Test
    void runsAfterWhateverTurnsANestedTaskIntoJson()
    {
        // `deep` is 10, and a task nested in an instance is not there to annotate until it has run
        assertTrue(this.processor.getPriority() > 10);
    }

    @Test
    void appliesWhereverTasksAreRead()
    {
        assertTrue(this.processor.canProcess(resourceOfType(WorkflowInstances.RESOURCE_TYPE)));
        assertTrue(this.processor.canProcess(resourceOfType(TaskInstance.RESOURCE_TYPE)));
    }

    @Test
    void leavesOtherTreesAlone()
    {
        assertFalse(this.processor.canProcess(resourceOfType("sub/Submission")));
    }

    @Test
    void answersWithoutBeingAskedFor()
    {
        // Every reader of a task wants to know this, and none of them should have to remember a selector
        assertTrue(this.processor.isEnabledByDefault(resourceOfType(TaskInstance.RESOURCE_TYPE)));
    }

    @Test
    void saysATaskIsMineWhenItNamesSomethingIActAs() throws Exception
    {
        // Named through a group rather than by user id, which is the case a browser could not have worked out
        final Node task = task(true, "reviewers");

        assertTrue(annotate(task).getBoolean(TaskPerformabilityProcessor.FIELD));
    }

    @Test
    void saysATaskIsMineWhenItNamesMeOutright() throws Exception
    {
        final Node task = task(true, "someone-else", READER);

        assertTrue(annotate(task).getBoolean(TaskPerformabilityProcessor.FIELD));
    }

    @Test
    void saysATaskIsNotMineWhenItNamesSomebodyElse() throws Exception
    {
        final Node task = task(true, "finance-team");

        assertFalse(annotate(task).getBoolean(TaskPerformabilityProcessor.FIELD));
    }

    @Test
    void saysATaskIsNotMineWhenItNamesNobody() throws Exception
    {
        // Fail closed, the same reading the engine's own performer check applies: a definition has to say who
        final Node task = task(true);
        Mockito.when(task.hasProperty("performers")).thenReturn(false);

        assertFalse(annotate(task).getBoolean(TaskPerformabilityProcessor.FIELD));
    }

    @Test
    void readsAPerformerWrittenWithoutTheMultipleFlag() throws Exception
    {
        // The type declares the property multiple but also carries a residual, so a single-valued write is possible
        final Node task = task(false, "reviewers");

        assertTrue(annotate(task).getBoolean(TaskPerformabilityProcessor.FIELD));
    }

    @Test
    void saysNothingAtAllAboutANodeThatIsNotATask() throws Exception
    {
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.isNodeType(TASK_TYPE)).thenReturn(false);

        assertFalse(annotate(node).containsKey(TaskPerformabilityProcessor.FIELD));
    }

    @Test
    void answersNothingRatherThanFailingTheWholeSerialization() throws Exception
    {
        // A missing field offers no control, which is what a task that is not mine looks like anyway. Taking the
        // submission's page down over one button would be the worse answer
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.isNodeType(TASK_TYPE)).thenThrow(new RepositoryException("no"));

        assertFalse(annotate(node).containsKey(TaskPerformabilityProcessor.FIELD));
    }

    /**
     * Runs the processor over one node and returns what it added.
     *
     * @param node the node to serialize
     * @return the resulting JSON object
     */
    private JsonObject annotate(final Node node)
    {
        final JsonObjectBuilder json = Json.createObjectBuilder();
        this.processor.leave(node, json, this.serializeNode);
        return json.build();
    }

    /**
     * A task waiting for the given principals, read by a session that acts as {@code priya} and {@code reviewers}.
     *
     * @param multiple whether the property reports itself as multi-valued
     * @param performers the principals the task names
     * @return the mocked task node
     * @throws RepositoryException never, but the API being stubbed declares it
     */
    private Node task(final boolean multiple, final String... performers) throws RepositoryException
    {
        final Node task = Mockito.mock(Node.class);
        Mockito.when(task.isNodeType(TASK_TYPE)).thenReturn(true);
        Mockito.when(task.hasProperty("performers")).thenReturn(true);

        final JackrabbitSession session = Mockito.mock(JackrabbitSession.class);
        Mockito.when(session.getUserID()).thenReturn(READER);
        final Set<Principal> bound = new LinkedHashSet<>();
        bound.add((Principal) () -> READER);
        bound.add((Principal) () -> "reviewers");
        Mockito.when(session.getBoundPrincipals()).thenReturn(bound);
        Mockito.when(task.getSession()).thenReturn(session);

        final Property property = Mockito.mock(Property.class);
        Mockito.when(property.isMultiple()).thenReturn(multiple);
        if (multiple) {
            final Value[] values = new Value[performers.length];
            for (int i = 0; i < performers.length; ++i) {
                values[i] = value(performers[i]);
            }
            Mockito.when(property.getValues()).thenReturn(values);
        } else {
            // Hoisted: stubbing inside a thenReturn argument is nested stubbing, which Mockito rejects
            final Value only = value(performers[0]);
            Mockito.when(property.getValue()).thenReturn(only);
        }
        Mockito.when(task.getProperty("performers")).thenReturn(property);
        return task;
    }

    private Value value(final String string) throws RepositoryException
    {
        final Value value = Mockito.mock(Value.class);
        Mockito.when(value.getString()).thenReturn(string);
        return value;
    }

    private Resource resourceOfType(final String resourceType)
    {
        final Resource resource = Mockito.mock(Resource.class);
        Mockito.when(resource.isResourceType(Mockito.anyString()))
            .thenAnswer(call -> resourceType.equals(call.getArgument(0)));
        return resource;
    }
}

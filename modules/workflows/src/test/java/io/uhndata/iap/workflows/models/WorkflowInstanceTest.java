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
package io.uhndata.iap.workflows.models;

import java.util.Calendar;
import java.util.Map;

import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.conditions.spi.OperandResolver;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;

import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WorkflowInstance}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class WorkflowInstanceTest
{
    private static final String PATH = "/WorkflowInstances/i1";

    private static final String VERSION_ID = "workflow-version-uuid";

    private final SlingContext context = new SlingContext();

    private Calendar started;

    private Calendar ended;

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
        this.started = Calendar.getInstance();
        this.started.set(2026, Calendar.JULY, 20, 9, 0, 0);
        this.ended = Calendar.getInstance();
        this.ended.set(2026, Calendar.JULY, 22, 17, 0, 0);
    }

    @Test
    void isAPartOfItsHostRatherThanARecordOfItsOwn()
    {
        // The ruling this type exists under: a running process belongs to the thing it runs over, it cannot
        // exist without it, and it is deleted with it. Being an entity bought nothing — nothing references an
        // instance, versions one, or reads an identifier off it — and cost the condition below.
        final Resource instance = this.context.create().resource(PATH, Map.of(
            TYPE, WorkflowInstance.RESOURCE_TYPE, "status", "active"));

        assertFalse(instance.isResourceType(Entity.RESOURCE_TYPE));
        assertTrue(instance.isResourceType("data/EntityPart"));
    }

    @Test
    void letsAConditionReachPastTheProcessToTheRecordItGuards()
    {
        // What the demotion is for. A gateway guard is evaluated against the instance, and the generic
        // resolvers ask for the enclosing entity: while the instance was one, the walk stopped there and a
        // guard could not read a property of the request it was guarding. Now it walks through to the host.
        final Resource host = this.context.create().resource("/Requests/r1", Map.of(
            TYPE, "test/Request", WorkflowFixture.SUPER_TYPE, Entity.RESOURCE_TYPE, "daysRequested", 31L));
        this.context.create().resource("/Requests/r1/wf:instances", Map.of(
            TYPE, WorkflowInstances.RESOURCE_TYPE));
        final Resource instance = this.context.create().resource("/Requests/r1/wf:instances/i1", Map.of(
            TYPE, WorkflowInstance.RESOURCE_TYPE, "status", "active"));
        final Resource task = this.context.create().resource("/Requests/r1/wf:instances/i1/task_1_1", Map.of(
            TYPE, TaskInstance.RESOURCE_TYPE, "taskDefinitionId", "approve", "status", "open"));

        final Content fromInstance = OperandResolver.findEnclosingEntity(instance.adaptTo(WorkflowInstance.class));
        final Content fromTask = OperandResolver.findEnclosingEntity(task.adaptTo(TaskInstance.class));

        assertNotNull(fromInstance);
        assertEquals(host.getPath(), fromInstance.getPath());
        // The same from a task, which is a part of a part: the walk does not stop at the process either
        assertNotNull(fromTask);
        assertEquals(host.getPath(), fromTask.getPath());
    }

    @Test
    void exposesInstanceProperties()
    {
        final Resource resource = this.context.create().resource(PATH, Map.of(
            TYPE, WorkflowInstance.RESOURCE_TYPE,
            "status", "completed",
            "startTime", this.started,
            "endTime", this.ended));
        final WorkflowInstance instance = resource.adaptTo(WorkflowInstance.class);

        assertNotNull(instance);
        assertEquals("completed", instance.getStatus());
        assertEquals(this.started, instance.getStartTime());
        assertEquals(this.ended, instance.getEndTime());
    }

    @Test
    void handsOutCopiesOfItsDates()
    {
        // Calendar is mutable, so a caller must not be able to reach back into the model's own state
        final Resource resource = this.context.create().resource(PATH, Map.of(
            TYPE, WorkflowInstance.RESOURCE_TYPE, "status", "active",
            "startTime", this.started, "endTime", this.ended));
        final WorkflowInstance instance = resource.adaptTo(WorkflowInstance.class);

        assertNotSame(instance.getStartTime(), instance.getStartTime());
        assertNotSame(instance.getEndTime(), instance.getEndTime());
    }

    @Test
    void toleratesAnInstanceThatHasNotFinished()
    {
        final Resource resource = this.context.create().resource(PATH, TYPE,
            WorkflowInstance.RESOURCE_TYPE, "status", "active");
        final WorkflowInstance instance = resource.adaptTo(WorkflowInstance.class);

        assertNull(instance.getStartTime());
        assertNull(instance.getEndTime());
        assertNull(instance.getWorkflowVersion());
        assertTrue(instance.getTokens().isEmpty());
        assertTrue(instance.getVariables().isEmpty());
        assertTrue(instance.getTaskInstances().isEmpty());
        assertNull(instance.getVariable("anything"));
    }

    @Test
    void resolvesTheVersionItIsExecuting()
        throws RepositoryException
    {
        this.context.create().resource("/Workflows/timeOff/1.0", Map.of(
            TYPE, WorkflowVersion.RESOURCE_TYPE, "version", "1.0"));
        WorkflowFixture.resolveReference(this.context, VERSION_ID, "/Workflows/timeOff/1.0");

        final Resource resource = this.context.create().resource(PATH, Map.of(
            TYPE, WorkflowInstance.RESOURCE_TYPE, "status", "active", "workflowVersion", VERSION_ID));

        assertEquals("1.0", resource.adaptTo(WorkflowInstance.class).getWorkflowVersion().getVersion());
    }

    @Test
    void listsItsTokensVariablesAndTasks()
    {
        final Resource resource = this.context.create().resource(PATH, Map.of(
            TYPE, WorkflowInstance.RESOURCE_TYPE, "status", "active"));
        this.context.create().resource(PATH + "/t1", Map.of(
            TYPE, WorkflowToken.RESOURCE_TYPE, "currentNodeId", "task_1"));
        this.context.create().resource(PATH + "/requestedDays", Map.of(
            TYPE, Variable.RESOURCE_TYPE, "dataType", Variable.TYPE_LONG, "longValue", 3L));
        this.context.create().resource(PATH + "/task_1_1", Map.of(
            TYPE, TaskInstance.RESOURCE_TYPE, "taskDefinitionId", "task_1", "label", "Approve",
            "status", "created"));
        final WorkflowInstance instance = resource.adaptTo(WorkflowInstance.class);

        assertEquals(1, instance.getTokens().size());
        assertEquals("task_1", instance.getTokens().get(0).getCurrentNodeId());
        assertEquals(1, instance.getVariables().size());
        assertEquals(1, instance.getTaskInstances().size());
        assertEquals("Approve", instance.getTaskInstances().get(0).getLabel());
    }

    @Test
    void looksAVariableUpByName()
    {
        final Resource resource = this.context.create().resource(PATH, Map.of(
            TYPE, WorkflowInstance.RESOURCE_TYPE, "status", "active"));
        this.context.create().resource(PATH + "/requestedDays", Map.of(
            TYPE, Variable.RESOURCE_TYPE, "dataType", Variable.TYPE_LONG, "longValue", 3L));
        final WorkflowInstance instance = resource.adaptTo(WorkflowInstance.class);

        assertEquals(3L, instance.getVariable("requestedDays").getValue());
        assertNull(instance.getVariable("noSuchVariable"));
    }

    @Test
    void doesNotMistakeATokenOrATaskForAVariable()
    {
        // Tokens, variables and tasks are all residual children named by the engine, so a name says nothing about
        // what will be found under it; without a type check these would adapt to a Variable with no value at all
        final Resource resource = this.context.create().resource(PATH, Map.of(
            TYPE, WorkflowInstance.RESOURCE_TYPE, "status", "active"));
        this.context.create().resource(PATH + "/t1", Map.of(
            TYPE, WorkflowToken.RESOURCE_TYPE, "currentNodeId", "task_1"));
        this.context.create().resource(PATH + "/task_1_1", Map.of(
            TYPE, TaskInstance.RESOURCE_TYPE, "taskDefinitionId", "task_1", "label", "Approve",
            "status", "created"));
        final WorkflowInstance instance = resource.adaptTo(WorkflowInstance.class);

        assertNull(instance.getVariable("t1"));
        assertNull(instance.getVariable("task_1_1"));
    }
}

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

import io.uhndata.iap.content.models.Content;

import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link TaskInstance}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class TaskInstanceTest
{
    private static final String INSTANCE_PATH = "/WorkflowInstances/i1";

    private static final String TASK_PATH = INSTANCE_PATH + "/task_1_1";

    private static final String VERSION_ID = "workflow-version-uuid";

    private static final String FORM_ID = "form-uuid";

    private final SlingContext context = new SlingContext();

    private Calendar started;

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
        this.started = Calendar.getInstance();
        this.started.set(2026, Calendar.JULY, 20, 9, 0, 0);
    }

    @Test
    void exposesTaskProperties()
    {
        this.context.create().resource(INSTANCE_PATH, TYPE, WorkflowInstance.RESOURCE_TYPE, "status", "active");
        final Resource resource = this.context.create().resource(TASK_PATH, Map.of(
            TYPE, TaskInstance.RESOURCE_TYPE,
            "taskDefinitionId", "task_1",
            "label", "Approve the request",
            "assignee", "alice",
            "status", "completed",
            "startTime", this.started,
            "endTime", this.started,
            "dueDate", this.started,
            "outcome", "approved"));
        final TaskInstance task = resource.adaptTo(TaskInstance.class);

        assertNotNull(task);
        assertEquals("task_1", task.getTaskDefinitionId());
        assertEquals("Approve the request", task.getLabel());
        assertEquals("alice", task.getAssignee());
        assertEquals("completed", task.getStatus());
        assertEquals(this.started, task.getStartTime());
        assertEquals(this.started, task.getEndTime());
        assertEquals(this.started, task.getDueDate());
        assertEquals("approved", task.getOutcome());
    }

    @Test
    void handsOutCopiesOfItsDates()
    {
        final Resource resource = this.context.create().resource(TASK_PATH, Map.of(
            TYPE, TaskInstance.RESOURCE_TYPE, "taskDefinitionId", "task_1", "label", "Approve",
            "status", "created", "startTime", this.started, "endTime", this.started,
            "dueDate", this.started));
        final TaskInstance task = resource.adaptTo(TaskInstance.class);

        assertNotSame(task.getStartTime(), task.getStartTime());
        assertNotSame(task.getEndTime(), task.getEndTime());
        assertNotSame(task.getDueDate(), task.getDueDate());
    }

    @Test
    void toleratesAnOpenUnassignedTask()
    {
        final Resource resource = this.context.create().resource(TASK_PATH, Map.of(
            TYPE, TaskInstance.RESOURCE_TYPE, "taskDefinitionId", "task_1", "label", "Approve",
            "status", "created"));
        final TaskInstance task = resource.adaptTo(TaskInstance.class);

        assertNull(task.getAssignee());
        assertNull(task.getStartTime());
        assertNull(task.getEndTime());
        assertNull(task.getDueDate());
        assertNull(task.getOutcome());
        assertNull(task.getForm());
        assertNull(task.getWorkflowInstance());
        assertNull(task.getDefinition());
    }

    @Test
    void resolvesItsFormAsGenericContent()
        throws RepositoryException
    {
        // Deliberately something from another module's namespace: the workflows module must not need to know
        // what kind of thing a task's form is
        this.context.create().resource("/Submissions/s1", TYPE, "sub/Submission");
        WorkflowFixture.resolveReference(this.context, FORM_ID, "/Submissions/s1");
        final Resource resource = this.context.create().resource(TASK_PATH, Map.of(
            TYPE, TaskInstance.RESOURCE_TYPE, "taskDefinitionId", "task_1", "label", "Approve",
            "status", "created", "form", FORM_ID));

        final Content form = resource.adaptTo(TaskInstance.class).getForm();

        assertNotNull(form);
        assertEquals("/Submissions/s1", form.getPath());
    }

    @Test
    void resolvesTheActivityItWasRaisedFrom()
        throws RepositoryException
    {
        this.createDefinition("task_1", Activity.RESOURCE_TYPE);
        final Resource resource = this.context.create().resource(TASK_PATH, Map.of(
            TYPE, TaskInstance.RESOURCE_TYPE, "taskDefinitionId", "task_1", "label", "Approve",
            "status", "created"));

        final Activity definition = resource.adaptTo(TaskInstance.class).getDefinition();

        assertNotNull(definition);
        assertEquals("task_1", definition.getElementId());
    }

    @Test
    void hasNoDefinitionWhenTheNamedNodeIsNotAnActivity()
        throws RepositoryException
    {
        // A task pointing at, say, a gateway is a corrupt state rather than a different kind of task, so it is
        // reported as having no definition instead of being force-fitted
        this.createDefinition("task_1", ExclusiveGateway.RESOURCE_TYPE);
        final Resource resource = this.context.create().resource(TASK_PATH, Map.of(
            TYPE, TaskInstance.RESOURCE_TYPE, "taskDefinitionId", "task_1", "label", "Approve",
            "status", "created"));

        assertNull(resource.adaptTo(TaskInstance.class).getDefinition());
    }

    @Test
    void hasNoDefinitionWhenItNamesAnUnknownNode()
        throws RepositoryException
    {
        this.createDefinition("task_1", Activity.RESOURCE_TYPE);
        final Resource resource = this.context.create().resource(TASK_PATH, Map.of(
            TYPE, TaskInstance.RESOURCE_TYPE, "taskDefinitionId", "no_such_node", "label", "Approve",
            "status", "created"));

        assertNull(resource.adaptTo(TaskInstance.class).getDefinition());
    }

    private void createDefinition(final String elementId, final String nodeResourceType)
        throws RepositoryException
    {
        this.context.create().resource("/Workflows/timeOff/1.0", TYPE, WorkflowVersion.RESOURCE_TYPE,
            "version", "1.0");
        this.context.create().resource("/Workflows/timeOff/1.0/" + elementId, Map.of(
            TYPE, nodeResourceType, "elementId", elementId));
        WorkflowFixture.resolveReference(this.context, VERSION_ID, "/Workflows/timeOff/1.0");
        this.context.create().resource(INSTANCE_PATH, Map.of(
            TYPE, WorkflowInstance.RESOURCE_TYPE, "status", "active", "workflowVersion", VERSION_ID));
    }
}

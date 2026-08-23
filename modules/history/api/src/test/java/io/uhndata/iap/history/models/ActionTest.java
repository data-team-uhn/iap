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
package io.uhndata.iap.history.models;

import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Action}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ActionTest
{
    private static final String PATH = "/History/ab/cd/ef/action";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Action.class, Entry.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource(PATH,
            "sling:resourceType", Action.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(Action.class));
    }

    @Test
    void exposesTheCauseOfTheChange()
    {
        final Resource resource = this.context.create().resource(PATH, Map.of(
            "sling:resourceType", Action.RESOURCE_TYPE,
            "actor", "reviewer1",
            "onBehalfOf", "submitter1",
            "operation", "activateVersion",
            "workflowInstance", "11111111-1111-1111-1111-111111111111",
            "workflowVersion", "22222222-2222-2222-2222-222222222222",
            "activityId", "Activity_retireAndActivate",
            "activityLabel", "Switch the active version",
            "taskInstance", "33333333-3333-3333-3333-333333333333",
            "outcome", "approved"));
        final Action action = resource.adaptTo(Action.class);

        assertEquals("reviewer1", action.getActor());
        assertEquals("submitter1", action.getOnBehalfOf());
        assertEquals("activateVersion", action.getOperation());
        assertEquals("11111111-1111-1111-1111-111111111111", action.getWorkflowInstance());
        assertEquals("22222222-2222-2222-2222-222222222222", action.getWorkflowVersion());
        assertEquals("Activity_retireAndActivate", action.getActivityId());
        assertEquals("Switch the active version", action.getActivityLabel());
        assertEquals("33333333-3333-3333-3333-333333333333", action.getTaskInstance());
        assertEquals("approved", action.getOutcome());
    }

    @Test
    void exposesWhatWasSaidAndWhoAskedWhenNoWorkflowDid()
    {
        final Resource resource = this.context.create().resource(PATH, Map.of(
            "sling:resourceType", Action.RESOURCE_TYPE,
            "actor", "iap-history",
            "operation", "purgeExpired",
            "outcomeNote", "Refused: the budget letter is still missing",
            "event", "submissionApproved",
            "component", "io.uhndata.iap.deletion.internal.RetentionSweep",
            "parentAction", "44444444-4444-4444-4444-444444444444"));
        final Action action = resource.adaptTo(Action.class);

        assertEquals("Refused: the budget letter is still missing", action.getOutcomeNote());
        assertEquals("submissionApproved", action.getEvent());
        assertEquals("io.uhndata.iap.deletion.internal.RetentionSweep", action.getComponent());
        assertEquals("44444444-4444-4444-4444-444444444444", action.getParentAction());
    }

    @Test
    void readsAsBlankRatherThanFailingWhenTheRecordSaysNothing()
    {
        final Resource resource = this.context.create().resource(PATH,
            "sling:resourceType", Action.RESOURCE_TYPE);
        final Action action = resource.adaptTo(Action.class);

        assertEquals("", action.getActor());
        assertEquals("", action.getOperation());
        assertNull(action.getOnBehalfOf());
        assertNull(action.getWorkflowInstance());
        assertNull(action.getWorkflowVersion());
        assertNull(action.getActivityId());
        assertNull(action.getActivityLabel());
        assertNull(action.getTaskInstance());
        assertNull(action.getOutcome());
        assertNull(action.getOutcomeNote());
        assertNull(action.getEvent());
        assertNull(action.getComponent());
        assertNull(action.getParentAction());
    }

    @Test
    void isNotCompleteUntilItSaysSo()
    {
        final Resource resource = this.context.create().resource(PATH,
            "sling:resourceType", Action.RESOURCE_TYPE);
        assertFalse(resource.adaptTo(Action.class).isComplete(),
            "An action whose record does not say must be treated as still owing its snapshots");
    }

    @Test
    void isCompleteOnceTheSnapshotsAreDoneWith()
    {
        final Resource resource = this.context.create().resource(PATH, Map.of(
            "sling:resourceType", Action.RESOURCE_TYPE,
            "complete", Boolean.TRUE));
        assertTrue(resource.adaptTo(Action.class).isComplete());
    }

    @Test
    void listsWhatItDidToEachResource()
    {
        final Resource resource = this.context.create().resource(PATH,
            "sling:resourceType", Action.RESOURCE_TYPE);
        this.context.create().resource(PATH + "/oldVersion", Map.of(
            "sling:resourceType", Entry.RESOURCE_TYPE,
            "subject", "aaaa", "subjectPath", "/Workflows/timeOff/1.0",
            "subjectType", "wf:WorkflowVersion", "role", "retired"));
        this.context.create().resource(PATH + "/newVersion", Map.of(
            "sling:resourceType", Entry.RESOURCE_TYPE,
            "subject", "bbbb", "subjectPath", "/Workflows/timeOff/2.0",
            "subjectType", "wf:WorkflowVersion", "role", "activated"));

        assertEquals(2, resource.adaptTo(Action.class).getEntries().size());
    }

    @Test
    void hasNoEntriesWhenItAffectedNothing()
    {
        final Resource resource = this.context.create().resource(PATH,
            "sling:resourceType", Action.RESOURCE_TYPE);
        assertTrue(resource.adaptTo(Action.class).getEntries().isEmpty());
    }
}

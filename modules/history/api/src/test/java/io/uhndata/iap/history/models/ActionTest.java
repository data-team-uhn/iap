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

import java.util.Calendar;
import java.util.GregorianCalendar;
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
            "createdBy", "reviewer1",
            "onBehalfOf", "submitter1",
            "operation", "activate",
            "workflowInstance", "/Submissions/aStudy/wf:instances/review",
            "workflowVersion", "22222222-2222-2222-2222-222222222222",
            "activityId", "Activity_retireAndActivate",
            "activityLabel", "Switch the active version",
            "taskInstance", "/Submissions/aStudy/wf:instances/review/approveStudy",
            "outcome", "approved"));
        final Action action = resource.adaptTo(Action.class);

        assertEquals("reviewer1", action.getRequestedBy());
        assertEquals("submitter1", action.getOnBehalfOf());
        assertEquals("activate", action.getOperation());
        assertEquals("/Submissions/aStudy/wf:instances/review", action.getWorkflowInstance());
        assertEquals("22222222-2222-2222-2222-222222222222", action.getWorkflowVersion());
        assertEquals("Activity_retireAndActivate", action.getActivityId());
        assertEquals("Switch the active version", action.getActivityLabel());
        assertEquals("/Submissions/aStudy/wf:instances/review/approveStudy", action.getTaskInstance());
        assertEquals("approved", action.getOutcome());
    }

    @Test
    void exposesWhatWasSaidAndWhoAskedWhenNoWorkflowDid()
    {
        final Resource resource = this.context.create().resource(PATH, Map.of(
            "sling:resourceType", Action.RESOURCE_TYPE,
            "createdBy", "iap-history",
            "operation", "purgeExpired",
            "outcomeNote", "Refused: the budget letter is still missing",
            "event", "submissionApproved",
            "component", "io.uhndata.iap.deletion.internal.RetentionSweep",
            "parentAction", "/History/44/44/44/44444444-4444-4444-4444-444444444444"));
        final Action action = resource.adaptTo(Action.class);

        assertEquals("Refused: the budget letter is still missing", action.getOutcomeNote());
        assertEquals("submissionApproved", action.getEvent());
        assertEquals("io.uhndata.iap.deletion.internal.RetentionSweep", action.getComponent());
        assertEquals("/History/44/44/44/44444444-4444-4444-4444-444444444444", action.getParentAction());
    }

    @Test
    void readsAsBlankRatherThanFailingWhenTheRecordSaysNothing()
    {
        final Resource resource = this.context.create().resource(PATH,
            "sling:resourceType", Action.RESOURCE_TYPE);
        final Action action = resource.adaptTo(Action.class);

        assertEquals("", action.getRequestedBy());
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
        assertNull(action.getOccurredAt());
    }

    @Test
    void happenedWhenTheRecordSays()
    {
        final Calendar happened = new GregorianCalendar(2026, Calendar.MARCH, 2, 9, 0);
        final Resource resource = this.context.create().resource(PATH, Map.of(
            "sling:resourceType", Action.RESOURCE_TYPE,
            "occurredAt", happened,
            "jcr:created", new GregorianCalendar(2026, Calendar.APRIL, 1, 0, 0)));

        assertEquals(happened, resource.adaptTo(Action.class).getOccurredAt());
    }

    @Test
    void happenedWhenItWasRecordedUnlessTheRecordSaysOtherwise()
    {
        final Calendar recorded = new GregorianCalendar(2026, Calendar.APRIL, 1, 0, 0);
        final Resource resource = this.context.create().resource(PATH, Map.of(
            "sling:resourceType", Action.RESOURCE_TYPE,
            "jcr:created", recorded));

        assertEquals(recorded, resource.adaptTo(Action.class).getOccurredAt());
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
            "subject", "aaaa", "subjectPath", "/Workflows/timeOff/v1",
            "subjectType", "wf:WorkflowVersion", "role", "retired"));
        this.context.create().resource(PATH + "/newVersion", Map.of(
            "sling:resourceType", Entry.RESOURCE_TYPE,
            "subject", "bbbb", "subjectPath", "/Workflows/timeOff/v2",
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

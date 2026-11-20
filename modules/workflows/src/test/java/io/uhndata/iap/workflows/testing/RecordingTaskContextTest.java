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
package io.uhndata.iap.workflows.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.testing.RecordingTaskContext.RecordedRole;
import io.uhndata.iap.workflows.testing.RecordingTaskContext.SentEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link RecordingTaskContext}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class RecordingTaskContextTest
{
    private final SlingContext context = new SlingContext();

    private Resource target;

    @BeforeEach
    void setUp()
    {
        this.target = this.context.create().resource("/Submissions/aLongWeekend");
    }

    @Test
    void answersATestEventFromAdminByDefault()
    {
        final RecordingTaskContext task = RecordingTaskContext.aimedAt(this.target).build();

        assertSame(this.target, task.getTarget());
        assertEquals("test", task.getEvent().getName());
        assertEquals(Map.of(), task.getEvent().getPayload());
        assertEquals("admin", task.getActor());
        assertSame(this.context.resourceResolver(), task.getResourceResolver());
        assertEquals(Map.of(), task.getVariables());
    }

    @Test
    void performsAnActivityThatConfiguresNothingByDefault() throws Exception
    {
        final RecordingTaskContext task = RecordingTaskContext.aimedAt(this.target).build();

        assertEquals(RecordingTaskContext.UNCONFIGURED_ACTIVITY_PATH, task.getActivity().getPath());
        assertNull(task.getActivity().get("handler"));
        assertSame(this.target, task.getActivity().actsOn(task));
    }

    @Test
    void answersWhatItWasGiven()
    {
        final Activity activity = Mockito.mock(Activity.class);
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);

        final RecordingTaskContext task = RecordingTaskContext.aimedAt(this.target)
            .forEvent("save", Map.of("title", "A long weekend"))
            .actingFor("demo-requester")
            .performing(activity)
            .resolvingThrough(resolver)
            .withVariable("outcome", "approved")
            .build();

        assertEquals("save", task.getEvent().getName());
        assertEquals(Map.of("title", "A long weekend"), task.getEvent().getPayload());
        assertEquals("demo-requester", task.getActor());
        assertSame(activity, task.getActivity());
        assertSame(resolver, task.getResourceResolver());
        assertEquals("approved", task.getVariable("outcome"));
    }

    @Test
    void refusesToInventAResolverForATargetWithoutOne()
    {
        final RecordingTaskContext task = RecordingTaskContext.aimedAt(Mockito.mock(Resource.class)).build();

        assertThrows(IllegalStateException.class, task::getResourceResolver);
    }

    @Test
    void findsWhatAnEarlierStepCreated() throws Exception
    {
        final Resource created = this.context.create().resource("/Submissions/aLongWeekend/answers");

        final RecordingTaskContext task = RecordingTaskContext.aimedAt(this.target)
            .havingCreated(created.getPath())
            .build();

        assertEquals(created.getPath(), task.getCreated().getPath());
        assertEquals(created.getPath(), task.getActivity().actsOn(task).getPath());
    }

    @Test
    void recordsTheVariablesTheHandlerSets()
    {
        final RecordingTaskContext task = RecordingTaskContext.aimedAt(this.target)
            .withVariable("outcome", "pending")
            .build();

        task.setVariable("outcome", "approved");
        task.setVariable("createdPath", "/Submissions/aLongWeekend");

        assertEquals(Map.of("outcome", "approved", "createdPath", "/Submissions/aLongWeekend"), task.getVariables());
    }

    @Test
    void keepsTheVariablesOfEachContextApart()
    {
        final RecordingTaskContext.Builder builder = RecordingTaskContext.aimedAt(this.target);
        final RecordingTaskContext first = builder.build();
        final RecordingTaskContext second = builder.build();

        first.setVariable("outcome", "approved");

        assertNull(second.getVariable("outcome"));
    }

    @Test
    void recordsEveryRoleInOrder()
    {
        final Resource answers = this.context.create().resource("/Submissions/aLongWeekend/answers");
        final RecordingTaskContext task = RecordingTaskContext.aimedAt(this.target).build();

        task.recordRole(this.target, "submitted");
        task.recordRole(answers, "answered");

        assertEquals(List.of(new RecordedRole(this.target.getPath(), "submitted"),
            new RecordedRole(answers.getPath(), "answered")), task.getRoles());
    }

    @Test
    void keepsASecondRoleForTheSameResource()
    {
        final RecordingTaskContext task = RecordingTaskContext.aimedAt(this.target).build();

        task.recordRole(this.target, "submitted");
        task.recordRole(this.target, "submitted");

        assertEquals(List.of(new RecordedRole(this.target.getPath(), "submitted"),
            new RecordedRole(this.target.getPath(), "submitted")), task.getRoles());
    }

    @Test
    void recordsTheEventsSentOnInOrder()
    {
        final Resource other = this.context.create().resource("/Schemas/study");
        final WorkflowEvent first = new WorkflowEvent("createVersion", Map.of());
        final WorkflowEvent second = new WorkflowEvent("retire", Map.of());
        final RecordingTaskContext task = RecordingTaskContext.aimedAt(this.target).build();

        task.sendEvent(other, first);
        task.sendEvent(this.target, second);

        assertEquals(List.of(new SentEvent(other, first), new SentEvent(this.target, second)), task.getSentEvents());
    }

    @Test
    void leavesStartingWorkflowsToTheEngine()
    {
        final RecordingTaskContext task = RecordingTaskContext.aimedAt(this.target).build();
        final WorkflowVersion version = Mockito.mock(WorkflowVersion.class);

        assertThrows(IllegalStateException.class, () -> task.startWorkflow(this.target, version));
        assertThrows(IllegalStateException.class, () -> task.replaceWorkflow(this.target, version));
    }

    @Test
    void runsTheWorkLeftForAfterTheCommitOnlyWhenAskedAndInOrder()
    {
        final List<String> done = new ArrayList<>();
        final Runnable notify = () -> done.add("notify");
        final Runnable checkIn = () -> done.add("check in");
        final RecordingTaskContext task = RecordingTaskContext.aimedAt(this.target).build();

        task.afterCommit(notify);
        task.afterCommit(checkIn);

        assertEquals(List.of(), done);
        assertEquals(List.of(notify, checkIn), task.getAfterCommitWork());
        task.runAfterCommit();
        assertEquals(List.of("notify", "check in"), done);
    }

    @Test
    void runsTheRollbackOnlyWhenAskedAndLastRegisteredFirst()
    {
        final List<String> undone = new ArrayList<>();
        final Runnable checkOut = () -> undone.add("check out");
        final Runnable move = () -> undone.add("move");
        final RecordingTaskContext task = RecordingTaskContext.aimedAt(this.target).build();

        task.onRollback(checkOut);
        task.onRollback(move);

        assertEquals(List.of(), undone);
        assertEquals(List.of(checkOut, move), task.getRollbackActions());
        task.runRollback();
        assertEquals(List.of("move", "check out"), undone);
    }

    @Test
    void keepsItsRecordingsOutOfTheTestsReach()
    {
        final RecordingTaskContext task = RecordingTaskContext.aimedAt(this.target).build();

        assertThrows(UnsupportedOperationException.class, () -> task.getVariables().put("outcome", "approved"));
        assertThrows(UnsupportedOperationException.class, () -> task.getRoles().clear());
        assertThrows(UnsupportedOperationException.class, () -> task.getSentEvents().clear());
        assertThrows(UnsupportedOperationException.class, () -> task.getAfterCommitWork().clear());
        assertThrows(UnsupportedOperationException.class, () -> task.getRollbackActions().clear());
    }
}

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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.errortracking.api.ErrorLoggerService;
import io.uhndata.iap.notifications.api.NotificationContext;
import io.uhndata.iap.notifications.api.NotificationService;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.Event;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowFixture;
import io.uhndata.iap.workflows.models.WorkflowInstance;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for what the engine does once a delivery has committed: send the notifications its message events
 * announced, and do the work its steps left. A stand-in task completion announces the messages and performs the
 * activities a test names: the mock repository cannot undo a lost attempt, so a real walk cannot be retried here.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class AfterCommitTest
{
    private static final String TASK = "/Submissions/request/wf:instances/process/approve";

    private static final WorkflowEvent DECIDED = new WorkflowEvent(TaskCompletion.COMPLETE_EVENT, Map.of());

    private static final String DECISION = "decided";

    private static final String APPROVERS = "approvers";

    private static final String LEAVE_WORK = "leaveWork";

    private static final String BROKEN = "broken";

    /** Lets every commit through. */
    private static final EngineFixture.RepositoryAction NO_RACE = () -> { };

    /** Refuses every commit as one that lost a race. */
    private static final EngineFixture.RepositoryAction LOSING_EVERY_RACE = () -> {
        throw EngineFixture.conflict();
    };

    private final SlingContext context = new SlingContext();

    private final RecordedNotifications told = new RecordedNotifications();

    private final ErrorLoggerService recorder = Mockito.mock(ErrorLoggerService.class);

    /** The work done after the commit, named by the activity that left it. */
    private final List<String> done = new ArrayList<>();

    /**
     * A handler leaving work for after the commit: recording its activity's name as done, or failing for the broken
     * one.
     */
    private final ServiceTaskHandler leavingWork = new ServiceTaskHandler()
    {
        @Override
        public String getName()
        {
            return LEAVE_WORK;
        }

        @Override
        public void execute(final WorkflowTaskContext step)
        {
            final String name = step.getActivity().getName();
            step.afterCommit(() -> {
                if (BROKEN.equals(name)) {
                    throw new IllegalStateException("The work failed");
                }
                AfterCommitTest.this.done.add(name);
            });
        }
    };

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
        this.context.create().resource("/Submissions/request", TYPE, "sub/Submission");
        this.context.create().resource("/Submissions/request/wf:instances/process", TYPE,
            WorkflowInstance.RESOURCE_TYPE);
        this.context.create().resource(TASK, TYPE, TaskInstance.RESOURCE_TYPE);
        ErrorLogger.setService(this.recorder);
    }

    @AfterEach
    void tearDown()
    {
        ErrorLogger.unsetService(this.recorder);
    }

    @Test
    void sendsWhatAWalkRetriedAfterAConflictAnnouncedOnce() throws Exception
    {
        final TaskCompletion completion = announcing(messageEvent(DECISION, APPROVERS));

        engine(new EngineFixture.OneLostRace(EngineFixture.OneLostRace.UNRELATED_CHANGE), completion, this.told)
            .receiveEvent(task(), DECIDED);

        assertEquals(List.of(DECISION), this.told.events());
        // Proves the walk ran twice, so both attempts announced
        Mockito.verify(completion, Mockito.times(2))
            .apply(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void sendsNothingWhenEveryAttemptLosesItsRace() throws Exception
    {
        assertThrows(InvalidStateException.class, () -> engine(LOSING_EVERY_RACE,
            announcing(messageEvent(DECISION, APPROVERS)), this.told).receiveEvent(task(), DECIDED));

        assertTrue(this.told.sent().isEmpty());
    }

    @Test
    void sendsInTheOrderTheMessageEventsWereReached() throws Exception
    {
        engine(NO_RACE, announcing(messageEvent("first", APPROVERS), messageEvent("second", APPROVERS)),
            this.told).receiveEvent(task(), DECIDED);

        assertEquals(List.of("first", "second"), this.told.events());
    }

    @Test
    void recordsANotificationThatCannotBeSentAndStillSendsTheRest() throws Exception
    {
        final NotificationService flaky = (notification, roles) -> {
            if ("first".equals(notification.getEvent())) {
                throw new IllegalStateException("The principals service is down");
            }
            this.told.notify(notification, roles);
        };

        engine(NO_RACE, announcing(messageEvent("first", APPROVERS), messageEvent("second", APPROVERS)), flaky)
            .receiveEvent(task(), DECIDED);

        assertEquals(List.of("second"), this.told.events());
        Mockito.verify(this.recorder).logError(Mockito.any(IllegalStateException.class),
            Mockito.any(ErrorContext.class));
    }

    @Test
    void doesTheWorkAWalkRetriedAfterAConflictLeftOnce() throws Exception
    {
        final TaskCompletion completion = performing(activity("count"));

        engine(new EngineFixture.OneLostRace(EngineFixture.OneLostRace.UNRELATED_CHANGE), completion, this.told)
            .receiveEvent(task(), DECIDED);

        assertEquals(List.of("count"), this.done);
        // Proves the walk ran twice, so both attempts left the work
        Mockito.verify(completion, Mockito.times(2))
            .apply(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void doesNoWorkWhenEveryAttemptLosesItsRace()
    {
        assertThrows(InvalidStateException.class, () -> engine(LOSING_EVERY_RACE, performing(activity("count")),
            this.told).receiveEvent(task(), DECIDED));

        assertTrue(this.done.isEmpty());
    }

    @Test
    void doesTheWorkAfterSendingTheNotificationsInTheOrderTheStepsLeftIt() throws Exception
    {
        final List<String> order = new ArrayList<>();
        final NotificationService noting = (notification, roles) -> order.add(notification.getEvent());

        engine(NO_RACE, walking(announce(messageEvent(DECISION, APPROVERS)), perform(activity("first")),
            perform(activity("second"))), noting).receiveEvent(task(), DECIDED);
        order.addAll(this.done);

        assertEquals(List.of(DECISION, "first", "second"), order);
    }

    @Test
    void recordsWorkThatFailsAgainstTheStepThatLeftItAndStillDoesTheRest() throws Exception
    {
        engine(NO_RACE, performing(activity(BROKEN), activity("count")), this.told).receiveEvent(task(), DECIDED);

        assertEquals(List.of("count"), this.done);
        final ArgumentCaptor<ErrorContext> recorded = ArgumentCaptor.forClass(ErrorContext.class);
        Mockito.verify(this.recorder).logError(Mockito.any(IllegalStateException.class), recorded.capture());
        assertEquals(LEAVE_WORK, recorded.getValue().getComponent());
        assertEquals("/Workflows/process/v1/" + BROKEN, recorded.getValue().getSubject());
    }

    @Test
    void recordsAMessageEventThatTellsNobody() throws Exception
    {
        engine(NO_RACE, announcing(messageEvent(DECISION)), this.told).receiveEvent(task(), DECIDED);

        assertTrue(this.told.sent().isEmpty());
        Mockito.verify(this.recorder).logProblem(Mockito.eq("A message event names no recipients"),
            Mockito.any(ErrorContext.class));
    }

    @Test
    void passesOnlyTheDecisionTheDeliveredEventCarries() throws Exception
    {
        final WorkflowEvent rejected = new WorkflowEvent(TaskCompletion.COMPLETE_EVENT,
            Map.of("outcome", "rejected", "outcomeNote", "Too long", "comment", "Not for the template"));

        engine(NO_RACE, announcing(messageEvent(DECISION, APPROVERS)), this.told).receiveEvent(task(), rejected);

        final NotificationContext notification = this.told.sent().get(0).notification();
        assertEquals(Map.of("outcome", "rejected", "outcomeNote", "Too long"), notification.getVariables());
    }

    /**
     * One step of a stand-in walk, taken from the task's instance.
     *
     * @version $Id$
     * @since 0.1.0
     */
    @FunctionalInterface
    private interface Step
    {
        void take(InstanceRunner.StepPerformer performer, Resource instance) throws Exception;
    }

    /**
     * A task completion that takes the given steps from the task's instance, each time it is applied.
     *
     * @param steps the steps to take, in order
     * @return the stand-in
     * @throws Exception never, the stubbed method merely declares its exceptions
     */
    private static TaskCompletion walking(final Step... steps) throws Exception
    {
        final TaskCompletion completion = Mockito.mock(TaskCompletion.class);
        Mockito.doAnswer(invocation -> {
            final InstanceRunner.StepPerformer performer = invocation.getArgument(4);
            final Resource task = invocation.getArgument(1);
            for (final Step step : steps) {
                step.take(performer, task.getParent());
            }
            return null;
        }).when(completion).apply(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        return completion;
    }

    private static TaskCompletion announcing(final Event... events) throws Exception
    {
        return walking(Arrays.stream(events).map(AfterCommitTest::announce).toArray(Step[]::new));
    }

    private static TaskCompletion performing(final Activity... activities) throws Exception
    {
        return walking(Arrays.stream(activities).map(AfterCommitTest::perform).toArray(Step[]::new));
    }

    private static Step announce(final Event event)
    {
        return (performer, instance) -> performer.throwMessage(event, instance);
    }

    private static Step perform(final Activity activity)
    {
        return (performer, instance) -> performer.perform(activity, instance);
    }

    /**
     * An activity of a definition leaving work for after the commit, failing when it is the broken one.
     *
     * @param name the activity's name, which the work records as done
     * @return the activity
     */
    private static Activity activity(final String name)
    {
        final Activity activity = Mockito.mock(Activity.class);
        Mockito.when(activity.getHandler()).thenReturn(LEAVE_WORK);
        Mockito.when(activity.getName()).thenReturn(name);
        Mockito.when(activity.getPath()).thenReturn("/Workflows/process/v1/" + name);
        return activity;
    }

    /**
     * A message event of a definition that tells one role.
     *
     * @param name the message it announces
     * @param recipient the role it tells
     * @return the event
     */
    private static Event messageEvent(final String name, final String recipient)
    {
        final Event event = messageEvent(name);
        Mockito.when(event.get(PendingNotifications.RECIPIENTS_PROPERTY, String[].class))
            .thenReturn(new String[] {recipient});
        return event;
    }

    /**
     * A message event of a definition that names nobody to tell.
     *
     * @param name the message it announces
     * @return the event
     */
    private static Event messageEvent(final String name)
    {
        final Event event = Mockito.mock(Event.class);
        Mockito.when(event.getMessageName()).thenReturn(name);
        Mockito.when(event.getPath()).thenReturn("/Workflows/process/v1/" + name);
        return event;
    }

    /**
     * An engine walking through the given task completion, over a session running something before each commit.
     *
     * @param beforeCommit what runs before each commit, and may refuse it
     * @param completion the stand-in walk
     * @param notifications what sends the notifications
     * @return the engine
     * @throws Exception when the fixture cannot be built
     */
    private WorkflowEngineImpl engine(final EngineFixture.RepositoryAction beforeCommit,
        final TaskCompletion completion, final NotificationService notifications) throws Exception
    {
        this.context.resourceResolver().commit();
        final WorkflowEngineImpl engine = EngineFixture.engine(
            EngineFixture.serviceUsersRunning(this.context, beforeCommit), List.of(this.leavingWork), notifications);
        EngineFixture.inject(engine, "taskCompletion", completion);
        return engine;
    }

    private Resource task()
    {
        return EngineFixture.seenThrough(this.context.resourceResolver().getResource(TASK),
            EngineFixture.actingAs(this.context.resourceResolver(), EngineFixture.REQUESTER));
    }
}

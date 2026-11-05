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

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.jcr.InvalidItemStateException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.errortracking.api.ErrorLoggerService;
import io.uhndata.iap.notifications.api.NotificationContext;
import io.uhndata.iap.notifications.api.NotificationService;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.models.Event;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowFixture;
import io.uhndata.iap.workflows.models.WorkflowInstance;

import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for when the engine sends the notifications a delivery's message events announced, and what it does
 * when one cannot be sent. A stand-in task completion announces the messages a test names: the mock repository cannot
 * undo a lost attempt, so a real walk cannot be retried here.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class NotificationsAfterCommitTest
{
    private static final String TASK = "/Submissions/request/wf:instances/process/approve";

    private static final WorkflowEvent DECIDED = new WorkflowEvent(TaskCompletion.COMPLETE_EVENT, Map.of());

    private static final String DECISION = "decided";

    private static final String APPROVERS = "approvers";

    private final SlingContext context = new SlingContext();

    private final RecordedNotifications told = new RecordedNotifications();

    private final ErrorLoggerService recorder = Mockito.mock(ErrorLoggerService.class);

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
        final ArrayDeque<PersistenceException> failures = new ArrayDeque<>(List.of(conflict()));

        engine(failures::poll, completion, this.told).receiveEvent(task(), DECIDED);

        assertEquals(List.of(DECISION), this.told.events());
        // Proves the walk ran twice, so both attempts announced
        Mockito.verify(completion, Mockito.times(2))
            .apply(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void sendsNothingWhenEveryAttemptLosesItsRace() throws Exception
    {
        final PersistenceException conflict = conflict();

        assertThrows(InvalidStateException.class, () -> engine(() -> conflict,
            announcing(messageEvent(DECISION, APPROVERS)), this.told).receiveEvent(task(), DECIDED));

        assertTrue(this.told.sent().isEmpty());
    }

    @Test
    void sendsInTheOrderTheMessageEventsWereReached() throws Exception
    {
        engine(() -> null, announcing(messageEvent("first", APPROVERS), messageEvent("second", APPROVERS)),
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

        engine(() -> null, announcing(messageEvent("first", APPROVERS), messageEvent("second", APPROVERS)), flaky)
            .receiveEvent(task(), DECIDED);

        assertEquals(List.of("second"), this.told.events());
        Mockito.verify(this.recorder).logError(Mockito.any(IllegalStateException.class),
            Mockito.any(ErrorContext.class));
    }

    @Test
    void recordsAMessageEventThatTellsNobody() throws Exception
    {
        engine(() -> null, announcing(messageEvent(DECISION)), this.told).receiveEvent(task(), DECIDED);

        assertTrue(this.told.sent().isEmpty());
        Mockito.verify(this.recorder).logProblem(Mockito.eq("A message event names no recipients"),
            Mockito.any(ErrorContext.class));
    }

    @Test
    void passesOnlyTheDecisionTheDeliveredEventCarries() throws Exception
    {
        final WorkflowEvent rejected = new WorkflowEvent(TaskCompletion.COMPLETE_EVENT,
            Map.of("outcome", "rejected", "outcomeNote", "Too long", "comment", "Not for the template"));

        engine(() -> null, announcing(messageEvent(DECISION, APPROVERS)), this.told).receiveEvent(task(), rejected);

        final NotificationContext notification = this.told.sent().get(0).notification();
        assertEquals(Map.of("outcome", "rejected", "outcomeNote", "Too long"), notification.getVariables());
    }

    /**
     * A task completion that reaches the given message events from the task's instance, each time it is applied.
     *
     * @param events the events to reach, in order
     * @return the stand-in
     * @throws Exception never, the stubbed method merely declares its exceptions
     */
    private static TaskCompletion announcing(final Event... events) throws Exception
    {
        final TaskCompletion completion = Mockito.mock(TaskCompletion.class);
        Mockito.doAnswer(invocation -> {
            final InstanceRunner.StepPerformer performer = invocation.getArgument(4);
            final Resource task = invocation.getArgument(1);
            for (final Event event : events) {
                performer.throwMessage(event, task.getParent());
            }
            return null;
        }).when(completion).apply(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        return completion;
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

    private static PersistenceException conflict()
    {
        return new PersistenceException("save failed", new InvalidItemStateException("this node has been modified"));
    }

    /**
     * An engine walking through the given task completion, over a session whose commits fail as told.
     *
     * @param failures asked at each commit what it should throw, {@code null} for a commit that succeeds
     * @param completion the stand-in walk
     * @param notifications what sends the notifications
     * @return the engine
     * @throws Exception when the fixture cannot be built
     */
    private WorkflowEngineImpl engine(final Supplier<PersistenceException> failures, final TaskCompletion completion,
        final NotificationService notifications) throws Exception
    {
        this.context.resourceResolver().commit();
        final WorkflowEngineImpl engine =
            EngineFixture.engine(EngineFixture.serviceUsersFailing(this.context, failures), List.of(), notifications);
        EngineFixture.inject(engine, "taskCompletion", completion);
        return engine;
    }

    private Resource task()
    {
        return EngineFixture.seenThrough(this.context.resourceResolver().getResource(TASK),
            EngineFixture.actingAs(this.context.resourceResolver(), EngineFixture.REQUESTER));
    }
}

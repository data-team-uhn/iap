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

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.history.api.RecordedAction;
import io.uhndata.iap.principals.api.PrincipalService;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.NoApplicableWorkflowException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.internal.handlers.StartWorkflowHandler;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.EndEvent;
import io.uhndata.iap.workflows.models.ExclusiveGateway;
import io.uhndata.iap.workflows.models.IntermediateCatchingEvent;
import io.uhndata.iap.workflows.models.SequenceFlow;
import io.uhndata.iap.workflows.models.StartEvent;
import io.uhndata.iap.workflows.models.SystemWorkflowsHomepage;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowDefinition;
import io.uhndata.iap.workflows.models.WorkflowFixture;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static io.uhndata.iap.workflows.models.WorkflowFixture.ACTIVE;
import static io.uhndata.iap.workflows.models.WorkflowFixture.TAGS;
import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;
import static io.uhndata.iap.workflows.models.WorkflowFixture.tags;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the user workflow runtime, the part that persists, driven the way it actually runs: an event
 * reaches the engine, an instance is started inside the resource it drives, a token parks on a user task, and a
 * later event completes it and carries the instance to an end.
 *
 * <p>Driving it through the engine rather than calling {@link InstanceRunner} directly is deliberate: starting,
 * authorizing, parking and resuming only make sense together, and the seams between them are exactly where the
 * mistakes would be.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class UserWorkflowTest
{
    private static final String ELEMENT_ID = "elementId";

    private static final String HANDLER = "handler";

    private static final String TARGET_REF = "targetRef";

    private static final String HOST = "/Submissions/aLongWeekend";

    private static final String PROCESS = "/Workflows/timeOffRequest/v1";

    private static final String TASK = HOST + "/wf:instances/timeOffRequest/approveRequest";

    private static final String APPROVE = "approveRequest";

    private static final WorkflowEvent START = new WorkflowEvent("start", Map.of());

    private static final WorkflowEvent TIMEOUT = new WorkflowEvent("timeout", Map.of());

    private static final WorkflowEvent RAISE = new WorkflowEvent("raise", Map.of());

    /** Where the system workflow raising a submission and starting its process puts it. */
    private static final String RAISED = "/Submissions/raised";

    private static final WorkflowEvent APPROVED =
        new WorkflowEvent(TaskCompletion.COMPLETE_EVENT, Map.of(TaskCompletion.OUTCOME_PARAMETER, "approved"));

    // JCR-backed: the runtime writes a real REFERENCE to the workflow version, which needs a JCR node
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final EngineFixture.History history = new EngineFixture.History();

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
        WorkflowFixture.enableTagging(this.context, WorkflowFixture.LIFECYCLE_TAGS);
        this.context.create().resource("/Submissions", TYPE, "sub/SubmissionsHomepage");
        // The engine records the person it acted for as createdBy, which @creator reads
        this.context.create().resource(HOST, Map.of(TYPE, "sub/Submission", "tags", new String[] {"draft"},
            "createdBy", EngineFixture.REQUESTER));
        this.context.create().resource(HOST + "/wf:instances", TYPE, "wf/WorkflowInstances");
    }

    /**
     * Builds the demo's shape of process: start, a user task only the named group may complete, a gateway, and an
     * end event for each way out.
     *
     * @param performers who may complete the user task
     */
    private void createProcess(final String... performers)
    {
        this.context.create().resource("/Workflows/timeOffRequest", Map.of(
            TYPE, "wf/WorkflowDefinition", "title", "Time off request"));
        this.context.create().resource(PROCESS, Map.of(
            TYPE, WorkflowVersion.RESOURCE_TYPE, "version", "1.0", TAGS, tags(ACTIVE)));
        this.context.create().resource(PROCESS + "/requestSubmitted", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, ELEMENT_ID, "requestSubmitted"));
        this.context.create().resource(PROCESS + "/requestSubmitted/toApproval", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toApproval", TARGET_REF, APPROVE));
        this.context.create().resource(PROCESS + "/" + APPROVE, Map.of(
            TYPE, Activity.RESOURCE_TYPE, ELEMENT_ID, APPROVE, "label", "Approve the request",
            "performers", performers, "outcomeOptions", new String[] {"approved", "rejected"}));
        this.context.create().resource(PROCESS + "/" + APPROVE + "/toDecision", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toDecision", TARGET_REF, "decision"));
        this.context.create().resource(PROCESS + "/decision", Map.of(
            TYPE, ExclusiveGateway.RESOURCE_TYPE, ELEMENT_ID, "decision"));
        this.context.create().resource(PROCESS + "/decision/toApproved", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toApproved", TARGET_REF, "requestApproved"));
        outcomeIs(PROCESS + "/decision/toApproved", "approved");
        this.context.create().resource(PROCESS + "/decision/toRejected", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toRejected", TARGET_REF, "requestRejected",
            "isDefault", true));
        this.context.create().resource(PROCESS + "/requestApproved", Map.of(
            TYPE, EndEvent.RESOURCE_TYPE, ELEMENT_ID, "requestApproved", "hostTag", "approved"));
        this.context.create().resource(PROCESS + "/requestRejected", Map.of(
            TYPE, EndEvent.RESOURCE_TYPE, ELEMENT_ID, "requestRejected", "hostTag", "rejected"));
    }

    /**
     * Adds properties to the process's user task, for the cases about what a task carries beyond its label.
     *
     * @param properties what to write on the activity
     */
    private void addToTheUserTask(final Map<String, Object> properties)
    {
        Objects.requireNonNull(this.context.resourceResolver().getResource(PROCESS + "/" + APPROVE)
            .adaptTo(ModifiableValueMap.class), "The mock repository lets any node be modified").putAll(properties);
    }

    /**
     * Puts a guard on an arc that holds when the instance's outcome is the given one.
     *
     * @param flowPath the arc to put the condition on
     * @param outcome the outcome the arc is taken for
     */
    private void outcomeIs(final String flowPath, final String outcome)
    {
        this.context.create().resource(flowPath + "/cond:condition", Map.of(
            TYPE, "cond/SingleCondition", "comparator", "equals"));
        this.context.create().resource(flowPath + "/cond:condition/operandA", Map.of(
            TYPE, "cond/ConditionOperand", "source", "variable", "value", "outcome"));
        this.context.create().resource(flowPath + "/cond:condition/operandB", Map.of(
            TYPE, "cond/ConditionOperand", "value", outcome));
    }

    @Test
    void startsTheClockOnATaskADeadlineWatches() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        watchApprovalWith("P5D");
        started();

        final Map<String, Object> task = read(TASK);
        assertEquals("approvalOverdue", task.get("dueEventId"));
        final Calendar started = (Calendar) task.get("startTime");
        final Calendar due = (Calendar) task.get("dueDate");
        assertNotNull(due);
        // Five calendar days in the server's own zone, which is 121 or 119 hours across a change of clocks
        assertEquals(ZonedDateTime.ofInstant(started.toInstant(), ZoneId.systemDefault()).plusDays(5).toInstant(),
            due.toInstant());
    }

    @Test
    void leavesATaskNothingIsCountingDownToWithoutADeadline() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        started();

        assertNull(read(TASK).get("dueDate"));
        assertNull(read(TASK).get("dueEventId"));
    }

    @Test
    void ignoresABoundaryEventThatIsNotATimer() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        this.context.create().resource(PROCESS + "/" + APPROVE + "/cancelled", Map.of(
            TYPE, IntermediateCatchingEvent.RESOURCE_TYPE, ELEMENT_ID, "cancelled", "messageName", "withdraw"));
        started();

        assertNull(read(TASK).get("dueDate"));
    }

    @Test
    void takesTheEarliestOfSeveralDeadlines() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        watchApprovalWith("P5D");
        watchApprovalWith("PT36H", "escalate", "escalated");
        started();

        assertEquals("escalate", read(TASK).get("dueEventId"));
    }

    @Test
    void carriesTheInstanceDownTheTimersArcWhenTheDeadlinePasses() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        watchApprovalWith("P5D");
        final WorkflowEngine engine = started();
        deadlinePassed();

        engine.receiveEvent(as(TASK, TaskCompletion.TIMER_USER), TIMEOUT);

        assertEquals("cancelled", read(TASK).get("status"));
        assertNull(read(TASK).get("assignee"));
        assertNull(read(TASK).get("outcome"));
        assertEquals(List.of("draft", "expired"), List.of((String[]) read(HOST).get("tags")));
        assertEquals("completed", read(HOST + "/wf:instances/timeOffRequest").get("status"));
    }

    @Test
    void remindsWithoutEndingTheWorkWhenTheTimerDoesNotInterrupt() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        watchApprovalWith("PT36H", "approvalSlow", null, false);
        // The reminder leads to a task chasing the approver instead of an end event
        this.context.resourceResolver().delete(
            this.context.resourceResolver().getResource(PROCESS + "/" + APPROVE + "/approvalSlow/toEndapprovalSlow"));
        this.context.create().resource(PROCESS + "/" + APPROVE + "/approvalSlow/toChase", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toChase", TARGET_REF, "chaseApprover"));
        this.context.create().resource(PROCESS + "/chaseApprover", Map.of(
            TYPE, Activity.RESOURCE_TYPE, ELEMENT_ID, "chaseApprover", "label", "Chase the approver",
            "performers", new String[] {EngineFixture.REQUESTERS}));
        this.context.create().resource(PROCESS + "/chaseApprover/toDecision", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "chaseToDecision", TARGET_REF, "decision"));
        final WorkflowEngine engine = started();
        deadlinePassed();

        engine.receiveEvent(as(TASK, TaskCompletion.TIMER_USER), TIMEOUT);

        assertEquals("created", read(TASK).get("status"));
        assertEquals("created", read(HOST + "/wf:instances/timeOffRequest/chaseApprover").get("status"));
        assertEquals("active", read(HOST + "/wf:instances/timeOffRequest").get("status"));
        assertArrayEquals(new String[] {"approvalSlow"}, (String[]) read(TASK).get("firedEvents"));
        assertNull(read(TASK).get("dueDate"));
        assertNull(read(TASK).get("dueEventId"));
    }

    @Test
    void armsTheNextDeadlineOnceAReminderHasFired() throws Exception
    {
        // A reminder after 36 hours, then a deadline after five days. The second counts from the task's start, not
        // from the reminder
        createProcess(EngineFixture.REQUESTERS);
        watchApprovalWith("PT36H", "approvalSlow", null, false);
        watchApprovalWith("P5D", "approvalOverdue", "expired");
        final WorkflowEngine engine = started();
        final Calendar started = (Calendar) read(TASK).get("startTime");
        deadlinePassed();

        engine.receiveEvent(as(TASK, TaskCompletion.TIMER_USER), TIMEOUT);

        assertEquals("approvalOverdue", read(TASK).get("dueEventId"));
        final Calendar due = (Calendar) read(TASK).get("dueDate");
        // Five calendar days in the server's own zone, which is 121 or 119 hours across a change of clocks
        assertEquals(ZonedDateTime.ofInstant(started.toInstant(), ZoneId.systemDefault()).plusDays(5).toInstant(),
            due.toInstant());

        // The later deadline still cancels the task when it passes
        deadlinePassed();
        engine.receiveEvent(as(TASK, TaskCompletion.TIMER_USER), TIMEOUT);

        assertEquals("cancelled", read(TASK).get("status"));
        assertEquals(List.of("draft", "expired"), List.of((String[]) read(HOST).get("tags")));
    }

    @Test
    void deliversAReminderOnlyOnce() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        watchApprovalWith("PT36H", "approvalSlow", null, false);
        final WorkflowEngine engine = started();
        deadlinePassed();
        engine.receiveEvent(as(TASK, TaskCompletion.TIMER_USER), TIMEOUT);

        assertThrows(NoApplicableWorkflowException.class,
            () -> engine.receiveEvent(as(TASK, TaskCompletion.TIMER_USER), TIMEOUT));
        assertEquals("created", read(TASK).get("status"));
    }

    @Test
    void refusesADeadlineNothingIsCountingDownTo() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        final WorkflowEngine engine = started();

        assertThrows(NoApplicableWorkflowException.class,
            () -> engine.receiveEvent(as(TASK, TaskCompletion.TIMER_USER), TIMEOUT));
        assertEquals("created", read(TASK).get("status"));
    }

    @Test
    void refusesATimeoutFromSomeoneWhoMayOnlyDecideTheTask() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        watchApprovalWith("P5D");
        final WorkflowEngine engine = started();
        deadlinePassed();

        assertThrows(NotAuthorizedException.class,
            () -> engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), TIMEOUT));
        assertEquals("created", read(TASK).get("status"));
    }

    @Test
    void letsATimerNameWhoMayFireIt() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        watchApprovalWith("P5D");
        this.context.resourceResolver().getResource(PROCESS + "/" + APPROVE + "/approvalOverdue")
            .adaptTo(ModifiableValueMap.class).put("performers", new String[] {EngineFixture.REQUESTER});
        final WorkflowEngine engine = started();
        deadlinePassed();

        assertThrows(NotAuthorizedException.class,
            () -> engine.receiveEvent(as(TASK, TaskCompletion.TIMER_USER), TIMEOUT));
        engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), TIMEOUT);

        assertEquals("cancelled", read(TASK).get("status"));
    }

    @Test
    void ignoresADeadlineThatPassesAfterTheTaskWasDecided() throws Exception
    {
        // "Refuse the request after five days", but it was approved in time
        createProcess(EngineFixture.REQUESTERS);
        watchApprovalWith("P5D");
        final WorkflowEngine engine = started();
        engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), APPROVED);
        deadlinePassed();

        assertThrows(NoApplicableWorkflowException.class,
            () -> engine.receiveEvent(as(TASK, TaskCompletion.TIMER_USER), TIMEOUT));
        assertEquals("completed", read(TASK).get("status"));
        assertEquals(List.of("approved"), List.of((String[]) read(HOST).get("tags")));
    }

    @Test
    void refusesATimeoutBeforeTheDeadline() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        watchApprovalWith("P5D");
        final WorkflowEngine engine = started();

        assertThrows(InvalidStateException.class,
            () -> engine.receiveEvent(as(TASK, TaskCompletion.TIMER_USER), TIMEOUT));
        assertEquals("created", read(TASK).get("status"));
        assertEquals(List.of("draft"), List.of((String[]) read(HOST).get("tags")));
    }

    @Test
    void refusesATimeoutOnATaskThatDoesNotSayWhenItRunsOut() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        watchApprovalWith("P5D");
        final WorkflowEngine engine = started();
        this.context.resourceResolver().getResource(TASK).adaptTo(ModifiableValueMap.class).remove("dueDate");

        assertThrows(InvalidStateException.class,
            () -> engine.receiveEvent(as(TASK, TaskCompletion.TIMER_USER), TIMEOUT));
        assertEquals("created", read(TASK).get("status"));
    }

    /**
     * Moves the approval task's deadline an hour into the past.
     */
    private void deadlinePassed()
    {
        final Calendar past = Calendar.getInstance();
        past.add(Calendar.HOUR, -1);
        this.context.resourceResolver().getResource(TASK).adaptTo(ModifiableValueMap.class).put("dueDate", past);
    }

    /**
     * Watches the approval task with a timer whose end event tags the request expired.
     *
     * @param duration how long the timer waits, as an ISO-8601 duration
     */
    private void watchApprovalWith(final String duration)
    {
        watchApprovalWith(duration, "approvalOverdue", "expired");
    }

    /**
     * Watches the approval task with an interrupting timer.
     *
     * @param duration how long the timer waits, as an ISO-8601 duration
     * @param elementId the timer's element identifier
     * @param endTag the tag the end event it leads to places on the host
     */
    private void watchApprovalWith(final String duration, final String elementId, final String endTag)
    {
        watchApprovalWith(duration, elementId, endTag, true);
    }

    /**
     * Attaches a boundary timer to the approval task, with an arc to an end event.
     *
     * @param duration how long the timer waits, as an ISO-8601 duration
     * @param elementId the timer's element identifier
     * @param endTag the tag the end event it leads to places on the host, or {@code null} for none
     * @param interrupting whether firing gives up on the task, or leaves it running alongside
     */
    private void watchApprovalWith(final String duration, final String elementId, final String endTag,
        final boolean interrupting)
    {
        final String timer = PROCESS + "/" + APPROVE + "/" + elementId;
        this.context.create().resource(timer, Map.of(
            TYPE, IntermediateCatchingEvent.RESOURCE_TYPE, ELEMENT_ID, elementId,
            "timerDuration", duration, "interrupting", interrupting));
        this.context.create().resource(timer + "/toEnd" + elementId, Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toEnd" + elementId, TARGET_REF, "end" + elementId));
        this.context.create().resource(PROCESS + "/end" + elementId, endTag == null
            ? Map.of(TYPE, EndEvent.RESOURCE_TYPE, ELEMENT_ID, "end" + elementId)
            : Map.of(TYPE, EndEvent.RESOURCE_TYPE, ELEMENT_ID, "end" + elementId, "hostTag", endTag));
    }

    /**
     * The system workflow that puts a submission under its process: the platform's own bootstrap in miniature,
     * since an instance is never started by an event of its own but by a {@code startWorkflow} service task.
     */
    private void createBootstrap()
    {
        createStartingWorkflow("putUnderWorkflow", "sub/Submission", START.getName());
    }

    /**
     * Adds a system workflow that catches an event on resources of one type, runs the given service tasks, and then
     * starts the workflow its target references.
     *
     * @param name the definition's node name
     * @param targetType the resource type the workflow is aimed at
     * @param message the event it catches
     * @param handlers the service tasks to run before starting the workflow, by handler name
     */
    private void createStartingWorkflow(final String name, final String targetType, final String message,
        final String... handlers)
    {
        final String version = SystemWorkflowsHomepage.HOMEPAGE_PATH + "/" + name + "/v1";
        this.context.create().resource(SystemWorkflowsHomepage.HOMEPAGE_PATH, TYPE,
            SystemWorkflowsHomepage.RESOURCE_TYPE);
        this.context.create().resource(SystemWorkflowsHomepage.HOMEPAGE_PATH + "/" + name, TYPE,
            WorkflowDefinition.RESOURCE_TYPE);
        this.context.create().resource(version, Map.of(
            TYPE, WorkflowVersion.RESOURCE_TYPE, "version", "1.0", TAGS, tags(ACTIVE),
            "targetResourceType", targetType));
        this.context.create().resource(version + "/caught", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, ELEMENT_ID, "caught", "messageName", message,
            "performers", new String[] {EngineFixture.REQUESTERS}));
        String previous = version + "/caught";
        for (final String handler : handlers) {
            previous = addActivity(previous, version + "/" + handler, Map.of(HANDLER, handler));
        }
        previous = addActivity(previous, version + "/start",
            Map.of(HANDLER, StartWorkflowHandler.HANDLER_NAME, "workflowFrom", "workflow"));
        addEnd(previous, version + "/done");
    }

    /**
     * Adds an activity as the next node of a straight walk, with the arc leading to it from the one before.
     *
     * @param previous the path of the node the arc leaves
     * @param path the path of the activity to add
     * @param configuration the activity's own properties
     * @return the path of the activity added
     */
    private String addActivity(final String previous, final String path, final Map<String, Object> configuration)
    {
        final Map<String, Object> properties = new HashMap<>(configuration);
        properties.put(TYPE, Activity.RESOURCE_TYPE);
        return addNode(previous, path, properties);
    }

    /**
     * Ends a straight walk with an end event, and the arc leading to it from the node before.
     *
     * @param previous the path of the node the arc leaves
     * @param path the path of the end event to add
     */
    private void addEnd(final String previous, final String path)
    {
        addNode(previous, path, new HashMap<>(Map.of(TYPE, EndEvent.RESOURCE_TYPE)));
    }

    /**
     * Adds a node and the arc leading to it from the one before.
     *
     * @param previous the path of the node the arc leaves
     * @param path the path of the node to add
     * @param properties the node's properties, its type included
     * @return the path of the node added
     */
    private String addNode(final String previous, final String path, final Map<String, Object> properties)
    {
        final String elementId = path.substring(path.lastIndexOf('/') + 1);
        this.context.create().resource(previous + "/to-" + elementId, Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "to-" + elementId, TARGET_REF, elementId));
        properties.put(ELEMENT_ID, elementId);
        this.context.create().resource(path, properties);
        return path;
    }

    /**
     * Points the host at the process, the way a submission's schema version does, and runs the bootstrap that
     * puts it under it.
     *
     * @return the engine, ready for the next event
     * @throws Exception when the fixture cannot be built
     */
    private WorkflowEngine started() throws Exception
    {
        createBootstrap();
        reference(HOST, "workflow", PROCESS);
        final WorkflowEngine engine = engine();
        engine.receiveEvent(host(EngineFixture.REQUESTER), START);
        return engine;
    }

    /**
     * Builds an engine wired as the DS runtime would wire it, with the {@code startWorkflow} handler the bootstrap
     * uses, and a service session that can answer who the repository's users are.
     *
     * @param handlers the service task handlers to register besides {@code startWorkflow}
     * @return a ready engine
     * @throws Exception when reflection fails, which would be a bug in this test
     */
    private WorkflowEngine engine(final ServiceTaskHandler... handlers) throws Exception
    {
        this.context.resourceResolver().commit();
        final List<ServiceTaskHandler> registered = new ArrayList<>(List.of(handlers));
        registered.add(new StartWorkflowHandler());
        final WorkflowEngineImpl engine =
            EngineFixture.engine(EngineFixture.serviceUsers(this.context, null), registered);
        EngineFixture.inject(engine, "recorder", this.history);
        return engine;
    }

    /**
     * A resource, as seen by the given user's session.
     *
     * @param path what to resolve
     * @param actor who is asking
     * @return the resource, reporting that user as its session's owner
     */
    private Resource as(final String path, final String actor)
    {
        final Resource resource = this.context.resourceResolver().getResource(path);
        final ResourceResolver resolver = EngineFixture.actingAs(this.context.resourceResolver(), actor);
        return EngineFixture.seenThrough(resource, resolver);
    }

    /**
     * The host resource, as seen by the given user. Its own start event is what the engine matches, so the host
     * doubles as the thing a {@code start} event is aimed at.
     *
     * @param actor who is asking
     * @return the host resource
     */
    private Resource host(final String actor)
    {
        return as(HOST, actor);
    }

    /**
     * Writes a real REFERENCE, which is the only kind the runtime will follow.
     *
     * @param from the resource holding the reference
     * @param property the property name
     * @param to the referenced resource's path
     * @throws Exception when the repository refuses
     */
    private void reference(final String from, final String property, final String to) throws Exception
    {
        final Node source = this.context.resourceResolver().getResource(from).adaptTo(Node.class);
        final Node target = this.context.resourceResolver().getResource(to).adaptTo(Node.class);
        source.setProperty(property, target);
        this.context.resourceResolver().commit();
    }

    /**
     * The properties a node has after the engine committed, read through a refreshed session.
     *
     * @param path the resource to read
     * @return its properties
     */
    private Map<String, Object> read(final String path)
    {
        this.context.resourceResolver().refresh();
        final Resource resource = this.context.resourceResolver().getResource(path);
        return resource == null ? Map.of() : resource.getValueMap();
    }

    /**
     * The repository's identifier for a node, which a workflow's own nodes have without being referenceable.
     *
     * @param path the node's path
     * @return its identifier
     * @throws RepositoryException when the node cannot be read
     */
    private String identifierOf(final String path) throws RepositoryException
    {
        return Objects.requireNonNull(this.context.resourceResolver().getResource(path).adaptTo(Node.class),
            "The test repository is a JCR one").getIdentifier();
    }

    @Test
    void startsAnInstanceInsideTheResourceItDrives() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);

        started();

        final Map<String, Object> instance = read(HOST + "/wf:instances/timeOffRequest");
        assertEquals("active", instance.get("status"));
        assertNotNull(instance.get("startTime"));
        // Parked on the user task, with a token saying exactly where
        assertEquals(APPROVE, read(HOST + "/wf:instances/timeOffRequest/token").get("currentNodeId"));
        final Map<String, Object> task = read(TASK);
        assertEquals("created", task.get("status"));
        assertEquals("Approve the request", task.get("label"));
        assertEquals(APPROVE, task.get("taskDefinitionId"));
    }

    @Test
    void raisesTasksCarryingTheDecisionsTheirDefinitionOffers() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        addToTheUserTask(Map.of("outcomeOptions", new String[] {"approved", "rejected"}));

        started();

        assertArrayEquals(new String[] {"approved", "rejected"},
            (String[]) read(TASK).get(TaskInstance.OUTCOME_OPTIONS_PROPERTY));
    }

    @Test
    void raisesTasksOfferingNothingWhenThereIsNothingToDecide() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        addToTheUserTask(Map.of("outcomeOptions", new String[0]));

        started();

        assertEquals(0, ((String[]) read(TASK).get(TaskInstance.OUTCOME_OPTIONS_PROPERTY)).length);
    }

    @Test
    void raisesTasksNamingWhoMayCompleteThem() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);

        started();

        assertArrayEquals(new String[] {EngineFixture.REQUESTERS},
            (String[]) read(TASK).get(TaskInstance.PERFORMERS_PROPERTY));
    }

    @Test
    void answersCreatorAgainstTheHostWhenItRecordsWhoMayCompleteATask() throws Exception
    {
        createProcess(PrincipalService.CREATOR_SPECIAL_NAME);

        started();

        assertArrayEquals(new String[] {EngineFixture.REQUESTER},
            (String[]) read(TASK).get(TaskInstance.PERFORMERS_PROPERTY));
    }

    @Test
    void answersCreatorAgainstAHostRaisedEarlierInTheSameWalk() throws Exception
    {
        createProcess(PrincipalService.CREATOR_SPECIAL_NAME);
        // Aimed at the submissions homepage: one activity raises a submission, and the next starts its process
        createStartingWorkflow("raise", "sub/SubmissionsHomepage", RAISE.getName(), RaisingHandler.NAME);
        final WorkflowEngine engine = engine(new RaisingHandler());

        engine.receiveEvent(as("/Submissions", EngineFixture.REQUESTER), RAISE);

        assertArrayEquals(new String[] {EngineFixture.REQUESTER},
            (String[]) read(RAISED + "/wf:instances/timeOffRequest/" + APPROVE).get(TaskInstance.PERFORMERS_PROPERTY));
    }

    @Test
    void admitsWhoeverRaisedTheHostToATaskThatComesBackToThem() throws Exception
    {
        createProcess(PrincipalService.CREATOR_SPECIAL_NAME);
        final WorkflowEngine engine = started();

        engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), APPROVED);

        assertEquals(TaskInstance.COMPLETED_STATUS, read(TASK).get(TaskInstance.STATUS_PROPERTY));
    }

    @Test
    void admitsThemEvenWhenTheyTypedTheirNameDifferentlyAtLogin() throws Exception
    {
        createProcess(PrincipalService.CREATOR_SPECIAL_NAME);
        final WorkflowEngine engine = started();

        engine.receiveEvent(EngineFixture.typedAtLogin(as(TASK, EngineFixture.REQUESTER),
            EngineFixture.REQUESTER.toUpperCase(Locale.ROOT)), APPROVED);

        assertEquals(TaskInstance.COMPLETED_STATUS, read(TASK).get(TaskInstance.STATUS_PROPERTY));
    }

    @Test
    void refusesSomebodyElseAtATaskThatComesBackToWhoeverRaisedTheHost() throws Exception
    {
        createProcess(PrincipalService.CREATOR_SPECIAL_NAME);
        final WorkflowEngine engine = started();

        assertThrows(NotAuthorizedException.class, () -> engine.receiveEvent(as(TASK, "somebody-else"), APPROVED));
        assertEquals(TaskInstance.OPEN_STATUS, read(TASK).get(TaskInstance.STATUS_PROPERTY));
    }

    @Test
    void offersCompletionToWhoeverRaisedTheHostOfATaskThatComesBackToThem() throws Exception
    {
        createProcess(PrincipalService.CREATOR_SPECIAL_NAME);
        final WorkflowEngine engine = started();

        assertEquals(Set.of(TaskCompletion.COMPLETE_EVENT),
            engine.getAvailableEvents(as(TASK, EngineFixture.REQUESTER)));
        assertEquals(Set.of(), engine.getAvailableEvents(as(TASK, "somebody-else")));
    }

    @Test
    void grantsReadToEveryoneTheProcessInvolves() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);

        started();

        // The person it is being run for, and the performers of its user tasks, derived from the definition
        // rather than declared twice
        assertTrue(EngineFixture.GRANTED.contains(EngineFixture.REQUESTER));
        assertTrue(EngineFixture.GRANTED.contains(EngineFixture.REQUESTERS));
    }

    @Test
    void carriesTheInstanceToTheEndTheOutcomeChooses() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        final WorkflowEngine engine = started();

        final WorkflowResult result = engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), APPROVED);

        assertNull(result.getVariable(WorkflowResult.CREATED_PATH_VARIABLE));
        assertEquals("completed", read(TASK).get("status"));
        assertEquals("approved", read(TASK).get("outcome"));
        assertEquals(EngineFixture.REQUESTER, read(TASK).get("assignee"));
        final Map<String, Object> instance = read(HOST + "/wf:instances/timeOffRequest");
        assertEquals("completed", instance.get("status"));
        assertNotNull(instance.get("endTime"));
        // The token is spent, and the end event said what finishing that way means to the host
        assertTrue(read(HOST + "/wf:instances/timeOffRequest/token").isEmpty());
        // A lifecycle is a tag: the models read `tags`, so an end event replaces the host's lifecycle tag there
        assertEquals(List.of("approved"), List.of((String[]) read(HOST).get("tags")));
    }

    @Test
    void recordsWhomADelegateCompletedTheTaskFor() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        final WorkflowEngine engine = started();

        engine.receiveEvent(as(TASK, EngineFixture.ADMIN), APPROVED, EngineFixture.REQUESTER);

        assertEquals("completed", read(TASK).get("status"));
        assertEquals(EngineFixture.REQUESTER, read(TASK).get("assignee"));
        final RecordedAction decision = this.history.actions().get(this.history.actions().size() - 1);
        assertEquals(EngineFixture.ADMIN, decision.getActor());
        assertEquals(EngineFixture.REQUESTER, decision.getOnBehalfOf());
    }

    @Test
    void recordsTheDecisionInHistory() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        final WorkflowEngine engine = started();
        final int before = this.history.actions().size();

        engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), APPROVED);

        assertEquals(before + 1, this.history.actions().size());
        final RecordedAction decision = this.history.actions().get(before);
        assertEquals(EngineFixture.REQUESTER, decision.getActor());
        assertEquals(TaskCompletion.COMPLETE_EVENT, decision.getEvent());
        assertEquals(identifierOf(TASK), decision.getTaskInstance());
        assertEquals("approved", decision.getOutcome());
        assertEquals(identifierOf(HOST + "/wf:instances/timeOffRequest"), decision.getWorkflowInstance());
        assertEquals(read(TASK).get("taskDefinitionId"), decision.getActivityId());
        assertTrue(decision.getEffects().stream()
            .anyMatch(effect -> "host".equals(effect.role()) && HOST.equals(effect.subjectPath())));
    }

    @Test
    void takesTheDefaultArcWhenNoConditionMatches() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        final WorkflowEngine engine = started();

        engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), new WorkflowEvent(
            TaskCompletion.COMPLETE_EVENT, Map.of(TaskCompletion.OUTCOME_PARAMETER, "rejected")));

        assertEquals(List.of("rejected"), List.of((String[]) read(HOST).get("tags")));
    }

    @Test
    void refusesADecisionFromSomeoneTheTaskDoesNotName() throws Exception
    {
        createProcess("someone-else");
        final WorkflowEngine engine = started();

        assertThrows(NotAuthorizedException.class,
            () -> engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), APPROVED));
        // Refused before anything moved
        assertEquals("created", read(TASK).get("status"));
        assertEquals(List.of("draft"), List.of((String[]) read(HOST).get("tags")));
    }

    @Test
    void hasNothingLeftToDecideOnceTheTaskIsDone() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        final WorkflowEngine engine = started();
        engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), APPROVED);

        assertThrows(NoApplicableWorkflowException.class,
            () -> engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), APPROVED));
    }

    @Test
    void offersCompletionToWhoeverTheOpenTaskNames() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        final WorkflowEngine engine = started();

        assertEquals(Set.of(TaskCompletion.COMPLETE_EVENT),
            engine.getAvailableEvents(as(TASK, EngineFixture.REQUESTER)));
    }

    @Test
    void findsNoSystemWorkflowForATask() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        final WorkflowEngine engine = started();

        assertNull(engine.findApplicableWorkflow(as(TASK, EngineFixture.REQUESTER), "complete"));
    }

    @Test
    void offersNothingToSomeoneTheTaskDoesNotName() throws Exception
    {
        createProcess("someone-else");
        final WorkflowEngine engine = started();

        assertEquals(Set.of(), engine.getAvailableEvents(as(TASK, EngineFixture.REQUESTER)));
    }

    @Test
    void offersNothingOnceTheTaskIsDone() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        final WorkflowEngine engine = started();
        engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), APPROVED);

        assertEquals(Set.of(), engine.getAvailableEvents(as(TASK, EngineFixture.REQUESTER)));
    }

    @Test
    void offersNothingOnATaskWhoseDefinitionHasGoneAway() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        final WorkflowEngine engine = started();
        this.context.resourceResolver().delete(
            this.context.resourceResolver().getResource(PROCESS + "/" + APPROVE));
        this.context.resourceResolver().commit();

        assertEquals(Set.of(), engine.getAvailableEvents(as(TASK, EngineFixture.REQUESTER)));
    }

    @Test
    void refusesEventsATaskCannotAnswer() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        final WorkflowEngine engine = started();

        final NoApplicableWorkflowException refusal = assertThrows(NoApplicableWorkflowException.class,
            () -> engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), new WorkflowEvent("create", Map.of())));
        assertTrue(refusal.getMessage().contains("being completed"));
    }

    @Test
    void completesWithoutAnOutcomeWhenTheProcessDoesNotBranchOnOne() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        addToTheUserTask(Map.of("outcomeOptions", new String[0]));
        final WorkflowEngine engine = started();

        // No outcome recorded, so nothing matches and the default arc carries it
        engine.receiveEvent(as(TASK, EngineFixture.REQUESTER),
            new WorkflowEvent(TaskCompletion.COMPLETE_EVENT, Map.of()));

        assertEquals(List.of("rejected"), List.of((String[]) read(HOST).get("tags")));
        assertNull(read(TASK).get("outcome"));
    }

    @Test
    void refusesToCloseADecisionWithoutAnOutcome() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        final WorkflowEngine engine = started();

        // A blank outcome reads as none, which would let the gateway route on an earlier task's decision
        assertThrows(InvalidPayloadException.class, () -> engine.receiveEvent(as(TASK, EngineFixture.REQUESTER),
            new WorkflowEvent(TaskCompletion.COMPLETE_EVENT, Map.of(TaskCompletion.OUTCOME_PARAMETER, " "))));
        assertEquals("created", read(TASK).get("status"));
        assertEquals(List.of("draft"), List.of((String[]) read(HOST).get("tags")));
    }

    @Test
    void refusesAnOutcomeTheTaskDoesNotOffer() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        final WorkflowEngine engine = started();

        assertThrows(InvalidPayloadException.class, () -> engine.receiveEvent(as(TASK, EngineFixture.REQUESTER),
            new WorkflowEvent(TaskCompletion.COMPLETE_EVENT, Map.of(TaskCompletion.OUTCOME_PARAMETER, "reject"))));
        assertEquals(TaskInstance.OPEN_STATUS, read(TASK).get(TaskInstance.STATUS_PROPERTY));
    }

    @Test
    void refusesAnOutcomeOnATaskWithNothingToDecide() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        addToTheUserTask(Map.of("outcomeOptions", new String[0]));
        final WorkflowEngine engine = started();

        assertThrows(InvalidPayloadException.class,
            () -> engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), APPROVED));
        assertEquals(TaskInstance.OPEN_STATUS, read(TASK).get(TaskInstance.STATUS_PROPERTY));
    }

    @Test
    void leavesNothingBehindWhenTheDecisionCannotBeCommitted() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        started();
        final WorkflowEngine engine = EngineFixture.engine(EngineFixture.serviceUsers(this.context,
            new PersistenceException("the disk is on fire")), List.of(new StartWorkflowHandler()));

        assertThrows(io.uhndata.iap.workflows.api.WorkflowFailedException.class,
            () -> engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), APPROVED));
    }

    @Test
    void keepsRunningUntilItSettlesOrTheDefinitionLoops() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        // Both arcs of the gateway lead back to it, so the instance can never settle
        this.context.resourceResolver().getResource(PROCESS + "/decision/toApproved")
            .adaptTo(ModifiableValueMap.class).put(TARGET_REF, "decision");
        this.context.resourceResolver().getResource(PROCESS + "/decision/toRejected")
            .adaptTo(ModifiableValueMap.class).put(TARGET_REF, "decision");
        final WorkflowEngine engine = started();

        final WorkflowDefinitionException rejection = assertThrows(WorkflowDefinitionException.class,
            () -> engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), APPROVED));
        assertTrue(rejection.getMessage().contains("did not settle"));
    }

    @Test
    void refusesToResumeAnInstanceThatIsNoLongerWaitingThere() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        final WorkflowEngine engine = started();
        // The token removed behind the engine's back, as a half-finished repair might leave things
        this.context.resourceResolver().delete(
            this.context.resourceResolver().getResource(HOST + "/wf:instances/timeOffRequest/token"));
        this.context.resourceResolver().commit();

        final WorkflowDefinitionException rejection = assertThrows(WorkflowDefinitionException.class,
            () -> engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), APPROVED));
        assertTrue(rejection.getMessage().contains("no longer waiting"));
    }

    @Test
    void replacesAnOutcomeAlreadyRecorded() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        started();
        // An earlier decision in the same instance, as a second user task would have left
        this.context.create().resource(HOST + "/wf:instances/timeOffRequest/outcome", Map.of(
            TYPE, "wf/Variable", "dataType", "string", "stringValue", "rejected"));
        final WorkflowEngine engine = engine();

        engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), APPROVED);

        assertEquals("approved",
            read(HOST + "/wf:instances/timeOffRequest/outcome").get("stringValue"));
        assertEquals(List.of("approved"), List.of((String[]) read(HOST).get("tags")));
    }

    @Test
    void refusesATaskWhoseDefinitionHasGoneAway() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        final WorkflowEngine engine = started();
        // The activity edited out of the workflow while a task for it was still open. Who may complete it is
        // written in the definition, so without one there is no way to answer that.
        this.context.resourceResolver().delete(
            this.context.resourceResolver().getResource(PROCESS + "/" + APPROVE));
        this.context.resourceResolver().commit();

        final WorkflowDefinitionException rejection = assertThrows(WorkflowDefinitionException.class,
            () -> engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), APPROVED));
        assertTrue(rejection.getMessage().contains("no longer has a definition"));
    }

    @Test
    void refusesToStartAProcessWithoutASingleStartEvent() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        this.context.create().resource(PROCESS + "/alsoStarts", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, ELEMENT_ID, "alsoStarts"));

        final WorkflowDefinitionException rejection =
            assertThrows(WorkflowDefinitionException.class, this::started);
        assertTrue(rejection.getMessage().contains("exactly one start event"));
    }

    @Test
    void refusesToStartARetiredProcess() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        this.context.resourceResolver().getResource(PROCESS)
            .adaptTo(ModifiableValueMap.class).put(TAGS, tags("retired"));

        final WorkflowDefinitionException rejection =
            assertThrows(WorkflowDefinitionException.class, this::started);
        assertTrue(rejection.getMessage().contains("not active"));
    }

    @Test
    void refusesToCarryAnInstanceThroughANodeItCannotPass() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        // A catching event mid-process: legal BPMN, but nothing can yet deliver what it waits for
        this.context.create().resource(PROCESS + "/waits", Map.of(
            TYPE, IntermediateCatchingEvent.RESOURCE_TYPE, ELEMENT_ID, "waits", "catching", true));
        this.context.resourceResolver().getResource(PROCESS + "/decision/toApproved")
            .adaptTo(ModifiableValueMap.class).put(TARGET_REF, "waits");
        final WorkflowEngine engine = started();

        final WorkflowDefinitionException rejection = assertThrows(WorkflowDefinitionException.class,
            () -> engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), APPROVED));
        assertTrue(rejection.getMessage().contains("cannot yet carry"));
    }

    @Test
    void refusesAGatewayThatMatchesNothingAndHasNoDefault() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        this.context.resourceResolver().getResource(PROCESS + "/decision/toRejected")
            .adaptTo(ModifiableValueMap.class).put("isDefault", false);
        addToTheUserTask(Map.of("outcomeOptions", new String[] {"approved", "rejected", "maybe"}));
        final WorkflowEngine engine = started();

        final WorkflowDefinitionException rejection = assertThrows(WorkflowDefinitionException.class,
            () -> engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), new WorkflowEvent(
                TaskCompletion.COMPLETE_EVENT, Map.of(TaskCompletion.OUTCOME_PARAMETER, "maybe"))));
        assertTrue(rejection.getMessage().contains("none is marked as the default"));
    }

    @Test
    void refusesANodeWithoutExactlyOneWayOut() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        this.context.create().resource(PROCESS + "/" + APPROVE + "/alsoOut", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "alsoOut", TARGET_REF, "decision"));
        final WorkflowEngine engine = started();

        final WorkflowDefinitionException rejection = assertThrows(WorkflowDefinitionException.class,
            () -> engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), APPROVED));
        assertTrue(rejection.getMessage().contains("outgoing sequence flows"));
    }

    @Test
    void refusesAnArcLeadingNowhere() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        this.context.resourceResolver().getResource(PROCESS + "/decision/toApproved")
            .adaptTo(ModifiableValueMap.class).put(TARGET_REF, "nowhere");
        final WorkflowEngine engine = started();

        final WorkflowDefinitionException rejection = assertThrows(WorkflowDefinitionException.class,
            () -> engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), APPROVED));
        assertTrue(rejection.getMessage().contains("does not exist"));
    }

    @Test
    void performsServiceTasksItMeetsAlongTheWay() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        // A service task between the gateway and the end, to prove handlers are reached from inside an instance
        this.context.create().resource(PROCESS + "/record", Map.of(
            TYPE, Activity.RESOURCE_TYPE, ELEMENT_ID, "record", HANDLER, "noop"));
        this.context.create().resource(PROCESS + "/record/toEnd", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toEnd", TARGET_REF, "requestApproved"));
        this.context.resourceResolver().getResource(PROCESS + "/decision/toApproved")
            .adaptTo(ModifiableValueMap.class).put(TARGET_REF, "record");
        createBootstrap();
        reference(HOST, "workflow", PROCESS);
        final RecordingHandler handler = new RecordingHandler();
        final WorkflowEngine engine = engine(handler);
        engine.receiveEvent(host(EngineFixture.REQUESTER), START);

        engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), APPROVED);

        assertEquals(HOST, handler.target);
        assertEquals(List.of("approved"), List.of((String[]) read(HOST).get("tags")));
    }

    @Test
    void doesNothingWhenTheResourceNamesNoWorkflow() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);

        // No `workflow` reference written, so there is nothing to put this under, which is not an error
        createBootstrap();
        final WorkflowEngine engine = engine();
        engine.receiveEvent(host(EngineFixture.REQUESTER), START);

        assertNull(this.context.resourceResolver().getResource(HOST + "/wf:instances/timeOffRequest"));
    }

    /**
     * Raises a submission under the homepage it is aimed at, and reports what it created.
     */
    private static final class RaisingHandler implements ServiceTaskHandler
    {
        static final String NAME = "raise";

        @Override
        public String getName()
        {
            return NAME;
        }

        @Override
        public void execute(final WorkflowTaskContext taskContext) throws PersistenceException
        {
            final ResourceResolver resolver = taskContext.getResourceResolver();
            final Resource raised = resolver.create(taskContext.getTarget(), "raised", Map.of(TYPE, "sub/Submission"));
            resolver.create(raised, "wf:instances", Map.of(TYPE, "wf/WorkflowInstances"));
            try {
                raised.adaptTo(Node.class).setProperty("workflow", resolver.getResource(PROCESS).adaptTo(Node.class));
            } catch (final RepositoryException e) {
                throw new PersistenceException(e.getMessage(), e);
            }
            taskContext.setVariable(WorkflowResult.CREATED_PATH_VARIABLE, raised.getPath());
        }
    }

    /**
     * A handler that records the host it was given.
     */
    private static final class RecordingHandler implements ServiceTaskHandler
    {
        private String target;

        @Override
        public String getName()
        {
            return "noop";
        }

        @Override
        public void execute(final WorkflowTaskContext taskContext)
            throws PersistenceException
        {
            this.target = taskContext.getTarget().getPath();
        }
    }
}

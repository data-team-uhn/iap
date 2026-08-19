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
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.jcr.Node;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.tags.internal.TagOperations;
import io.uhndata.iap.workflows.api.NoApplicableWorkflowException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.EndEvent;
import io.uhndata.iap.workflows.models.ExclusiveGateway;
import io.uhndata.iap.workflows.models.IntermediateCatchingEvent;
import io.uhndata.iap.workflows.models.SequenceFlow;
import io.uhndata.iap.workflows.models.StartEvent;
import io.uhndata.iap.workflows.models.WorkflowFixture;
import io.uhndata.iap.workflows.models.WorkflowVersion;

import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the <em>user</em> workflow runtime — the part that persists — driven the way it actually runs: an event
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

    private static final String BOOTSTRAP = "/SystemWorkflows/putUnderWorkflow/v1";

    private static final WorkflowEvent START = new WorkflowEvent("start", Map.of());

    private static final WorkflowEvent TIMEOUT = new WorkflowEvent("timeout", Map.of());

    private static final WorkflowEvent APPROVED =
        new WorkflowEvent(TaskCompletion.COMPLETE_EVENT, Map.of(TaskCompletion.OUTCOME, "approved"));

    // JCR-backed: the runtime writes a real REFERENCE to the workflow version, which needs a JCR node
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
        this.context.registerService(TagOperations.class, EngineFixture.lifecycleTags());
        this.context.create().resource("/Submissions", TYPE, "sub/SubmissionsHomepage");
        // createdBy is what the engine records when it raises something, jcr:createdBy naming the engine itself;
        // it is what a task coming back to whoever raised the host is answered by
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
            TYPE, "wf/WorkflowDefinition", "title", "Time off request", "active", true));
        this.context.create().resource(PROCESS, Map.of(
            TYPE, WorkflowVersion.RESOURCE_TYPE, "version", "1.0", "active", true));
        this.context.create().resource(PROCESS + "/requestSubmitted", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, ELEMENT_ID, "requestSubmitted"));
        this.context.create().resource(PROCESS + "/requestSubmitted/toApproval", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toApproval", TARGET_REF, APPROVE));
        this.context.create().resource(PROCESS + "/" + APPROVE, Map.of(
            TYPE, Activity.RESOURCE_TYPE, ELEMENT_ID, APPROVE, "label", "Approve the request",
            "performers", performers));
        this.context.create().resource(PROCESS + "/" + APPROVE + "/toDecision", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toDecision", TARGET_REF, "decision"));
        this.context.create().resource(PROCESS + "/decision", Map.of(
            TYPE, ExclusiveGateway.RESOURCE_TYPE, ELEMENT_ID, "decision"));
        this.context.create().resource(PROCESS + "/decision/toApproved", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toApproved", TARGET_REF, "requestApproved"));
        // The guard reads what the execution knows: the outcome the completed task recorded
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
     * Gives an arc the guard "the instance's outcome is this", written the way a definition writes it: a single
     * condition comparing the {@code outcome} variable with a literal.
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
        // The deadline is a fact about this run, so it is on the task rather than worked out later from the
        // definition, which only says how long the wait is
        assertEquals("approvalOverdue", task.get("dueEventId"));
        final Calendar due = (Calendar) task.get("dueDate");
        assertNotNull(due);
        final long days = (due.getTimeInMillis() - System.currentTimeMillis()) / 86400000L;
        assertEquals(4, days, "Five days out, give or take the moment of measuring");
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
        // An event with no duration waits for something to be delivered to it, and nothing yet delivers one, so
        // there is no deadline to start
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

        // The earliest is the one that will actually fire; the others would have needed a token each anyway
        assertEquals("escalate", read(TASK).get("dueEventId"));
    }

    @Test
    void carriesTheInstanceDownTheTimersArcWhenTheDeadlinePasses() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        watchApprovalWith("P5D");
        final WorkflowEngine engine = started();

        engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), TIMEOUT);

        // The task is cancelled rather than completed, and nothing decided it: no assignee, no outcome
        assertEquals("cancelled", read(TASK).get("status"));
        assertNull(read(TASK).get("assignee"));
        assertNull(read(TASK).get("outcome"));
        // Execution left down the timer's own arc, so the host ends in the state that arc leads to
        assertEquals(Set.of("expired"), hostTags());
        assertEquals("completed", read(HOST + "/wf:instances/timeOffRequest").get("status"));
    }

    @Test
    void remindsWithoutEndingTheWorkWhenTheTimerDoesNotInterrupt() throws Exception
    {
        // "Remind them but let them carry on": the approval stays on somebody's desk, and the reminder is a second
        // branch beside it rather than what became of it
        createProcess(EngineFixture.REQUESTERS);
        watchApprovalWith("PT36H", "approvalSlow", null, false);
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

        engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), TIMEOUT);

        // The approval is untouched and there are now two things to do
        assertEquals("created", read(TASK).get("status"));
        assertEquals("created", read(HOST + "/wf:instances/timeOffRequest/chaseApprover").get("status"));
        assertEquals("active", read(HOST + "/wf:instances/timeOffRequest").get("status"));
        // Recorded as spent, and nothing is counting down any more, which is what stops the sweep delivering it again
        assertArrayEquals(new String[] {"approvalSlow"}, (String[]) read(TASK).get("firedEvents"));
        assertNull(read(TASK).get("dueDate"));
        assertNull(read(TASK).get("dueEventId"));
    }

    @Test
    void armsTheNextDeadlineOnceAReminderHasFired() throws Exception
    {
        // "Remind them after a day and a half, give up after five days": the second deadline is measured from when
        // the task started, not from when the reminder went out
        createProcess(EngineFixture.REQUESTERS);
        watchApprovalWith("PT36H", "approvalSlow", null, false);
        watchApprovalWith("P5D", "approvalOverdue", "expired");
        final WorkflowEngine engine = started();
        final Calendar started = (Calendar) read(TASK).get("startTime");

        engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), TIMEOUT);

        assertEquals("approvalOverdue", read(TASK).get("dueEventId"));
        final Calendar due = (Calendar) read(TASK).get("dueDate");
        final Calendar fromTheStart = (Calendar) started.clone();
        fromTheStart.add(Calendar.DATE, 5);
        assertEquals(fromTheStart.getTimeInMillis(), due.getTimeInMillis());

        // And the later deadline still gives up on the task when it passes
        engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), TIMEOUT);

        assertEquals("cancelled", read(TASK).get("status"));
        assertEquals(Set.of("expired"), hostTags());
    }

    @Test
    void deliversAReminderOnlyOnce() throws Exception
    {
        // The sweep looks for tasks whose deadline has passed, and a task a non-interrupting event fired on is still
        // open; nothing is counting down to it any more, so there is nothing left to deliver
        createProcess(EngineFixture.REQUESTERS);
        watchApprovalWith("PT36H", "approvalSlow", null, false);
        final WorkflowEngine engine = started();
        engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), TIMEOUT);

        assertThrows(NoApplicableWorkflowException.class,
            () -> engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), TIMEOUT));
        assertEquals("created", read(TASK).get("status"));
    }

    @Test
    void refusesADeadlineNothingIsCountingDownTo() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        final WorkflowEngine engine = started();

        // Nothing armed this task, so there is no boundary event to leave through: the sweep must not be able to
        // cancel a task by aiming a timeout at it
        assertThrows(NoApplicableWorkflowException.class,
            () -> engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), TIMEOUT));
        assertEquals("created", read(TASK).get("status"));
    }

    @Test
    void letsTheClockPassThroughWhereAPersonMayNot() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        // performers says who may make execution pass through a node, and time belongs to no group: a deadline
        // that could be refused for that would park the instance on a task nobody can ever do
        watchApprovalWith("P5D");
        final WorkflowEngine engine = started();

        engine.receiveEvent(as(TASK, "nobody-in-particular"), TIMEOUT);

        assertEquals("cancelled", read(TASK).get("status"));
    }

    /**
     * Attaches a boundary timer to the approval task, leading to an end event that says the request expired.
     *
     * @param duration how long the timer waits, as an ISO-8601 duration
     */
    private void watchApprovalWith(final String duration)
    {
        watchApprovalWith(duration, "approvalOverdue", "expired");
    }

    /**
     * Attaches a boundary timer to the approval task, leading to an end event of its own.
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
     * Attaches a boundary timer to the approval task, leading to an end event of its own.
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
        // A branch that runs beside the work rather than instead of it says nothing about the host's state, so it
        // gets no tag: passing null is how a caller says the end event is not a lifecycle step
        this.context.create().resource(PROCESS + "/end" + elementId, endTag == null
            ? Map.of(TYPE, EndEvent.RESOURCE_TYPE, ELEMENT_ID, "end" + elementId)
            : Map.of(TYPE, EndEvent.RESOURCE_TYPE, ELEMENT_ID, "end" + elementId, "hostTag", endTag));
    }

    /**
     * Adds properties to the process's user task, for the cases about what a task carries beyond its label.
     *
     * @param properties what to write on the activity
     */
    private void onTheUserTask(final Map<String, Object> properties)
    {
        Objects.requireNonNull(this.context.resourceResolver().getResource(PROCESS + "/" + APPROVE)
            .adaptTo(ModifiableValueMap.class), "The mock repository lets any node be modified").putAll(properties);
    }

    /**
     * The system workflow that puts a submission under its process: the platform's own bootstrap in miniature,
     * since an instance is never started by an event of its own but by a {@code startWorkflow} service task.
     */
    private void createBootstrap()
    {
        this.context.create().resource("/SystemWorkflows", TYPE, "wf/SystemWorkflowsHomepage");
        this.context.create().resource("/SystemWorkflows/putUnderWorkflow", Map.of(
            TYPE, "wf/WorkflowDefinition", "title", "Put a submission under its workflow", "active", true));
        this.context.create().resource(BOOTSTRAP, Map.of(
            TYPE, WorkflowVersion.RESOURCE_TYPE, "version", "1.0", "active", true,
            "targetResourceType", "sub/Submission"));
        this.context.create().resource(BOOTSTRAP + "/raised", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, ELEMENT_ID, "raised", "messageName", "start",
            "performers", new String[] {EngineFixture.REQUESTERS}));
        this.context.create().resource(BOOTSTRAP + "/raised/toStart", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toStart", TARGET_REF, "start"));
        this.context.create().resource(BOOTSTRAP + "/start", Map.of(
            TYPE, Activity.RESOURCE_TYPE, ELEMENT_ID, "start", HANDLER, "startWorkflow",
            "workflowFrom", "workflow"));
        this.context.create().resource(BOOTSTRAP + "/start/toDone", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toDone", TARGET_REF, "done"));
        this.context.create().resource(BOOTSTRAP + "/done", Map.of(
            TYPE, EndEvent.RESOURCE_TYPE, ELEMENT_ID, "done"));
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
     * Builds an engine wired as the DS runtime would wire it, with a service session that can answer who the
     * repository's users are.
     *
     * @return a ready engine
     * @throws Exception when reflection fails, which would be a bug in this test
     */
    private WorkflowEngine engine() throws Exception
    {
        this.context.resourceResolver().commit();
        final WorkflowEngineImpl impl = new WorkflowEngineImpl();
        inject(impl, "resolverFactory", EngineFixture.serviceUsers(this.context, null));
        inject(impl, "handlers", List.of());
        inject(impl, "conditions", EngineFixture.conditions());
        return impl;
    }

    private static void inject(final Object target, final String field, final Object value) throws Exception
    {
        final Field reference = WorkflowEngineImpl.class.getDeclaredField(field);
        reference.setAccessible(true);
        reference.set(target, value);
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
        return new ResourceWrapper(resource)
        {
            @Override
            public ResourceResolver getResourceResolver()
            {
                return resolver;
            }
        };
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
     * The lifecycle the host is in, which an end event is what changes.
     *
     * @return the tags the host carries
     */
    private Set<String> hostTags()
    {
        this.context.resourceResolver().refresh();
        return EngineFixture.tagsOf(
            Objects.requireNonNull(this.context.resourceResolver().getResource(HOST), "The host always exists"));
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
    void movesTheHostIntoTheStateOfTheTaskItIsWaitingAt() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        onTheUserTask(Map.of("hostTag", "in-review"));

        final WorkflowEngine engine = started();

        // Placed on arrival rather than only on finishing: what state a thing is in is where its process has got
        // to, and for almost all of a process's life that is a task somebody has not done yet
        assertEquals(Set.of("in-review"), hostTags());

        engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), APPROVED);

        assertEquals(Set.of("approved"), hostTags());
    }

    @Test
    void raisesTasksCarryingTheDecisionsTheirDefinitionOffers() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        onTheUserTask(Map.of("outcomeOptions", new String[] {"approved", "rejected"}));

        started();

        // Copied onto the task, so that what it may be decided with can be read without reading the definition
        assertArrayEquals(new String[] {"approved", "rejected"},
            (String[]) read(TASK).get("outcomeOptions"));
    }

    @Test
    void raisesTasksOfferingNothingWhenThereIsNothingToDecide() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);

        started();

        assertEquals(0, ((String[]) read(TASK).get("outcomeOptions")).length);
    }

    @Test
    void raisesTasksNamingWhoMayCompleteThem() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);

        started();

        // So that "what is waiting for me" is a question about tasks: whoever owes the decision cannot
        // necessarily read the definition this came from
        assertArrayEquals(new String[] {EngineFixture.REQUESTERS},
            (String[]) read(TASK).get("performers"));
    }

    @Test
    void answersCreatorAgainstTheHostWhenItRecordsWhoMayCompleteATask() throws Exception
    {
        // "@creator" is a question about this host, and nothing reading the task later is holding the host to
        // ask it — so it is answered once, here, and what is recorded stands on its own
        createProcess(PerformerCheck.CREATOR);

        started();

        assertArrayEquals(new String[] {EngineFixture.REQUESTER},
            (String[]) read(TASK).get("performers"));
    }

    @Test
    void admitsWhoeverRaisedTheHostToATaskThatComesBackToThem() throws Exception
    {
        // The rule a group cannot express: this request comes back to the person who made it, not to everyone
        // who could have made one
        createProcess(PerformerCheck.CREATOR);
        final WorkflowEngine engine = started();

        engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), APPROVED);

        assertEquals("completed", read(TASK).get("status"));
    }

    @Test
    void admitsThemEvenWhenTheyTypedTheirNameDifferentlyAtLogin() throws Exception
    {
        // A login resolves case-insensitively, so the same person arrives as "demo-requester" one day and as
        // "DEMO-REQUESTER" the next while the repository knows them as one user. @creator compares the actor
        // against what was recorded when the host was raised, so an actor taken from the spelling would refuse
        // the very person the task belongs to
        createProcess(PerformerCheck.CREATOR);
        final WorkflowEngine engine = started();

        engine.receiveEvent(EngineFixture.typedAtLogin(as(TASK, EngineFixture.REQUESTER),
            EngineFixture.REQUESTER.toUpperCase(Locale.ROOT)), APPROVED);

        assertEquals("completed", read(TASK).get("status"));
    }

    @Test
    void refusesSomebodyElseAtATaskThatComesBackToWhoeverRaisedTheHost() throws Exception
    {
        createProcess(PerformerCheck.CREATOR);
        final WorkflowEngine engine = started();

        assertThrows(NotAuthorizedException.class,
            () -> engine.receiveEvent(as(TASK, "somebody-else"), APPROVED));
        assertEquals("created", read(TASK).get("status"));
    }

    @Test
    void grantsReadToEveryoneTheProcessInvolves() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);

        started();

        // The person it is being run for, and the performers of its user tasks — derived from the definition
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

        assertNull(result.getVariable(WorkflowResult.CREATED_PATH));
        assertEquals("completed", read(TASK).get("status"));
        assertEquals("approved", read(TASK).get("outcome"));
        assertEquals(EngineFixture.REQUESTER, read(TASK).get("assignee"));
        final Map<String, Object> instance = read(HOST + "/wf:instances/timeOffRequest");
        assertEquals("completed", instance.get("status"));
        assertNotNull(instance.get("endTime"));
        // The token is spent, and the end event said what finishing that way means to the host
        assertTrue(read(HOST + "/wf:instances/timeOffRequest/token").isEmpty());
        assertEquals(Set.of("approved"), hostTags());
    }

    @Test
    void takesTheDefaultArcWhenNoConditionMatches() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        final WorkflowEngine engine = started();

        engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), new WorkflowEvent(
            TaskCompletion.COMPLETE_EVENT, Map.of(TaskCompletion.OUTCOME, "rejected")));

        assertEquals(Set.of("rejected"), hostTags());
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
        assertEquals(Set.of("draft"), hostTags());
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
        final WorkflowEngine engine = started();

        // No outcome recorded, so nothing matches and the default arc carries it
        engine.receiveEvent(as(TASK, EngineFixture.REQUESTER),
            new WorkflowEvent(TaskCompletion.COMPLETE_EVENT, Map.of()));

        assertEquals(Set.of("rejected"), hostTags());
        assertNull(read(TASK).get("outcome"));
    }

    @Test
    void leavesNothingBehindWhenTheDecisionCannotBeCommitted() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        started();
        final WorkflowEngineImpl engine = new WorkflowEngineImpl();
        inject(engine, "resolverFactory", EngineFixture.serviceUsers(this.context,
            new PersistenceException("the disk is on fire")));
        inject(engine, "handlers", List.of());
        inject(engine, "conditions", EngineFixture.conditions());

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
        assertEquals(Set.of("approved"), hostTags());
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
    void refusesToStartAnInactiveProcess() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);
        this.context.resourceResolver().getResource(PROCESS)
            .adaptTo(ModifiableValueMap.class).put("active", false);

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
        final WorkflowEngine engine = started();

        final WorkflowDefinitionException rejection = assertThrows(WorkflowDefinitionException.class,
            () -> engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), new WorkflowEvent(
                TaskCompletion.COMPLETE_EVENT, Map.of(TaskCompletion.OUTCOME, "maybe"))));
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
        final WorkflowEngineImpl engine = new WorkflowEngineImpl();
        inject(engine, "resolverFactory", EngineFixture.serviceUsers(this.context, null));
        inject(engine, "handlers", List.of(handler));
        inject(engine, "conditions", EngineFixture.conditions());
        engine.receiveEvent(host(EngineFixture.REQUESTER), START);

        engine.receiveEvent(as(TASK, EngineFixture.REQUESTER), APPROVED);

        assertEquals(HOST, handler.target);
        assertEquals(Set.of("approved"), hostTags());
    }

    @Test
    void doesNothingWhenTheResourceNamesNoWorkflow() throws Exception
    {
        createProcess(EngineFixture.REQUESTERS);

        // No `workflow` reference written, so there is nothing to put this under — which is not an error
        createBootstrap();
        final WorkflowEngine engine = engine();
        engine.receiveEvent(host(EngineFixture.REQUESTER), START);

        assertNull(this.context.resourceResolver().getResource(HOST + "/wf:instances/timeOffRequest"));
    }

    /**
     * A handler that records the host it was given.
     */
    private static final class RecordingHandler implements io.uhndata.iap.workflows.spi.ServiceTaskHandler
    {
        private String target;

        @Override
        public String getName()
        {
            return "noop";
        }

        @Override
        public void execute(final io.uhndata.iap.workflows.spi.WorkflowTaskContext taskContext)
            throws PersistenceException
        {
            this.target = taskContext.getTarget().getPath();
        }
    }
}

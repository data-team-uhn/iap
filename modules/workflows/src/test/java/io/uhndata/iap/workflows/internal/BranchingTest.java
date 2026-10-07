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
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.jcr.Node;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.internal.handlers.StartWorkflowHandler;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.EndEvent;
import io.uhndata.iap.workflows.models.ExclusiveGateway;
import io.uhndata.iap.workflows.models.InclusiveGateway;
import io.uhndata.iap.workflows.models.ParallelGateway;
import io.uhndata.iap.workflows.models.SequenceFlow;
import io.uhndata.iap.workflows.models.StartEvent;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowFixture;
import io.uhndata.iap.workflows.models.WorkflowInstance;
import io.uhndata.iap.workflows.models.WorkflowVersion;

import static io.uhndata.iap.workflows.models.WorkflowFixture.ACTIVE;
import static io.uhndata.iap.workflows.models.WorkflowFixture.TAGS;
import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;
import static io.uhndata.iap.workflows.models.WorkflowFixture.tags;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of a process that runs more than one branch at once. A gateway forks a token per branch, and the join holds
 * them until the branches it waits for have arrived.
 *
 * <p>The tests drive the engine and check what a person would see: which tasks are open, and whether the request is
 * finished.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class BranchingTest
{
    private static final String ELEMENT_ID = "elementId";

    private static final String TARGET_REF = "targetRef";

    private static final String HOST = "/Submissions/aLongWeekend";

    private static final String PROCESS = "/Workflows/timeOffRequest/v1";

    private static final String INSTANCE = HOST + "/wf:instances/timeOffRequest";

    private static final String BOOTSTRAP = "/SystemWorkflows/putUnderWorkflow/v1";

    private static final String FORK = "split";

    private static final String JOIN = "merge";

    private static final WorkflowEvent START = new WorkflowEvent("start", Map.of());

    private static final WorkflowEvent DONE = new WorkflowEvent(TaskCompletion.COMPLETE_EVENT, Map.of());

    // JCR-backed: the host points at its workflow with a real REFERENCE, which needs a JCR node
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
        WorkflowFixture.enableTagging(this.context);
        this.context.create().resource("/Submissions", TYPE, "sub/SubmissionsHomepage");
        this.context.create().resource(HOST, Map.of(TYPE, "sub/Submission", "tags", new String[] {"draft"},
            "createdBy", EngineFixture.REQUESTER));
        this.context.create().resource(HOST + "/wf:instances", TYPE, "wf/WorkflowInstances");
    }

    @Test
    void forksATokenAndATaskPerBranch() throws Exception
    {
        branching();

        assertEquals(2, instance().getTokens().size());
        assertEquals(List.of("Approve the request", "Book the cover"), openTasks());
    }

    @Test
    void waitsAtTheJoinUntilEveryBranchArrives() throws Exception
    {
        final WorkflowEngine engine = branching();

        engine.receiveEvent(as(INSTANCE + "/approve", EngineFixture.REQUESTER), DONE);

        assertEquals("active", instance().getStatus());
        assertEquals(2, instance().getTokens().size());
        assertEquals(1, instance().getTokens().stream()
            .filter(token -> JOIN.equals(token.getCurrentNodeId())).count());
        assertEquals(List.of("Book the cover"), openTasks());
    }

    @Test
    void mergesTheBranchesBackIntoOneTokenAndFinishes() throws Exception
    {
        final WorkflowEngine engine = branching();

        engine.receiveEvent(as(INSTANCE + "/approve", EngineFixture.REQUESTER), DONE);
        engine.receiveEvent(as(INSTANCE + "/cover", EngineFixture.REQUESTER), DONE);

        assertEquals("completed", instance().getStatus());
        assertEquals(0, instance().getTokens().size());
        assertEquals(List.of(), openTasks());
        assertTrue(List.of(Objects.requireNonNull(this.context.resourceResolver().getResource(HOST),
            "The host always exists").getValueMap().get("tags", String[].class)).contains("approved"));
    }

    @Test
    void staysRunningWhileAnotherBranchIsStillGoing() throws Exception
    {
        // A third branch goes from the fork straight to an end event of its own
        createProcess();
        this.context.create().resource(PROCESS + "/" + FORK + "/toNote", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toNote", TARGET_REF, "noted"));
        this.context.create().resource(PROCESS + "/noted", Map.of(
            TYPE, EndEvent.RESOURCE_TYPE, ELEMENT_ID, "noted"));
        start();

        // The third branch is spent, the two tasks are still waiting
        assertEquals("active", instance().getStatus());
        assertEquals(2, instance().getTokens().size());
        assertEquals(2, openTasks().size());
    }

    @Test
    void endsTheWholeInstanceAtATerminateEndEvent() throws Exception
    {
        createProcess();
        this.context.create().resource(PROCESS + "/" + FORK + "/toWithdraw", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toWithdraw", TARGET_REF, "withdraw"));
        task("withdraw", "Withdraw the request");
        // The withdrawal leads to a terminate end event instead of the join
        this.context.resourceResolver().delete(
            this.context.resourceResolver().getResource(PROCESS + "/withdraw/toJoin"));
        this.context.create().resource(PROCESS + "/withdraw/toWithdrawn", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "withdrawToEnd", TARGET_REF, "withdrawn"));
        this.context.create().resource(PROCESS + "/withdrawn", Map.of(
            TYPE, EndEvent.RESOURCE_TYPE, ELEMENT_ID, "withdrawn", "terminate", true));
        final WorkflowEngine engine = start();
        assertEquals(3, openTasks().size());

        engine.receiveEvent(as(INSTANCE + "/withdraw", EngineFixture.REQUESTER), DONE);

        assertEquals("completed", instance().getStatus());
        assertEquals(0, instance().getTokens().size());
        assertEquals(List.of(), openTasks());
        assertEquals(List.of("cancelled", "cancelled", "completed"), instance().getTaskInstances().stream()
            .map(TaskInstance::getStatus).sorted().toList());
    }

    @Test
    void refusesAConditionOnAParallelArc() throws Exception
    {
        createProcess();
        this.context.create().resource(PROCESS + "/" + FORK + "/toApprove/cond:condition", Map.of(
            TYPE, "cond/SingleCondition", "comparator", "equals"));

        final WorkflowDefinitionException refusal =
            assertThrows(WorkflowDefinitionException.class, this::start);

        assertTrue(refusal.getMessage().contains("carries a condition"), refusal.getMessage());
    }

    @Test
    void refusesAParallelGatewayWithNowhereToGo() throws Exception
    {
        this.context.create().resource("/Workflows/timeOffRequest", Map.of(
            TYPE, "wf/WorkflowDefinition", "title", "Time off request"));
        this.context.create().resource(PROCESS, Map.of(
            TYPE, WorkflowVersion.RESOURCE_TYPE, "version", "1.0", TAGS, tags(ACTIVE)));
        this.context.create().resource(PROCESS + "/requestSubmitted", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, ELEMENT_ID, "requestSubmitted"));
        this.context.create().resource(PROCESS + "/requestSubmitted/toFork", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toFork", TARGET_REF, FORK));
        this.context.create().resource(PROCESS + "/" + FORK, Map.of(
            TYPE, ParallelGateway.RESOURCE_TYPE, ELEMENT_ID, FORK));

        final WorkflowDefinitionException refusal =
            assertThrows(WorkflowDefinitionException.class, this::start);

        assertTrue(refusal.getMessage().contains("no outgoing sequence flow"), refusal.getMessage());
    }

    @Test
    void mergesAForkThatLeadsStraightIntoItsOwnJoin() throws Exception
    {
        // Both branches reach the join in the same walk, so the second is merged away while it is still queued
        forkStraightIntoJoin(ParallelGateway.RESOURCE_TYPE);

        start();

        assertEquals("completed", instance().getStatus());
        assertEquals(0, instance().getTokens().size());
        assertTrue(List.of(Objects.requireNonNull(this.context.resourceResolver().getResource(HOST),
            "The host always exists").getValueMap().get("tags", String[].class)).contains("approved"));
    }

    @Test
    void releasesAnInclusiveJoinWhenTheOtherBranchCannotReachIt() throws Exception
    {
        // A third branch leads to a task whose way out is an end event, not the join
        forkStraightIntoJoin(InclusiveGateway.RESOURCE_TYPE);
        this.context.create().resource(PROCESS + "/" + FORK + "/toAside", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toAside", TARGET_REF, "aside"));
        this.context.create().resource(PROCESS + "/aside", Map.of(
            TYPE, Activity.RESOURCE_TYPE, ELEMENT_ID, "aside", "label", "Note it in the file",
            "performers", new String[] {EngineFixture.REQUESTERS}));
        this.context.create().resource(PROCESS + "/aside/toNoted", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "asideToNoted", TARGET_REF, "noted"));
        this.context.create().resource(PROCESS + "/noted", Map.of(
            TYPE, EndEvent.RESOURCE_TYPE, ELEMENT_ID, "noted"));

        start();

        assertTrue(List.of(Objects.requireNonNull(this.context.resourceResolver().getResource(HOST),
            "The host always exists").getValueMap().get("tags", String[].class)).contains("approved"));
        assertEquals("active", instance().getStatus());
        assertEquals(List.of("Note it in the file"), openTasks());
        assertEquals(1, instance().getTokens().size());
    }

    @Test
    void fallsBackOnTheDefaultArcWhenNoInclusiveBranchApplies() throws Exception
    {
        // Every arc has a guard, the default's included, and none of them holds
        inclusive();
        outcomeIs(PROCESS + "/" + FORK + "/toApprove", "approved");
        outcomeIs(PROCESS + "/" + FORK + "/toCover", "approved");
        this.context.create().resource(PROCESS + "/" + FORK + "/toNoted", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toNoted", TARGET_REF, "noted", "isDefault", true));
        outcomeIs(PROCESS + "/" + FORK + "/toNoted", "anything");
        this.context.create().resource(PROCESS + "/noted", Map.of(
            TYPE, EndEvent.RESOURCE_TYPE, ELEMENT_ID, "noted", "hostTag", "expired"));

        start();

        assertEquals("completed", instance().getStatus());
        assertEquals(List.of(), openTasks());
        assertTrue(List.of(Objects.requireNonNull(this.context.resourceResolver().getResource(HOST),
            "The host always exists").getValueMap().get("tags", String[].class)).contains("expired"));
    }

    @Test
    void takesOnlyTheInclusiveArcsThatApply() throws Exception
    {
        // The approval arc has no guard, and the cover arc asks for an outcome nothing has recorded
        inclusive();
        outcomeIs(PROCESS + "/" + FORK + "/toCover", "approved");
        start();

        assertEquals(1, instance().getTokens().size());
        assertEquals(List.of("Approve the request"), openTasks());
    }

    @Test
    void holdsAnInclusiveJoinWhileABranchIsStillRunning() throws Exception
    {
        inclusive();
        final WorkflowEngine engine = start();

        engine.receiveEvent(as(INSTANCE + "/approve", EngineFixture.REQUESTER), DONE);

        assertEquals("active", instance().getStatus());
        assertEquals(List.of("Book the cover"), openTasks());

        engine.receiveEvent(as(INSTANCE + "/cover", EngineFixture.REQUESTER), DONE);

        assertEquals("completed", instance().getStatus());
        assertEquals(0, instance().getTokens().size());
    }

    @Test
    void releasesAnInclusiveJoinOnceNoBranchCanStillReachIt() throws Exception
    {
        this.context.create().resource("/Workflows/timeOffRequest", Map.of(
            TYPE, "wf/WorkflowDefinition", "title", "Time off request"));
        this.context.create().resource(PROCESS, Map.of(
            TYPE, WorkflowVersion.RESOURCE_TYPE, "version", "1.0", TAGS, tags(ACTIVE)));
        this.context.create().resource(PROCESS + "/requestSubmitted", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, ELEMENT_ID, "requestSubmitted"));
        this.context.create().resource(PROCESS + "/requestSubmitted/toFork", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toFork", TARGET_REF, FORK));
        this.context.create().resource(PROCESS + "/" + FORK, Map.of(
            TYPE, InclusiveGateway.RESOURCE_TYPE, ELEMENT_ID, FORK));
        this.context.create().resource(PROCESS + "/" + FORK + "/toJoin", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "forkToJoin", TARGET_REF, JOIN));
        this.context.create().resource(PROCESS + "/" + FORK + "/toCheck", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toCheck", TARGET_REF, "check"));
        // The second branch could reach the join, but no outcome is recorded, so it leaves by the default arc instead
        this.context.create().resource(PROCESS + "/check", Map.of(
            TYPE, ExclusiveGateway.RESOURCE_TYPE, ELEMENT_ID, "check"));
        this.context.create().resource(PROCESS + "/check/toJoinLate", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "checkToJoin", TARGET_REF, JOIN));
        outcomeIs(PROCESS + "/check/toJoinLate", "approved");
        this.context.create().resource(PROCESS + "/check/toNoted", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toNoted", TARGET_REF, "noted", "isDefault", true));
        this.context.create().resource(PROCESS + "/noted", Map.of(
            TYPE, EndEvent.RESOURCE_TYPE, ELEMENT_ID, "noted"));
        this.context.create().resource(PROCESS + "/" + JOIN, Map.of(
            TYPE, InclusiveGateway.RESOURCE_TYPE, ELEMENT_ID, JOIN));
        this.context.create().resource(PROCESS + "/" + JOIN + "/toApproved", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toApproved", TARGET_REF, "requestApproved"));
        this.context.create().resource(PROCESS + "/requestApproved", Map.of(
            TYPE, EndEvent.RESOURCE_TYPE, ELEMENT_ID, "requestApproved", "hostTag", "approved"));

        start();

        assertEquals("completed", instance().getStatus());
        assertEquals(0, instance().getTokens().size());
    }

    @Test
    void refusesAnInclusiveGatewayWhereNothingAppliesAndThereIsNoDefault() throws Exception
    {
        inclusive();
        outcomeIs(PROCESS + "/" + FORK + "/toApprove", "approved");
        outcomeIs(PROCESS + "/" + FORK + "/toCover", "approved");

        final WorkflowDefinitionException refusal =
            assertThrows(WorkflowDefinitionException.class, this::start);

        assertTrue(refusal.getMessage().contains("none is marked as the default"), refusal.getMessage());
    }

    /**
     * Builds the smallest branching process: a gateway of the given kind whose two arcs lead straight into its join,
     * then one end event.
     *
     * @param gatewayType the resource type of the gateway to fork and join with
     */
    private void forkStraightIntoJoin(final String gatewayType)
    {
        this.context.create().resource("/Workflows/timeOffRequest", Map.of(
            TYPE, "wf/WorkflowDefinition", "title", "Time off request"));
        this.context.create().resource(PROCESS, Map.of(
            TYPE, WorkflowVersion.RESOURCE_TYPE, "version", "1.0", TAGS, tags(ACTIVE)));
        this.context.create().resource(PROCESS + "/requestSubmitted", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, ELEMENT_ID, "requestSubmitted"));
        this.context.create().resource(PROCESS + "/requestSubmitted/toFork", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toFork", TARGET_REF, FORK));
        this.context.create().resource(PROCESS + "/" + FORK, Map.of(
            TYPE, gatewayType, ELEMENT_ID, FORK));
        this.context.create().resource(PROCESS + "/" + FORK + "/toJoinFirst", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toJoinFirst", TARGET_REF, JOIN));
        this.context.create().resource(PROCESS + "/" + FORK + "/toJoinSecond", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toJoinSecond", TARGET_REF, JOIN));
        this.context.create().resource(PROCESS + "/" + JOIN, Map.of(
            TYPE, gatewayType, ELEMENT_ID, JOIN));
        this.context.create().resource(PROCESS + "/" + JOIN + "/toApproved", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toApproved", TARGET_REF, "requestApproved"));
        this.context.create().resource(PROCESS + "/requestApproved", Map.of(
            TYPE, EndEvent.RESOURCE_TYPE, ELEMENT_ID, "requestApproved", "hostTag", "approved"));
    }

    /**
     * Builds the branching process with inclusive gateways instead of parallel ones.
     */
    private void inclusive()
    {
        createProcess();
        for (final String gateway : List.of(FORK, JOIN)) {
            Objects.requireNonNull(this.context.resourceResolver()
                    .getResource(PROCESS + "/" + gateway).adaptTo(ModifiableValueMap.class))
                .put(TYPE, InclusiveGateway.RESOURCE_TYPE);
        }
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

    /**
     * Builds a process that forks into two user tasks and joins them back: start, a parallel fork, a task on each
     * branch, a parallel join, one end event.
     */
    private void createProcess()
    {
        this.context.create().resource("/Workflows/timeOffRequest", Map.of(
            TYPE, "wf/WorkflowDefinition", "title", "Time off request"));
        this.context.create().resource(PROCESS, Map.of(
            TYPE, WorkflowVersion.RESOURCE_TYPE, "version", "1.0", TAGS, tags(ACTIVE)));
        this.context.create().resource(PROCESS + "/requestSubmitted", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, ELEMENT_ID, "requestSubmitted"));
        this.context.create().resource(PROCESS + "/requestSubmitted/toFork", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toFork", TARGET_REF, FORK));

        this.context.create().resource(PROCESS + "/" + FORK, Map.of(
            TYPE, ParallelGateway.RESOURCE_TYPE, ELEMENT_ID, FORK));
        this.context.create().resource(PROCESS + "/" + FORK + "/toApprove", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toApprove", TARGET_REF, "approve"));
        this.context.create().resource(PROCESS + "/" + FORK + "/toCover", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toCover", TARGET_REF, "cover"));

        task("approve", "Approve the request");
        task("cover", "Book the cover");

        this.context.create().resource(PROCESS + "/" + JOIN, Map.of(
            TYPE, ParallelGateway.RESOURCE_TYPE, ELEMENT_ID, JOIN));
        this.context.create().resource(PROCESS + "/" + JOIN + "/toApproved", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toApproved", TARGET_REF, "requestApproved"));
        this.context.create().resource(PROCESS + "/requestApproved", Map.of(
            TYPE, EndEvent.RESOURCE_TYPE, ELEMENT_ID, "requestApproved", "hostTag", "approved"));
    }

    /**
     * One user task on a branch, leading to the join.
     *
     * @param elementId the activity's identifier, which is also its node name
     * @param label what the task is called
     */
    private void task(final String elementId, final String label)
    {
        this.context.create().resource(PROCESS + "/" + elementId, Map.of(
            TYPE, Activity.RESOURCE_TYPE, ELEMENT_ID, elementId, "label", label,
            "performers", new String[] {EngineFixture.REQUESTERS}));
        this.context.create().resource(PROCESS + "/" + elementId + "/toJoin", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, elementId + "ToJoin", TARGET_REF, JOIN));
    }

    /**
     * Builds the branching process and starts an instance of it.
     *
     * @return the engine, ready for the next event
     * @throws Exception when the fixture cannot be built
     */
    private WorkflowEngine branching() throws Exception
    {
        createProcess();
        return start();
    }

    /**
     * Starts an instance: points the host at the process and runs the bootstrap.
     *
     * @return the engine, ready for the next event
     * @throws Exception when the fixture cannot be built
     */
    private WorkflowEngine start() throws Exception
    {
        createBootstrap();
        reference(HOST, "workflow", PROCESS);
        final WorkflowEngine engine = engine();
        engine.receiveEvent(as(HOST, EngineFixture.REQUESTER), START);
        return engine;
    }

    /**
     * Creates the system workflow that puts a submission under its process.
     */
    private void createBootstrap()
    {
        this.context.create().resource("/SystemWorkflows", TYPE, "wf/SystemWorkflowsHomepage");
        this.context.create().resource("/SystemWorkflows/putUnderWorkflow", Map.of(
            TYPE, "wf/WorkflowDefinition", "title", "Put a submission under its workflow"));
        this.context.create().resource(BOOTSTRAP, Map.of(
            TYPE, WorkflowVersion.RESOURCE_TYPE, "version", "1.0", TAGS, tags(ACTIVE),
            "targetResourceType", "sub/Submission"));
        this.context.create().resource(BOOTSTRAP + "/raised", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, ELEMENT_ID, "raised", "messageName", "start",
            "performers", new String[] {EngineFixture.REQUESTERS}));
        this.context.create().resource(BOOTSTRAP + "/raised/toStart", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toStart", TARGET_REF, "start"));
        this.context.create().resource(BOOTSTRAP + "/start", Map.of(
            TYPE, Activity.RESOURCE_TYPE, ELEMENT_ID, "start", "handler", "startWorkflow",
            "workflowFrom", "workflow"));
        this.context.create().resource(BOOTSTRAP + "/start/toDone", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toDone", TARGET_REF, "done"));
        this.context.create().resource(BOOTSTRAP + "/done", Map.of(
            TYPE, EndEvent.RESOURCE_TYPE, ELEMENT_ID, "done"));
    }

    /**
     * Reads the instance the engine started, through a refreshed session.
     *
     * @return the running instance
     */
    private WorkflowInstance instance()
    {
        this.context.resourceResolver().refresh();
        return Objects.requireNonNull(
            this.context.resourceResolver().getResource(INSTANCE).adaptTo(WorkflowInstance.class));
    }

    /**
     * The labels of the tasks still waiting for somebody, sorted.
     *
     * @return task labels
     */
    private List<String> openTasks()
    {
        return instance().getTaskInstances().stream()
            .filter(task -> "created".equals(task.getStatus()))
            .map(TaskInstance::getLabel)
            .sorted()
            .toList();
    }

    /**
     * Builds an engine with its services injected by hand.
     *
     * @return a ready engine
     * @throws Exception when reflection fails, which would be a bug in this test
     */
    private WorkflowEngine engine() throws Exception
    {
        this.context.resourceResolver().commit();
        final WorkflowEngineImpl impl = new WorkflowEngineImpl();
        inject(impl, "resolverFactory", EngineFixture.serviceUsers(this.context, null));
        inject(impl, "handlers", List.of(new StartWorkflowHandler()));
        inject(impl, "conditionEvaluator", EngineFixture.conditions());
        return impl;
    }

    private static void inject(final Object target, final String field, final Object value) throws Exception
    {
        final Field reference = WorkflowEngineImpl.class.getDeclaredField(field);
        reference.setAccessible(true);
        reference.set(target, value);
    }

    /**
     * Writes a JCR REFERENCE from one node to another.
     *
     * @param from the node to write on
     * @param property the property to write
     * @param to the node to point at
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
     * Resolves a resource in a session that reports the given user.
     *
     * @param path what to resolve
     * @param actor who is asking
     * @return the resource, reporting that user as its session's owner
     */
    private Resource as(final String path, final String actor)
    {
        this.context.resourceResolver().refresh();
        final Resource resource = this.context.resourceResolver().getResource(path);
        final ResourceResolver resolver = new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public String getUserID()
            {
                return actor;
            }
        };
        return new ResourceWrapper(resource)
        {
            @Override
            public ResourceResolver getResourceResolver()
            {
                return resolver;
            }
        };
    }
}

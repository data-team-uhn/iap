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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.jcr.AccessDeniedException;
import javax.jcr.InvalidItemStateException;
import javax.jcr.nodetype.ConstraintViolationException;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.conditions.internal.ConditionEvaluatorImpl;
import io.uhndata.iap.conditions.internal.LiteralOperandResolver;
import io.uhndata.iap.conditions.internal.TagsOperandResolver;
import io.uhndata.iap.conditions.models.Condition;
import io.uhndata.iap.conditions.models.ConditionOperand;
import io.uhndata.iap.conditions.models.SingleCondition;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.NoApplicableWorkflowException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.EndEvent;
import io.uhndata.iap.workflows.models.IntermediateCatchingEvent;
import io.uhndata.iap.workflows.models.SequenceFlow;
import io.uhndata.iap.workflows.models.StartEvent;
import io.uhndata.iap.workflows.models.SystemWorkflowsHomepage;
import io.uhndata.iap.workflows.models.WorkflowFixture;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.models.WorkflowsHomepage;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static io.uhndata.iap.workflows.internal.EngineFixture.VERSION;
import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WorkflowEngineImpl}: matching an event to the single waiting system workflow, running it
 * straight through in one commit, and rejecting definitions that cannot be run that way.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class WorkflowEngineImplTest
{
    private static final String ELEMENT_ID = "elementId";

    private static final String SUPER_TYPE = "sling:resourceSuperType";

    /** A second system workflow, for the tests where two of them catch the same event. */
    private static final String OTHER_VERSION = "/SystemWorkflows/otherWorkflow/v1";

    private static final WorkflowEvent CREATE =
        new WorkflowEvent("create", Map.of("title", "My cool workflow"));

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
        this.context.addModelsForClasses(SingleCondition.class, ConditionOperand.class);
        this.context.create().resource("/libs/cond/Condition", Map.of(SUPER_TYPE, "data/EntityPart"));
        this.context.create().resource("/libs/cond/SingleCondition", Map.of(SUPER_TYPE, Condition.RESOURCE_TYPE));
        this.context.create().resource("/libs/cond/ConditionOperand", Map.of(SUPER_TYPE, "data/EntityPart"));
    }

    /**
     * Builds an engine wired the way the DS runtime would wire it: the mock context's resolver factory, plus the
     * real entity-creating handler and whatever extra handlers a test needs. Wiring is by reflection, the way the
     * other component tests do it, since the SCR metadata only exists in the packaged bundle.
     *
     * @param extraHandlers additional handlers to register beside {@link CreateEntityHandler}
     * @return a ready engine
     * @throws Exception when reflection fails, which would be a bug in this test
     */
    private WorkflowEngine engine(final ServiceTaskHandler... extraHandlers) throws Exception
    {
        return engine(null, extraHandlers);
    }

    /**
     * Builds an engine whose session fails to commit, so that the tests can observe how the engine translates
     * repository failures. The writes themselves succeed; only the commit fails.
     *
     * @param failure what the engine's commit throws, or {@code null} for a session that commits normally
     * @param extraHandlers additional handlers to register beside {@link CreateEntityHandler}
     * @return a ready engine
     * @throws Exception when reflection fails, which would be a bug in this test
     */
    private WorkflowEngine engine(final PersistenceException failure, final ServiceTaskHandler... extraHandlers)
        throws Exception
    {
        // The fixture content was written through the test's own session; the engine matches through its service
        // session, which only sees what has been committed
        this.context.resourceResolver().commit();
        final WorkflowEngineImpl impl = new WorkflowEngineImpl();
        inject(impl, "resolverFactory", EngineFixture.serviceUsers(this.context, failure));
        final List<ServiceTaskHandler> allHandlers = new ArrayList<>(List.of(extraHandlers));
        allHandlers.add(new CreateEntityHandler());
        inject(impl, "handlers", allHandlers);
        final ConditionEvaluatorImpl evaluator = new ConditionEvaluatorImpl();
        final Field resolvers = ConditionEvaluatorImpl.class.getDeclaredField("resolvers");
        resolvers.setAccessible(true);
        resolvers.set(evaluator, List.of(new LiteralOperandResolver(), new TagsOperandResolver()));
        inject(impl, "conditionEvaluator", evaluator);
        return impl;
    }

    /**
     * Builds an engine whose service sessions are recorded as they are opened, so that a test can count them and
     * see them closed.
     *
     * @param opened where to record them
     * @return a ready engine
     * @throws Exception when reflection fails, which would be a bug in this test
     */
    private WorkflowEngine recording(final List<OpenedSession> opened) throws Exception
    {
        final WorkflowEngine engine = engine();
        final ResourceResolverFactory serviceUsers = EngineFixture.serviceUsers(this.context, null);
        final ResourceResolverFactory factory = Mockito.mock(ResourceResolverFactory.class);
        Mockito.when(factory.getServiceResourceResolver(Mockito.anyMap())).thenAnswer(invocation -> {
            final OpenedSession session =
                new OpenedSession(serviceUsers.getServiceResourceResolver(invocation.getArgument(0)));
            opened.add(session);
            return session;
        });
        inject(engine, "resolverFactory", factory);
        return engine;
    }

    private static void inject(final Object target, final String field, final Object value) throws Exception
    {
        final Field reference = WorkflowEngineImpl.class.getDeclaredField(field);
        reference.setAccessible(true);
        reference.set(target, value);
    }

    @Test
    void executesTheBootstrapWorkflow() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        EngineFixture.createBootstrapGraph(this.context);

        final WorkflowResult result = engine().receiveEvent(target, CREATE);

        assertEquals("/Workflows/myCoolWorkflow", result.getVariable(WorkflowResult.CREATED_PATH_VARIABLE));
        final Resource created = this.context.resourceResolver().getResource("/Workflows/myCoolWorkflow");
        assertNotNull(created);
        assertEquals("My cool workflow", created.getValueMap().get("title"));
        // The engine did the writing, so the repository's own jcr:createdBy names the service user; who actually
        // asked for this is only remembered because the engine records it
        assertEquals(EngineFixture.ADMIN, created.getValueMap().get("createdBy"));
    }

    @Test
    void admitsAnActorTheStartEventNames() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context, EngineFixture.REQUESTER);
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        EngineFixture.createBootstrapGraph(this.context, EngineFixture.REQUESTERS);

        final WorkflowResult result = engine().receiveEvent(target, CREATE);

        assertEquals("/Workflows/myCoolWorkflow", result.getVariable(WorkflowResult.CREATED_PATH_VARIABLE));
        // Written by the engine on behalf of a user who holds no rights at all on /Workflows
        final Resource created = this.context.resourceResolver().getResource("/Workflows/myCoolWorkflow");
        assertNotNull(created);
        assertEquals(EngineFixture.REQUESTER, created.getValueMap().get("createdBy"));
    }

    @Test
    void tellsTheHandlerWhoItIsActingFor() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context, EngineFixture.REQUESTER);
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        createNoopGraph(EngineFixture.REQUESTERS);
        final ActorRecordingHandler handler = new ActorRecordingHandler();

        final WorkflowResult result = engine(handler).receiveEvent(target, CREATE);

        // The handler is privileged, so knowing the actor is the only way it can act on their behalf
        assertEquals(EngineFixture.REQUESTER, handler.seen);
        // A workflow that creates nothing completes all the same: there is simply nowhere to send the caller
        assertNull(result.getVariable(WorkflowResult.CREATED_PATH_VARIABLE));
    }

    @Test
    void refusesAnActorTheStartEventDoesNotName() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context, EngineFixture.REQUESTER);
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        EngineFixture.createBootstrapGraph(this.context, "some-other-group");

        final WorkflowEngine engine = engine();

        assertThrows(NotAuthorizedException.class, () -> engine.receiveEvent(target, CREATE));
        // Refused before the first step, so nothing was attempted
        assertNull(this.context.resourceResolver().getResource("/Workflows/myCoolWorkflow"));
    }

    @Test
    void refusesEveryoneWhenTheStartEventNamesNobody() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context, EngineFixture.REQUESTER);
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        EngineFixture.createBootstrapGraph(this.context);

        final WorkflowEngine engine = engine();

        assertThrows(NotAuthorizedException.class, () -> engine.receiveEvent(target, CREATE));
    }

    @Test
    void rejectsTheEventWhenNoSystemWorkflowsExist() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);

        final WorkflowEngine engine = engine();

        assertThrows(NoApplicableWorkflowException.class, () -> engine.receiveEvent(target, CREATE));
    }

    @Test
    void rejectsTheEventWhenTheHomepageIsEmpty() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        this.context.create().resource("/SystemWorkflows", TYPE, "wf/SystemWorkflowsHomepage");

        final WorkflowEngine engine = engine();

        assertThrows(NoApplicableWorkflowException.class, () -> engine.receiveEvent(target, CREATE));
    }

    @Test
    void skipsInactiveDefinitions() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, false, true, WorkflowsHomepage.RESOURCE_TYPE);
        EngineFixture.createBootstrapGraph(this.context);

        final WorkflowEngine engine = engine();

        assertThrows(NoApplicableWorkflowException.class, () -> engine.receiveEvent(target, CREATE));
    }

    @Test
    void skipsInactiveVersions() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, true, false, WorkflowsHomepage.RESOURCE_TYPE);
        EngineFixture.createBootstrapGraph(this.context);

        final WorkflowEngine engine = engine();

        assertThrows(NoApplicableWorkflowException.class, () -> engine.receiveEvent(target, CREATE));
    }

    @Test
    void skipsVersionsDeclaringNoTarget() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, true, true, null);
        EngineFixture.createBootstrapGraph(this.context);

        final WorkflowEngine engine = engine();

        assertThrows(NoApplicableWorkflowException.class, () -> engine.receiveEvent(target, CREATE));
    }

    @Test
    void skipsVersionsDeclaringAnotherTarget() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, true, true, "sub/SubmissionsHomepage");
        EngineFixture.createBootstrapGraph(this.context);

        final WorkflowEngine engine = engine();

        assertThrows(NoApplicableWorkflowException.class, () -> engine.receiveEvent(target, CREATE));
    }

    @Test
    void skipsStartEventsCatchingOtherMessages() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        EngineFixture.createBootstrapGraph(this.context);

        final WorkflowEngine engine = engine();

        assertThrows(NoApplicableWorkflowException.class,
            () -> engine.receiveEvent(target, new WorkflowEvent("destroy", Map.of())));
    }

    @Test
    void rejectsCompetingWorkflows() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        EngineFixture.createBootstrapGraph(this.context);
        // A second definition catching the same event on the same target
        this.context.create().resource("/SystemWorkflows/other", Map.of(
            TYPE, "wf/WorkflowDefinition", "title", "Competitor", "active", true));
        this.context.create().resource("/SystemWorkflows/other/v1", Map.of(
            TYPE, "wf/WorkflowVersion", "version", "1.0", "active", true,
            "targetResourceType", WorkflowsHomepage.RESOURCE_TYPE));
        this.context.create().resource("/SystemWorkflows/other/v1/requested", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, ELEMENT_ID, "requested", "messageName", "create"));

        final WorkflowEngine engine = engine();
        final WorkflowDefinitionException rejection = assertThrows(WorkflowDefinitionException.class,
            () -> engine.receiveEvent(target, CREATE));
        assertTrue(rejection.getMessage().contains("several system workflows"));
    }

    @Test
    void rejectsWaitingNodesInSystemWorkflows() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        // start -> a catching event that would have to wait -> end
        this.context.create().resource(VERSION + "/requested", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, ELEMENT_ID, "requested", "messageName", "create"));
        this.context.create().resource(VERSION + "/requested/toWait", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toWait", "targetRef", "wait"));
        this.context.create().resource(VERSION + "/wait", Map.of(
            TYPE, IntermediateCatchingEvent.RESOURCE_TYPE, ELEMENT_ID, "wait", "catching", true));

        final WorkflowEngine engine = engine();
        final WorkflowDefinitionException rejection = assertThrows(WorkflowDefinitionException.class,
            () -> engine.receiveEvent(target, CREATE));
        assertTrue(rejection.getMessage().contains("cannot wait"));
    }

    @Test
    void rejectsActivitiesWithoutAHandler() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        this.context.create().resource(VERSION + "/requested", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, ELEMENT_ID, "requested", "messageName", "create"));
        this.context.create().resource(VERSION + "/requested/toCreate", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toCreate", "targetRef", "create"));
        this.context.create().resource(VERSION + "/create", Map.of(
            TYPE, Activity.RESOURCE_TYPE, ELEMENT_ID, "create"));

        final WorkflowEngine engine = engine();
        final WorkflowDefinitionException rejection = assertThrows(WorkflowDefinitionException.class,
            () -> engine.receiveEvent(target, CREATE));
        assertTrue(rejection.getMessage().contains("names no handler"));
    }

    @Test
    void rejectsActivitiesNamingAnUnregisteredHandler() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        this.context.create().resource(VERSION + "/requested", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, ELEMENT_ID, "requested", "messageName", "create"));
        this.context.create().resource(VERSION + "/requested/toCreate", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toCreate", "targetRef", "create"));
        this.context.create().resource(VERSION + "/create", Map.of(
            TYPE, Activity.RESOURCE_TYPE, ELEMENT_ID, "create", "handler", "noSuchHandler"));

        final WorkflowEngine engine = engine();
        final WorkflowDefinitionException rejection = assertThrows(WorkflowDefinitionException.class,
            () -> engine.receiveEvent(target, CREATE));
        assertTrue(rejection.getMessage().contains("none is registered"));
    }

    @Test
    void rejectsNodesWithoutExactlyOneWayOut() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        // A start event with no outgoing flows at all
        this.context.create().resource(VERSION + "/requested", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, ELEMENT_ID, "requested", "messageName", "create"));

        final WorkflowEngine engine = engine();
        final WorkflowDefinitionException rejection = assertThrows(WorkflowDefinitionException.class,
            () -> engine.receiveEvent(target, CREATE));
        assertTrue(rejection.getMessage().contains("outgoing sequence flows"));
    }

    @Test
    void rejectsDanglingArcs() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        this.context.create().resource(VERSION + "/requested", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, ELEMENT_ID, "requested", "messageName", "create"));
        this.context.create().resource(VERSION + "/requested/toNowhere", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toNowhere", "targetRef", "nowhere"));

        final WorkflowEngine engine = engine();
        final WorkflowDefinitionException rejection = assertThrows(WorkflowDefinitionException.class,
            () -> engine.receiveEvent(target, CREATE));
        assertTrue(rejection.getMessage().contains("does not exist"));
    }

    @Test
    void rejectsCyclesBackToTheStart() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        // start -> start: on the second visit the start event is no longer a legal place to be
        this.context.create().resource(VERSION + "/requested", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, ELEMENT_ID, "requested", "messageName", "create"));
        this.context.create().resource(VERSION + "/requested/back", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "back", "targetRef", "requested"));

        final WorkflowEngine engine = engine();
        final WorkflowDefinitionException rejection = assertThrows(WorkflowDefinitionException.class,
            () -> engine.receiveEvent(target, CREATE));
        assertTrue(rejection.getMessage().contains("straight-through"));
    }

    @Test
    void rejectsEndlessActivityCycles() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        this.context.create().resource(VERSION + "/requested", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, ELEMENT_ID, "requested", "messageName", "create"));
        this.context.create().resource(VERSION + "/requested/toA", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toA", "targetRef", "a"));
        this.context.create().resource(VERSION + "/a", Map.of(
            TYPE, Activity.RESOURCE_TYPE, ELEMENT_ID, "a", "handler", "noop"));
        this.context.create().resource(VERSION + "/a/toB", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toB", "targetRef", "b"));
        this.context.create().resource(VERSION + "/b", Map.of(
            TYPE, Activity.RESOURCE_TYPE, ELEMENT_ID, "b", "handler", "noop"));
        this.context.create().resource(VERSION + "/b/toA", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "backToA", "targetRef", "a"));

        final WorkflowEngine engine = engine(new NoopHandler());
        final WorkflowDefinitionException rejection = assertThrows(WorkflowDefinitionException.class,
            () -> engine.receiveEvent(target, CREATE));
        assertTrue(rejection.getMessage().contains("cycle"));
    }

    @Test
    void treatsAnAccessDenialAsTheEnginesOwnFailure() throws Exception
    {
        // Not a refusal of the caller: they were already admitted by the definition, so a repository that says no
        // to the engine is a deployment whose service user is short of rights
        final WorkflowFailedException failure = assertThrows(WorkflowFailedException.class,
            () -> runWithFailingCommit(new PersistenceException("save failed",
                new AccessDeniedException("no add_node permission"))));
        assertTrue(failure.getMessage().contains("service user is missing rights"));
    }

    @Test
    void treatsALostRaceAsSomethingToLookAtAgain() throws Exception
    {
        // Two people acting on the same thing at once is not a fault in either request: the state simply moved
        // under the slower one, which is the same layer as "nothing here is waiting for this"
        final NoApplicableWorkflowException refusal = assertThrows(NoApplicableWorkflowException.class,
            () -> runWithFailingCommit(new PersistenceException("save failed",
                new InvalidItemStateException("this node has been modified"))));
        assertTrue(refusal.getMessage().contains("at the same time"));
    }

    @Test
    void translatesAConstraintViolationIntoInvalidPayload() throws Exception
    {
        assertThrows(InvalidPayloadException.class,
            () -> runWithFailingCommit(new PersistenceException("save failed",
                new ConstraintViolationException("mandatory title missing"))));
    }

    @Test
    void translatesOtherPersistenceFailuresIntoWorkflowFailed() throws Exception
    {
        assertThrows(WorkflowFailedException.class,
            () -> runWithFailingCommit(new PersistenceException("the disk is on fire")));
    }

    @Test
    void failsCleanlyWithoutItsServiceUser() throws Exception
    {
        final ResourceResolverFactory brokenFactory = Mockito.mock(ResourceResolverFactory.class);
        Mockito.when(brokenFactory.getServiceResourceResolver(Mockito.anyMap()))
            .thenThrow(new LoginException("no such service user"));
        final WorkflowEngineImpl engine = new WorkflowEngineImpl();
        inject(engine, "resolverFactory", brokenFactory);
        final Resource target = EngineFixture.createTarget(this.context);

        assertThrows(WorkflowFailedException.class, () -> engine.receiveEvent(target, CREATE));
        assertThrows(WorkflowFailedException.class, () -> engine.getAvailableEvents(target));
        assertThrows(WorkflowFailedException.class, () -> engine.findApplicableWorkflow(target, CREATE.getName()));
    }

    /**
     * Runs the happy-path bootstrap with an engine whose commit fails, so that the tests can observe how the
     * failure is translated. The write itself succeeds and only the commit fails, and nothing may reach the
     * repository.
     *
     * @param failure what the commit throws
     * @throws WorkflowException the translated failure, for the caller to assert on
     */
    private void runWithFailingCommit(final PersistenceException failure) throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        EngineFixture.createBootstrapGraph(this.context);

        final WorkflowEngine engine = engine(failure);
        try {
            engine.receiveEvent(target, CREATE);
        } finally {
            // Whatever the failure, an aborted execution must leave nothing behind: the engine reverts its own
            // session, and a session it never committed cannot have reached the repository anyway
            assertNull(this.context.resourceResolver().getResource("/Workflows/myCoolWorkflow"));
        }
    }

    @Test
    void startsAGuardedWorkflowWhenItsGuardHolds() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        tagTarget("open");
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        EngineFixture.createBootstrapGraph(this.context);
        guard(VERSION + "/requested", "open");

        final WorkflowResult result = engine().receiveEvent(target, CREATE);

        assertEquals("/Workflows/myCoolWorkflow", result.getVariable(WorkflowResult.CREATED_PATH_VARIABLE));
    }

    @Test
    void rejectsTheEventWhenTheGuardDoesNotHold() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        tagTarget("open");
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        EngineFixture.createBootstrapGraph(this.context);
        guard(VERSION + "/requested", "closed");

        final WorkflowEngine engine = engine();

        assertThrows(NoApplicableWorkflowException.class, () -> engine.receiveEvent(target, CREATE));
        assertNull(this.context.resourceResolver().getResource("/Workflows/myCoolWorkflow"));
    }

    @Test
    void startsTheWorkflowWhoseGuardHolds() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        tagTarget("open");
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        EngineFixture.createBootstrapGraph(this.context);
        guard(VERSION + "/requested", "closed");
        createOtherNoopWorkflow("create");
        guard(OTHER_VERSION + "/requested", "open");
        final ActorRecordingHandler handler = new ActorRecordingHandler();

        engine(handler).receiveEvent(target, CREATE);

        assertEquals(EngineFixture.ADMIN, handler.seen);
        assertNull(this.context.resourceResolver().getResource("/Workflows/myCoolWorkflow"));
    }

    @Test
    void rejectsSeveralWorkflowsWhoseGuardsHold() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        tagTarget("open");
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        EngineFixture.createBootstrapGraph(this.context);
        guard(VERSION + "/requested", "open");
        createOtherNoopWorkflow("create");

        final WorkflowEngine engine = engine(new NoopHandler());

        assertThrows(WorkflowDefinitionException.class, () -> engine.receiveEvent(target, CREATE));
    }

    @Test
    void offersTheEventsWhoseDefinitionAdmitsTheActor() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context, EngineFixture.REQUESTER);
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        EngineFixture.createBootstrapGraph(this.context, EngineFixture.REQUESTERS);
        createOtherNoopWorkflow("archive");

        assertEquals(Set.of("create"), engine().getAvailableEvents(target));
    }

    @Test
    void offersEveryWaitingEventInAlphabeticalOrder() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        EngineFixture.createBootstrapGraph(this.context);
        createOtherNoopWorkflow("archive");

        assertEquals(List.of("archive", "create"), List.copyOf(engine().getAvailableEvents(target)));
    }

    @Test
    void offersOnlyTheEventsWhoseGuardHolds() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        tagTarget("open");
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        EngineFixture.createBootstrapGraph(this.context);
        guard(VERSION + "/requested", "closed");
        createOtherNoopWorkflow("archive");
        guard(OTHER_VERSION + "/requested", "open");

        assertEquals(Set.of("archive"), engine().getAvailableEvents(target));
    }

    @Test
    void ignoresStartEventsCatchingNoMessage() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        EngineFixture.createBootstrapGraph(this.context);
        this.context.create().resource(VERSION + "/plain", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, ELEMENT_ID, "plain"));

        assertEquals(Set.of("create"), engine().getAvailableEvents(target));
    }

    @Test
    void holdsNoGuardOnATargetThatIsNotContent() throws Exception
    {
        final Resource plain = this.context.create().resource("/plain", TYPE, "test/Plain");
        final ResourceResolver admin = EngineFixture.actingAs(plain.getResourceResolver(), EngineFixture.ADMIN);
        final Resource target = new ResourceWrapper(plain)
        {
            @Override
            public ResourceResolver getResourceResolver()
            {
                return admin;
            }
        };
        EngineFixture.createSystemWorkflow(this.context, true, true, "test/Plain");
        EngineFixture.createBootstrapGraph(this.context);
        final WorkflowEngine engine = engine();
        assertEquals(Set.of("create"), engine.getAvailableEvents(target));

        guard(VERSION + "/requested", "open");
        this.context.resourceResolver().commit();

        assertEquals(Set.of(), engine.getAvailableEvents(target));
    }

    @Test
    void refusesToListAnEventWorkflowsCompeteFor() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        EngineFixture.createBootstrapGraph(this.context);
        createOtherNoopWorkflow(CREATE.getName());
        final WorkflowEngine engine = engine();

        // Receiving the event would refuse it as contradictory definitions, so offering it would be a lie
        assertThrows(WorkflowDefinitionException.class, () -> engine.getAvailableEvents(target));
    }

    @Test
    void offersNothingWhenNoSystemWorkflowsExist() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);

        assertEquals(Set.of(), engine().getAvailableEvents(target));
    }

    @Test
    void findsTheWorkflowThatWouldHandleAnEvent() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        EngineFixture.createBootstrapGraph(this.context);

        final WorkflowVersion version = engine().findApplicableWorkflow(target, CREATE.getName());

        assertEquals(VERSION, version.getPath());
        assertEquals(List.of(VERSION + "/requested"),
            version.getStartEvents().stream().map(StartEvent::getPath).toList());
    }

    @Test
    void handsTheWorkflowOverThroughTheAskingUsersOwnSession() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        EngineFixture.createBootstrapGraph(this.context);
        // What a session sees without the grant that lets everyone read the system workflows
        final ResourceResolver blind = new ResourceResolverWrapper(target.getResourceResolver())
        {
            @Override
            public Resource getResource(final String path)
            {
                return path.startsWith(SystemWorkflowsHomepage.PATH) ? null : super.getResource(path);
            }
        };
        final Resource unseen = new ResourceWrapper(target)
        {
            @Override
            public ResourceResolver getResourceResolver()
            {
                return blind;
            }
        };
        final WorkflowEngine engine = engine();

        assertNotNull(engine.findApplicableWorkflow(target, CREATE.getName()));
        // The engine's own session reads the workflow either way, so only handing it over can fail here
        assertThrows(WorkflowFailedException.class, () -> engine.findApplicableWorkflow(unseen, CREATE.getName()));
    }

    @Test
    void findsNothingWhenNoWorkflowWouldTakeTheEventFromTheUser() throws Exception
    {
        final Resource requester = EngineFixture.createTarget(this.context, EngineFixture.REQUESTER);
        tagTarget("open");
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        EngineFixture.createBootstrapGraph(this.context, "some-other-group");
        createOtherNoopWorkflow("archive");
        guard(OTHER_VERSION + "/requested", "closed");
        final WorkflowEngine engine = engine();

        // Not a performer, a guard that does not hold, and nothing waiting at all
        assertNull(engine.findApplicableWorkflow(requester, CREATE.getName()));
        assertNull(engine.findApplicableWorkflow(requester, "archive"));
        assertNull(engine.findApplicableWorkflow(requester, "unknown"));
    }

    @Test
    void refusesToChooseBetweenWorkflowsCompetingForAnEvent() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        EngineFixture.createBootstrapGraph(this.context);
        createOtherNoopWorkflow(CREATE.getName());
        final WorkflowEngine engine = engine();

        // Receiving the event would refuse it as contradictory definitions, and so does asking which would run
        assertThrows(WorkflowDefinitionException.class,
            () -> engine.findApplicableWorkflow(target, CREATE.getName()));
    }

    @Test
    void answersEverythingAskedThroughOneResolverFromOneSession() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        EngineFixture.createBootstrapGraph(this.context);
        final List<OpenedSession> opened = new ArrayList<>();
        final WorkflowEngine engine = recording(opened);

        engine.getAvailableEvents(target);
        engine.getAvailableEvents(target);
        engine.findApplicableWorkflow(target, CREATE.getName());

        assertEquals(1, opened.size());
    }

    @Test
    void closesItsSessionWithTheResolverAskedThrough() throws Exception
    {
        final Resource homepage = EngineFixture.createTarget(this.context);
        final List<OpenedSession> opened = new ArrayList<>();
        final WorkflowEngine engine = recording(opened);
        final ResourceResolver asking = EngineFixture.actingAs(
            this.context.getService(ResourceResolverFactory.class).getResourceResolver(null), EngineFixture.ADMIN);
        final Resource target = new ResourceWrapper(asking.getResource(homepage.getPath()))
        {
            @Override
            public ResourceResolver getResourceResolver()
            {
                return asking;
            }
        };

        engine.getAvailableEvents(target);
        assertFalse(opened.get(0).closed);
        asking.close();

        assertTrue(opened.get(0).closed);
    }

    @Test
    void keepsASessionForEachUserAskingThroughOneResolver() throws Exception
    {
        final Resource administrator = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, true, true, WorkflowsHomepage.RESOURCE_TYPE);
        EngineFixture.createBootstrapGraph(this.context, "some-other-group");
        final ResourceResolver asRequester =
            EngineFixture.actingAs(this.context.resourceResolver(), EngineFixture.REQUESTER);
        final Resource requester = new ResourceWrapper(administrator)
        {
            @Override
            public ResourceResolver getResourceResolver()
            {
                return asRequester;
            }
        };
        final WorkflowEngine engine = engine();

        // Both wrap the test's own resolver and share its property map, so only the user tells them apart
        assertEquals(Set.of(), engine.getAvailableEvents(requester));
        assertEquals(Set.of(CREATE.getName()), engine.getAvailableEvents(administrator));
    }

    /**
     * Places tags on the {@code /Workflows} target, which is what the tests' guards look at.
     *
     * @param tags the tags to place
     */
    private void tagTarget(final String... tags)
    {
        this.context.resourceResolver().getResource("/Workflows").adaptTo(ModifiableValueMap.class)
            .put("tags", tags);
    }

    /**
     * Guards a start event on the target carrying a tag.
     *
     * @param start the start event's path
     * @param tag the tag the target must carry
     */
    private void guard(final String start, final String tag)
    {
        this.context.create().resource(start + "/cond:condition", Map.of(
            TYPE, SingleCondition.RESOURCE_TYPE, "comparator", "includes"));
        this.context.create().resource(start + "/cond:condition/operandA", Map.of(
            TYPE, ConditionOperand.RESOURCE_TYPE, "source", "tags"));
        this.context.create().resource(start + "/cond:condition/operandB", Map.of(
            TYPE, ConditionOperand.RESOURCE_TYPE, "value", new String[] { tag }));
    }

    /**
     * Creates a second active system workflow on the same homepage, admitting only administrators and running
     * the {@code noop} handler.
     *
     * @param message the event it catches
     */
    private void createOtherNoopWorkflow(final String message)
    {
        this.context.create().resource("/SystemWorkflows/otherWorkflow", Map.of(
            TYPE, "wf/WorkflowDefinition", "title", "Another", "active", true));
        this.context.create().resource(OTHER_VERSION, Map.of(
            TYPE, "wf/WorkflowVersion", "version", "1.0", "active", true,
            "targetResourceType", WorkflowsHomepage.RESOURCE_TYPE));
        this.context.create().resource(OTHER_VERSION + "/requested", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, ELEMENT_ID, "requested", "messageName", message,
            "performers", new String[] { EngineFixture.ADMIN }));
        this.context.create().resource(OTHER_VERSION + "/requested/toNoop", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toNoop", "targetRef", "noop"));
        this.context.create().resource(OTHER_VERSION + "/noop", Map.of(
            TYPE, Activity.RESOURCE_TYPE, ELEMENT_ID, "noop", "handler", "noop"));
        this.context.create().resource(OTHER_VERSION + "/noop/toDone", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toDone", "targetRef", "done"));
        this.context.create().resource(OTHER_VERSION + "/done", Map.of(
            TYPE, EndEvent.RESOURCE_TYPE, ELEMENT_ID, "done"));
    }

    /**
     * Builds a straight-through graph whose single activity does nothing: start, a {@code noop} service task, end.
     *
     * @param performers the principals the start event admits
     */
    private void createNoopGraph(final String... performers)
    {
        this.context.create().resource(VERSION + "/requested", Map.of(
            TYPE, StartEvent.RESOURCE_TYPE, ELEMENT_ID, "requested", "messageName", "create",
            "performers", performers));
        this.context.create().resource(VERSION + "/requested/toNoop", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toNoop", "targetRef", "noop"));
        this.context.create().resource(VERSION + "/noop", Map.of(
            TYPE, Activity.RESOURCE_TYPE, ELEMENT_ID, "noop", "handler", "noop"));
        this.context.create().resource(VERSION + "/noop/toDone", Map.of(
            TYPE, SequenceFlow.RESOURCE_TYPE, ELEMENT_ID, "toDone", "targetRef", "done"));
        this.context.create().resource(VERSION + "/done", Map.of(
            TYPE, EndEvent.RESOURCE_TYPE, ELEMENT_ID, "done"));
    }

    /**
     * A handler that records who the execution said it was acting for.
     */
    private static final class ActorRecordingHandler implements ServiceTaskHandler
    {
        private String seen;

        @Override
        public String getName()
        {
            return "noop";
        }

        @Override
        public void execute(final WorkflowTaskContext taskContext)
        {
            this.seen = taskContext.getActor();
        }
    }

    /**
     * A handler that does nothing, for graphs whose shape is under test rather than their work.
     */
    private static final class NoopHandler implements ServiceTaskHandler
    {
        @Override
        public String getName()
        {
            return "noop";
        }

        @Override
        public void execute(final WorkflowTaskContext taskContext)
        {
            // Nothing to do
        }
    }

    /**
     * A service session handed to the engine, remembering whether the engine closed it. The mock resolver always
     * reports itself live, so closing has to be watched for rather than asked about.
     */
    private static final class OpenedSession extends ResourceResolverWrapper
    {
        private boolean closed;

        OpenedSession(final ResourceResolver resolver)
        {
            super(resolver);
        }

        @Override
        public void close()
        {
            this.closed = true;
            super.close();
        }
    }
}

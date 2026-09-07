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

import javax.jcr.AccessDeniedException;
import javax.jcr.InvalidItemStateException;
import javax.jcr.nodetype.ConstraintViolationException;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

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
import io.uhndata.iap.workflows.models.WorkflowFixture;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static io.uhndata.iap.workflows.internal.EngineFixture.VERSION;
import static io.uhndata.iap.workflows.models.WorkflowFixture.ACTIVE;
import static io.uhndata.iap.workflows.models.WorkflowFixture.STATE;
import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static final WorkflowEvent CREATE =
        new WorkflowEvent("create", Map.of("title", "My cool workflow"));

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
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
     * repository failures. The writes themselves succeed — only the commit fails.
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
        inject(impl, "conditions", EngineFixture.conditions());
        inject(impl, "principals", EngineFixture.principals());
        return impl;
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
        EngineFixture.createSystemWorkflow(this.context, "wf/WorkflowsHomepage");
        EngineFixture.createBootstrapGraph(this.context);

        final WorkflowResult result = engine().receiveEvent(target, CREATE);

        assertEquals("/Workflows/myCoolWorkflow", result.getVariable(WorkflowResult.CREATED_PATH));
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
        EngineFixture.createSystemWorkflow(this.context, "wf/WorkflowsHomepage");
        EngineFixture.createBootstrapGraph(this.context, EngineFixture.REQUESTERS);

        final WorkflowResult result = engine().receiveEvent(target, CREATE);

        assertEquals("/Workflows/myCoolWorkflow", result.getVariable(WorkflowResult.CREATED_PATH));
        // Written by the engine on behalf of a user who holds no rights at all on /Workflows
        final Resource created = this.context.resourceResolver().getResource("/Workflows/myCoolWorkflow");
        assertNotNull(created);
        assertEquals(EngineFixture.REQUESTER, created.getValueMap().get("createdBy"));
    }

    @Test
    void tellsTheHandlerWhoItIsActingFor() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context, EngineFixture.REQUESTER);
        EngineFixture.createSystemWorkflow(this.context, "wf/WorkflowsHomepage");
        createNoopGraph(EngineFixture.REQUESTERS);
        final ActorRecordingHandler handler = new ActorRecordingHandler();

        final WorkflowResult result = engine(handler).receiveEvent(target, CREATE);

        // The handler is privileged, so knowing the actor is the only way it can act on their behalf
        assertEquals(EngineFixture.REQUESTER, handler.seen);
        // A workflow that creates nothing completes all the same: there is simply nowhere to send the caller
        assertNull(result.getVariable(WorkflowResult.CREATED_PATH));
    }

    @Test
    void refusesAnActorTheStartEventDoesNotName() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context, EngineFixture.REQUESTER);
        EngineFixture.createSystemWorkflow(this.context, "wf/WorkflowsHomepage");
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
        EngineFixture.createSystemWorkflow(this.context, "wf/WorkflowsHomepage");
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
    void skipsDraftVersions() throws Exception
    {
        assertNothingIsStartedFrom(WorkflowVersion.State.DRAFT);
    }

    @Test
    void skipsTrialVersions() throws Exception
    {
        assertNothingIsStartedFrom(WorkflowVersion.State.TRIAL);
    }

    @Test
    void skipsRetiredVersions() throws Exception
    {
        assertNothingIsStartedFrom(WorkflowVersion.State.RETIRED);
    }

    /**
     * Asserts that a system workflow whose only version is in the given state catches nothing: ACTIVE is the one
     * state new instances are created from, and a definition is instantiable only through such a version, so this
     * covers the definition being unusable as well.
     *
     * @param state the lifecycle state to put the version in
     * @throws Exception if the fixture or the engine fails unexpectedly
     */
    private void assertNothingIsStartedFrom(final WorkflowVersion.State state) throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, state, "wf/WorkflowsHomepage");
        EngineFixture.createBootstrapGraph(this.context);

        final WorkflowEngine engine = engine();

        assertThrows(NoApplicableWorkflowException.class, () -> engine.receiveEvent(target, CREATE));
    }

    @Test
    void skipsVersionsDeclaringNoTarget() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, (String) null);
        EngineFixture.createBootstrapGraph(this.context);

        final WorkflowEngine engine = engine();

        assertThrows(NoApplicableWorkflowException.class, () -> engine.receiveEvent(target, CREATE));
    }

    @Test
    void skipsVersionsDeclaringAnotherTarget() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, "sub/SubmissionsHomepage");
        EngineFixture.createBootstrapGraph(this.context);

        final WorkflowEngine engine = engine();

        assertThrows(NoApplicableWorkflowException.class, () -> engine.receiveEvent(target, CREATE));
    }

    @Test
    void skipsStartEventsCatchingOtherMessages() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, "wf/WorkflowsHomepage");
        EngineFixture.createBootstrapGraph(this.context);

        final WorkflowEngine engine = engine();

        assertThrows(NoApplicableWorkflowException.class,
            () -> engine.receiveEvent(target, new WorkflowEvent("destroy", Map.of())));
    }

    @Test
    void rejectsCompetingWorkflows() throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, "wf/WorkflowsHomepage");
        EngineFixture.createBootstrapGraph(this.context);
        // A second definition catching the same event on the same target
        this.context.create().resource("/SystemWorkflows/other", Map.of(
            TYPE, "wf/WorkflowDefinition", "title", "Competitor"));
        this.context.create().resource("/SystemWorkflows/other/v1", Map.of(
            TYPE, "wf/WorkflowVersion", "version", "1.0", STATE, ACTIVE,
            "targetResourceType", "wf/WorkflowsHomepage"));
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
        EngineFixture.createSystemWorkflow(this.context, "wf/WorkflowsHomepage");
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
        EngineFixture.createSystemWorkflow(this.context, "wf/WorkflowsHomepage");
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
        EngineFixture.createSystemWorkflow(this.context, "wf/WorkflowsHomepage");
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
        EngineFixture.createSystemWorkflow(this.context, "wf/WorkflowsHomepage");
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
        EngineFixture.createSystemWorkflow(this.context, "wf/WorkflowsHomepage");
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
        EngineFixture.createSystemWorkflow(this.context, "wf/WorkflowsHomepage");
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
        EngineFixture.createSystemWorkflow(this.context, "wf/WorkflowsHomepage");
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
    }

    /**
     * Runs the happy-path bootstrap with an engine whose commit fails, so that the tests can observe how the
     * failure is translated. The write itself succeeds — only the commit fails — and nothing may reach the
     * repository.
     *
     * @param failure what the commit throws
     * @throws WorkflowException the translated failure, for the caller to assert on
     */
    private void runWithFailingCommit(final PersistenceException failure) throws Exception
    {
        final Resource target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, "wf/WorkflowsHomepage");
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
     * A handler that does nothing, for graphs whose shape — not work — is under test.
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
            // Deliberately nothing
        }
    }
}

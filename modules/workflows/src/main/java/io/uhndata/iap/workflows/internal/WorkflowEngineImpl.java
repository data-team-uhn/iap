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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.jcr.Session;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.history.api.HistoryRecorder;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.StartEvent;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;

/**
 * The engine, and the one door a domain event comes in through. An event either runs the system workflow that
 * matches it, or carries on the instance whose task it was aimed at.
 *
 * <p>A system workflow runs straight through, inside the request, and leaves no instance behind.
 * {@link InstanceRunner} owns the other half, where a workflow persists and waits. Both perform their service
 * tasks through {@link ServiceTaskDispatcher}.</p>
 *
 * <p>Everything happens through the engine's own service user. Matching has to see all of a target's content,
 * whatever the user may read of it, and execution writes content they hold no rights on. Authorization cannot be
 * left to the repository. It is decided here, before the first step, by asking the start event which principals it
 * admits. What a user may do is what the workflows say, not what an access control list on the data happens to
 * allow.</p>
 *
 * <p>Everything a run changes lands in a single commit at quiescence. An event either fully happened, or did not
 * happen at all.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = WorkflowEngine.class)
// Temporary suppression while we wait on IAP-88 to refactor this class, as we neither want to make this class
// difficult to merge later, but need to add Delegation
@SuppressWarnings("checkstyle:ClassFanOutComplexity")
public class WorkflowEngineImpl implements WorkflowEngine
{
    /** The subservice name under which the engine's service user is mapped. */
    static final String SUBSERVICE_NAME = "workflows";

    /**
     * How many nodes one delivery may pass through, counted across all its branches, before the definition is
     * declared broken. Far above anything a real workflow needs, so a definition whose arcs form a cycle fails fast
     * instead of spinning. A system workflow's run and a user workflow's walk share it.
     */
    static final int MAX_STEPS = 200;

    /** How many times an event aimed at a task is run again after another walk of the same instance won a race. */
    private static final int CONFLICT_RETRIES = 2;

    /** How many events deep workflows may send events to each other before the definitions are declared broken. */
    private static final int MAX_SENT_EVENTS_DEPTH = 10;

    @Reference
    private ResourceResolverFactory resolverFactory;

    /** Evaluates every guard, on a start event and on a gateway's arcs alike. */
    @Reference
    private ConditionEvaluator conditionEvaluator;

    /**
     * Moves a system workflow's run from node to node. The engine drives user workflows too, through the runners it
     * is handed, which carry their own routing.
     */
    @Reference
    private FlowRouting routing;

    /** Ends the user tasks an event is aimed at, and carries their instances on. */
    @Reference
    private TaskCompletion taskCompletion;

    /** Says who the names in a definition stand for: special names, users, and groups however they are stored. */
    @Reference
    private Performers performers;

    /** Hands out the runner that carries a user workflow on. */
    @Reference
    private InstanceRunners runners;

    /** Answers what a user could do without changing anything. */
    @Reference
    private WorkflowQueries queries;

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    private volatile List<ServiceTaskHandler> handlers;

    @Reference
    private Delegation delegation;

    @Reference
    private HistoryRecorder recorder;

    @Override
    public WorkflowResult receiveEvent(final Resource target, final WorkflowEvent event, final String onBehalfOf)
        throws WorkflowException
    {
        // Taken from the incoming resource rather than passed in. The session that resolved it is the one that
        // authenticated, so it is the authority on who is asking
        final ResourceResolver senderResolver = target.getResourceResolver();
        try (ResourceResolver serviceResolver = serviceResolver()) {
            final Actor actor = this.delegation.actor(serviceResolver, senderResolver, onBehalfOf);
            // Re-resolved through the engine's session. From here on the run is privileged: the caller's own
            // view of the target may be nothing but the bare node they were allowed to post to
            final Resource privilegedTarget = Objects.requireNonNull(serviceResolver.getResource(target.getPath()),
                "A target the caller could reach is always visible to the engine");
            if (privilegedTarget.isResourceType(TaskInstance.RESOURCE_TYPE)) {
                return resume(privilegedTarget, event, actor);
            }
            final StartEvent start =
                SystemWorkflowLocator.find(serviceResolver, privilegedTarget, event, this.conditionEvaluator);
            this.performers.createCheck(serviceResolver, actor.effectiveUser()).verify(start, privilegedTarget);
            return execute(privilegedTarget, event, start, actor);
        }
    }

    @Override
    public Set<String> getAvailableEvents(final Resource target) throws WorkflowException
    {
        return this.queries.availableEvents(target, this::serviceResolver);
    }

    @Override
    public WorkflowVersion findApplicableWorkflow(final Resource target, final String event) throws WorkflowException
    {
        return this.queries.applicableWorkflow(target, event, this::serviceResolver);
    }

    /**
     * Opens the engine's own session.
     *
     * @return a service resource resolver, to be closed by the caller
     * @throws WorkflowFailedException when the service user is not available
     */
    private ResourceResolver serviceResolver() throws WorkflowFailedException
    {
        try {
            return this.resolverFactory
                .getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, SUBSERVICE_NAME));
        } catch (final LoginException e) {
            throw new WorkflowFailedException("The workflow engine's service user is not available", e);
        }
    }

    /**
     * Carries a running instance on from the user task the event was aimed at. The same one-commit rule as a
     * system workflow: the decision, the task's new state and what follows from it all happen, or none of them
     * do.
     *
     * <p>Two walks of one instance at once conflict when the second commits. The loser is reverted, which also
     * brings its session up to date, and run again on top of what the winner did.</p>
     *
     * @param task the task the event is aimed at, resolved through the engine's session
     * @param event the incoming event
     * @param actor the user who sent the event
     * @return an empty result: ending a task creates nothing to send the caller to
     * @throws WorkflowException when the run cannot complete, typed by whose fault that is
     */
    private WorkflowResult resume(final Resource task, final WorkflowEvent event, final Actor actor)
        throws WorkflowException
    {
        final ResourceResolver resolver = task.getResourceResolver();
        return RepositoryFailures.retryingConflicts(CONFLICT_RETRIES, () -> {
            try {
                final ActionRecord record = ActionRecord.of(actor, event);
                this.taskCompletion.apply(resolver, Objects.requireNonNull(resolver.getResource(task.getPath()),
                    "A task is never deleted while its instance runs"), event, actor,
                    dispatcher(record).performer(event, actor), record);
                record.write(session(resolver), this.recorder);
                resolver.commit();
                return new WorkflowResult(Map.of());
            } catch (final PersistenceException e) {
                revert(resolver);
                throw RepositoryFailures.translate(e);
            } catch (final WorkflowException | RuntimeException e) {
                revert(resolver);
                throw e;
            }
        });
    }

    /**
     * Runs the matched workflow and commits everything it changed at once. A failing event leaves no trace.
     *
     * @param target the resource the event is aimed at, backed by the engine's own session
     * @param event the incoming event
     * @param start the entry point to run from
     * @param actor the user who fired the event
     * @return the variables the execution left behind
     * @throws WorkflowException when the run cannot complete, typed by whose fault that is
     */
    private WorkflowResult execute(final Resource target, final WorkflowEvent event, final StartEvent start,
        final Actor actor) throws WorkflowException
    {
        final ResourceResolver resolver = target.getResourceResolver();
        try {
            final ActionRecord record = ActionRecord.of(actor, event);
            final Map<String, Object> variables = run(target, event, start, actor, 0, record);
            record.write(session(resolver), this.recorder);
            resolver.commit();
            return new WorkflowResult(variables);
        } catch (final PersistenceException e) {
            revert(resolver);
            throw RepositoryFailures.translate(e);
        } catch (final WorkflowException | RuntimeException e) {
            revert(resolver);
            throw e;
        }
    }

    /**
     * Runs a system workflow without committing, gathering what it did for history.
     *
     * @param target the resource the event is aimed at, backed by the engine's own session
     * @param event the incoming event
     * @param start the entry point to run from
     * @param actor the user who fired the event
     * @param depth how many sent events deep this run is, 0 for the event that came in
     * @param record where what the run did is gathered for history
     * @return the variables the run left behind
     * @throws WorkflowException when the run cannot complete, typed by whose fault that is
     * @throws PersistenceException when a write fails
     */
    private Map<String, Object> run(final Resource target, final WorkflowEvent event, final StartEvent start,
        final Actor actor, final int depth, final ActionRecord record) throws WorkflowException, PersistenceException
    {
        record.workflow(null, start.getWorkflowVersion());
        // Before any service task runs: one may delete the target in a session of its own, leaving this one stale
        record.affecting(target, "target");
        final Map<String, Object> variables =
            dispatcher(record).runSystemWorkflow(target, event, start, actor, depth);
        final Object created = variables.get(WorkflowResult.CREATED_PATH_VARIABLE);
        if (created instanceof String) {
            record.affecting(Objects.requireNonNull(target.getResourceResolver().getResource((String) created),
                "A handler reported creating something that is not there"), "created");
        }
        return variables;
    }

    /**
     * Runs the system workflow waiting for an event another workflow sent, as part of the sender's execution:
     * matched, guarded and authorized like any incoming event, for the same user.
     *
     * @param target the resource the event is sent to
     * @param event the sent event
     * @param actor the user the sending execution acts for
     * @param depth how many sent events deep this is
     * @param sender the record of the execution that sent the event
     * @throws WorkflowException when the event is refused, the workflow fails, or events are sent too deep
     * @throws PersistenceException when a write fails
     */
    private void chain(final Resource target, final WorkflowEvent event, final Actor actor, final int depth,
        final ActionRecord sender) throws WorkflowException, PersistenceException
    {
        if (depth > MAX_SENT_EVENTS_DEPTH) {
            throw new WorkflowDefinitionException("Workflows sent events more than " + MAX_SENT_EVENTS_DEPTH
                + " deep, the last one " + event.getName() + " to " + target.getPath()
                + "; their definitions probably send events to each other in a loop");
        }
        final ResourceResolver resolver = target.getResourceResolver();
        final StartEvent start = SystemWorkflowLocator.find(resolver, target, event, this.conditionEvaluator);
        this.performers.createCheck(resolver, actor.effectiveUser()).verify(start, target);
        run(target, event, start, actor, depth, sender.send(actor, event));
    }

    /**
     * How a run performs its service tasks: through the handlers registered as it begins, and this engine for the
     * workflow an event sent from one of them is waiting for.
     *
     * @param record the record of the run, which the actions of sent events are part of
     * @return a dispatcher for one run
     */
    private ServiceTaskDispatcher dispatcher(final ActionRecord record)
    {
        return new ServiceTaskDispatcher(this.handlers,
            (to, sent, actor, depth) -> chain(to, sent, actor, depth, record), this.routing, this.runners);
    }

    /**
     * The JCR session behind the engine's resolver, which history is written through.
     *
     * @param resolver the engine's own resolver
     * @return its session
     */
    private static Session session(final ResourceResolver resolver)
    {
        return Objects.requireNonNull(resolver.adaptTo(Session.class), "The engine's resolver is always a JCR one");
    }

    /**
     * Discards whatever an aborted execution had already changed. A failing event leaves no trace in the engine's
     * session.
     *
     * @param resolver the engine's own resource resolver
     */
    private void revert(final ResourceResolver resolver)
    {
        if (resolver.hasChanges()) {
            resolver.revert();
        }
    }
}

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
import io.uhndata.iap.utils.UserIds;
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

    @Override
    public WorkflowResult receiveEvent(final Resource target, final WorkflowEvent event) throws WorkflowException
    {
        // Taken from the incoming resource rather than passed in. The session that resolved it is the one that
        // authenticated, so it is the authority on who is asking. Canonical, because a login resolves
        // case-insensitively and the resolver reports the spelling that was typed: @creator compares against what
        // is recorded here, and would otherwise refuse the person who raised the request
        final Actor actor = Actor.of(UserIds.canonical(target.getResourceResolver()));
        try (ResourceResolver serviceResolver = serviceResolver()) {
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
                this.taskCompletion.apply(resolver, Objects.requireNonNull(resolver.getResource(task.getPath()),
                    "A task is never deleted while its instance runs"), event, actor,
                    dispatcher().performer(event, actor));
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
            final Map<String, Object> variables = dispatcher().runSystemWorkflow(target, event, start, actor, 0);
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
     * Runs the system workflow waiting for an event another workflow sent, as part of the sender's execution:
     * matched, guarded and authorized like any incoming event, for the same user.
     *
     * @param target the resource the event is sent to
     * @param event the sent event
     * @param actor the user the sending execution acts for
     * @param depth how many sent events deep this is
     * @throws WorkflowException when the event is refused, the workflow fails, or events are sent too deep
     * @throws PersistenceException when a write fails
     */
    private void chain(final Resource target, final WorkflowEvent event, final Actor actor, final int depth)
        throws WorkflowException, PersistenceException
    {
        if (depth > MAX_SENT_EVENTS_DEPTH) {
            throw new WorkflowDefinitionException("Workflows sent events more than " + MAX_SENT_EVENTS_DEPTH
                + " deep, the last one " + event.getName() + " to " + target.getPath()
                + "; their definitions probably send events to each other in a loop");
        }
        final ResourceResolver resolver = target.getResourceResolver();
        final StartEvent start = SystemWorkflowLocator.find(resolver, target, event, this.conditionEvaluator);
        this.performers.createCheck(resolver, actor.effectiveUser()).verify(start, target);
        dispatcher().runSystemWorkflow(target, event, start, actor, depth);
    }

    /**
     * How a run performs its service tasks: through the handlers registered as it begins, and this engine for the
     * workflow an event sent from one of them is waiting for.
     *
     * @return a dispatcher for one run
     */
    private ServiceTaskDispatcher dispatcher()
    {
        return new ServiceTaskDispatcher(this.handlers, this::chain, this.routing, this.runners);
    }

    /**
     * Discards whatever an aborted execution had already changed. A failing event leaves no trace in the firing
     * user's session.
     *
     * @param resolver the firing user's resource resolver
     */
    private void revert(final ResourceResolver resolver)
    {
        if (resolver.hasChanges()) {
            resolver.revert();
        }
    }
}

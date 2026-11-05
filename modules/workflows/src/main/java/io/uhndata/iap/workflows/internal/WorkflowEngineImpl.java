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

import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.StartEvent;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowVersion;

/**
 * The engine, and the one door a domain event comes in through. An event either runs the system workflow that
 * matches it, or carries on the instance whose task it was aimed at.
 *
 * <p>A system workflow runs straight through, inside the request, and leaves no instance behind:
 * {@link SystemWorkflowRunner} runs it. {@link InstanceRunner} owns the other half, where a workflow persists and
 * waits. Both perform their service tasks through {@link ServiceTaskDispatcher}.</p>
 *
 * <p>Everything happens through the engine's own service user. Matching has to see all of a target's content,
 * whatever the user may read of it, and execution writes content they hold no rights on. Authorization cannot be
 * left to the repository. It is decided here, before the first step, by asking the start event which principals it
 * admits. What a user may do is what the workflows say, not what an access control list on the data happens to
 * allow.</p>
 *
 * <p>Everything a run changes lands in a single commit at quiescence. An event either fully happened, or did not
 * happen at all. The commit records the delivery in the history, and the notifications its message events announced
 * are sent only after it.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = WorkflowEngine.class)
public class WorkflowEngineImpl implements WorkflowEngine
{
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

    /** Opens the engine's own session, in which every delivery runs. */
    @Reference
    private EngineSessions sessions;

    /** Evaluates every guard, on a start event and on a gateway's arcs alike. */
    @Reference
    private ConditionEvaluator conditionEvaluator;

    /** Ends the user tasks an event is aimed at, and carries their instances on. */
    @Reference
    private TaskCompletion taskCompletion;

    /** Says who the names in a definition stand for: special names, users, and groups however they are stored. */
    @Reference
    private Performers performers;

    /** Answers what a user could do without changing anything. */
    @Reference
    private WorkflowQueries queries;

    /** Hands out what performs a delivery's service tasks and holds its notifications. */
    @Reference
    private ServiceTaskDispatchers dispatchers;

    /** Commits each delivery so that the commit records it in the history. */
    @Reference
    private DeliveryRecorder recorder;

    @Reference
    private Delegation delegation;

    @Override
    public WorkflowResult receiveEvent(final Resource target, final WorkflowEvent event, final String onBehalfOf)
        throws WorkflowException
    {
        // Taken from the incoming resource rather than passed in. The session that resolved it is the one that
        // authenticated, so it is the authority on who is asking
        final ResourceResolver senderResolver = target.getResourceResolver();
        try (ResourceResolver serviceResolver = this.sessions.open()) {
            final Actor actor = this.delegation.authorizeActor(serviceResolver, senderResolver, onBehalfOf);
            // Re-resolved through the engine's session. From here on the run is privileged: the caller's own
            // view of the target may be nothing but the bare node they were allowed to post to
            final Resource privilegedTarget = Objects.requireNonNull(serviceResolver.getResource(target.getPath()),
                "A target the caller could reach is always visible to the engine");
            if (privilegedTarget.isResourceType(TaskInstance.RESOURCE_TYPE)) {
                return resume(privilegedTarget, event, actor);
            }
            return execute(privilegedTarget, event, admit(privilegedTarget, event, actor, 0), actor);
        }
    }

    @Override
    public Set<String> getAvailableEvents(final Resource target) throws WorkflowException
    {
        return this.queries.availableEvents(target, this.sessions::open);
    }

    @Override
    public WorkflowVersion findApplicableWorkflow(final Resource target, final String event) throws WorkflowException
    {
        return this.queries.applicableWorkflow(target, event, this.sessions::open);
    }

    /**
     * Carries a running instance on from the user task the event was aimed at. The same one-commit rule as a
     * system workflow: the decision, the task's new state and what follows from it all happen, or none of them
     * do.
     *
     * <p>Two walks of one instance at once conflict when the second commits. The loser is reverted, which also
     * brings its session up to date, and run again on top of what the winner did. Each attempt has its own
     * dispatcher, so the notifications a lost attempt announced are dropped with its writes.</p>
     *
     * @param task the task the event is aimed at, resolved through the engine's session
     * @param event the incoming event
     * @param actor who sent the event, and whom for
     * @return an empty result: ending a task creates nothing to send the caller to
     * @throws WorkflowException when the run cannot complete, typed by whose fault that is
     */
    private WorkflowResult resume(final Resource task, final WorkflowEvent event, final Actor actor)
        throws WorkflowException
    {
        final ResourceResolver resolver = task.getResourceResolver();
        final ServiceTaskDispatcher committed = RepositoryFailures.retryingConflicts(CONFLICT_RETRIES, () -> {
            final ServiceTaskDispatcher attempt = dispatcher();
            try {
                final Resource current = Objects.requireNonNull(resolver.getResource(task.getPath()),
                    "A task is never deleted while its instance runs");
                this.taskCompletion.apply(resolver, current, event, actor.effectiveUser(),
                    attempt.performer(event, actor));
                this.recorder.commit(resolver, attempt.recordedAsTask(actor, event, current));
                return attempt;
            } catch (final PersistenceException e) {
                revert(resolver);
                throw RepositoryFailures.translate(e);
            } catch (final WorkflowException | RuntimeException e) {
                revert(resolver);
                throw e;
            }
        });
        committed.completeAfterCommit(resolver);
        return new WorkflowResult(Map.of());
    }

    /**
     * Runs the matched workflow and commits everything it changed at once. A failing event leaves no trace.
     *
     * @param target the resource the event is aimed at, backed by the engine's own session
     * @param event the incoming event
     * @param start the entry point to run from
     * @param actor who sent the event, and whom for
     * @return the variables the execution left behind
     * @throws WorkflowException when the run cannot complete, typed by whose fault that is
     */
    private WorkflowResult execute(final Resource target, final WorkflowEvent event, final StartEvent start,
        final Actor actor) throws WorkflowException
    {
        final ResourceResolver resolver = target.getResourceResolver();
        final ServiceTaskDispatcher dispatcher = dispatcher();
        final Map<String, Object> variables;
        try {
            variables = dispatcher.runSystemWorkflow(target, event, start, actor, 0);
            this.recorder.commit(resolver, dispatcher.recordedAsSystemWorkflow(actor, event, start));
        } catch (final PersistenceException e) {
            revert(resolver);
            throw RepositoryFailures.translate(e);
        } catch (final WorkflowException | RuntimeException e) {
            revert(resolver);
            throw e;
        }
        dispatcher.completeAfterCommit(resolver);
        return new WorkflowResult(variables);
    }

    /**
     * Admits an event: finds the system workflow waiting for it, and checks that the user may fire it. An event
     * another workflow sent is admitted the same way as one that came in, as part of the sender's execution and for
     * the same user.
     *
     * @param target the resource the event is aimed at, backed by the engine's own session
     * @param event the event
     * @param actor who sent the event, and whom for; for an event a service task sent, the sending execution's
     * @param depth how many sent events deep this is, 0 for an event that came in
     * @return the start event of the system workflow waiting for it
     * @throws WorkflowException when the event is refused, or events are sent too deep
     */
    private StartEvent admit(final Resource target, final WorkflowEvent event, final Actor actor, final int depth)
        throws WorkflowException
    {
        if (depth > MAX_SENT_EVENTS_DEPTH) {
            throw new WorkflowDefinitionException("Workflows sent events more than " + MAX_SENT_EVENTS_DEPTH
                + " deep, the last one " + event.getName() + " to " + target.getPath()
                + "; their definitions probably send events to each other in a loop");
        }
        final ResourceResolver resolver = target.getResourceResolver();
        final StartEvent start = SystemWorkflowLocator.find(resolver, target, event, this.conditionEvaluator);
        this.performers.createCheck(resolver, actor.effectiveUser()).verify(start, target);
        return start;
    }

    /**
     * How a delivery performs its service tasks and holds its notifications, with this engine to admit an event one
     * of them sends.
     *
     * @return a dispatcher for one delivery
     */
    private ServiceTaskDispatcher dispatcher()
    {
        return this.dispatchers.create(this::admit);
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

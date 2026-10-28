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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.EndEvent;
import io.uhndata.iap.workflows.models.FlowNode;
import io.uhndata.iap.workflows.models.StartEvent;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Performs service tasks, the same way for both kinds of workflow: a system workflow's own run, and a running
 * instance's through {@link #performer}. A handler therefore behaves identically whichever kind reached it, and
 * nothing here may assume which.
 *
 * <p>One dispatcher serves one run, working from the handlers registered when the run began.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ServiceTaskDispatcher
{
    /** Where the human an execution acted for is recorded, {@code jcr:createdBy} being the engine itself. */
    private static final String CREATED_BY_PROPERTY = "createdBy";

    private final List<ServiceTaskHandler> handlers;

    /** How the engine runs the workflow waiting for an event a service task sends. */
    private final EventChain chain;

    /** Moves a system workflow's run from node to node. */
    private final FlowRouting routing;

    /** Hands out the runner for any instance a task starts. */
    private final InstanceRunners runners;

    /**
     * Constructor.
     *
     * @param handlers the registered service task handlers
     * @param chain how the engine runs the workflow waiting for a sent event
     * @param routing moves a system workflow's run from node to node
     * @param runners hands out the runner for any instance a task starts
     */
    ServiceTaskDispatcher(final List<ServiceTaskHandler> handlers, final EventChain chain, final FlowRouting routing,
        final InstanceRunners runners)
    {
        this.handlers = handlers;
        this.chain = chain;
        this.routing = routing;
        this.runners = runners;
    }

    /**
     * Runs a system workflow to quiescence, without committing: node by node from the start event, performing
     * each service task, until an end event is reached. A system workflow has no instance, and nowhere to wait.
     * Any node that cannot be passed straight through means the definition is not a valid system workflow.
     *
     * @param target the resource the event is aimed at, backed by the engine's own session
     * @param event the incoming event
     * @param start the entry point to run from
     * @param actor the user who fired the event
     * @param depth how many sent events deep this run is, 0 for the event that came in
     * @return the variables the run left behind
     * @throws WorkflowException when the run cannot complete, typed by whose fault that is
     * @throws PersistenceException when a write fails
     */
    Map<String, Object> runSystemWorkflow(final Resource target, final WorkflowEvent event, final StartEvent start,
        final String actor, final int depth) throws WorkflowException, PersistenceException
    {
        final ResourceResolver resolver = target.getResourceResolver();
        final Map<String, Object> variables = new LinkedHashMap<>();
        FlowNode node = start;
        for (int step = 0; step < WorkflowEngineImpl.MAX_STEPS; step++) {
            if (node instanceof EndEvent) {
                return variables;
            }
            if (node instanceof Activity) {
                perform((Activity) node, new WorkflowTaskContextImpl(target, event, (Activity) node, variables, actor,
                    this, depth));
                // After each activity rather than at the end: a later activity may start a workflow on what this one
                // created, and @creator there reads createdBy
                recordActor(resolver, variables, actor);
            } else if (!(node instanceof StartEvent) || step > 0) {
                // A system workflow cannot contain this node: there is no persisted instance whose token
                // could rest here
                throw new WorkflowDefinitionException("A system workflow cannot wait, but " + node.getPath()
                    + " is not a straight-through node");
            }
            node = this.routing.followOnlyFlow(node);
        }
        throw new WorkflowDefinitionException("The workflow did not reach an end event within "
            + WorkflowEngineImpl.MAX_STEPS + " steps; its sequence flows probably form a cycle");
    }

    /**
     * Records who an execution acted for, on whatever it created. The write itself was the engine's, so
     * {@code jcr:createdBy} names the service user. Nothing else would remember the human. The audit trail, every
     * "things I raised" listing and {@code @creator} all read it.
     *
     * @param resolver the engine's session, still uncommitted
     * @param variables the execution's variables, consulted for what was created
     * @param actor the user who fired the event
     * @throws PersistenceException when the created node cannot be written to
     */
    private static void recordActor(final ResourceResolver resolver, final Map<String, Object> variables,
        final String actor) throws PersistenceException
    {
        final Object created = variables.get(WorkflowResult.CREATED_PATH_VARIABLE);
        if (!(created instanceof String)) {
            return;
        }
        final Resource resource = Objects.requireNonNull(resolver.getResource((String) created),
            "A handler reported creating something that is not there");
        Objects.requireNonNull(resource.adaptTo(ModifiableValueMap.class),
            "A node the engine just created is always modifiable").put(CREATED_BY_PROPERTY, actor);
    }

    /**
     * Performs one service task by dispatching to the handler its activity names.
     *
     * @param activity the activity node being executed
     * @param context what the handler gets to work with
     * @throws WorkflowException when the activity cannot be performed
     * @throws PersistenceException when the handler's repository writes fail immediately
     */
    void perform(final Activity activity, final WorkflowTaskContext context)
        throws WorkflowException, PersistenceException
    {
        final String name = activity.getHandler();
        if (name == null) {
            throw new WorkflowDefinitionException("The activity " + activity.getPath()
                + " names no handler to perform it automatically");
        }
        final ServiceTaskHandler handler = this.handlers.stream()
            .filter(candidate -> name.equals(candidate.getName()))
            .findFirst()
            .orElse(null);
        if (handler == null) {
            throw new WorkflowDefinitionException(
                "The activity " + activity.getPath() + " names the handler " + name + ", but none is registered");
        }
        handler.execute(context);
    }

    /**
     * How an instance performs a service task it meets, through the same dispatch a system workflow uses. The
     * variables belong to this delivery; an instance's persisted variables are not yet exposed to handlers.
     *
     * @param event the event being delivered
     * @param actor the user the instance is being moved for
     * @return a performer bound to this delivery
     */
    InstanceRunner.ServiceTaskPerformer performer(final WorkflowEvent event, final String actor)
    {
        final Map<String, Object> variables = new LinkedHashMap<>();
        return (activity, instance) -> perform(activity,
            new WorkflowTaskContextImpl(InstanceRecords.hostOf(instance), event, activity, variables, actor, this, 0));
    }

    /**
     * A runner for an instance a service task starts, performing the service tasks it meets through this dispatcher.
     *
     * @param resolver the engine's own session
     * @param event the event being delivered
     * @param actor the user the execution acts for
     * @return the runner
     */
    InstanceRunner createRunner(final ResourceResolver resolver, final WorkflowEvent event, final String actor)
    {
        return this.runners.createRunner(resolver, performer(event, actor), actor);
    }

    /**
     * Runs the workflow waiting for an event a service task sent, as part of the sending execution.
     *
     * @param target the resource the event is sent to, backed by the engine's own session
     * @param event the sent event
     * @param actor the user the sending execution acts for
     * @param depth how many sent events deep this one is
     * @throws WorkflowException when the event is refused, the workflow fails, or events are sent too deep
     * @throws PersistenceException when the workflow's writes fail
     */
    void send(final Resource target, final WorkflowEvent event, final String actor, final int depth)
        throws WorkflowException, PersistenceException
    {
        this.chain.send(target, event, actor, depth);
    }

    /**
     * How the engine runs the system workflow waiting for an event a service task sent, as part of the sending
     * execution.
     *
     * @version $Id$
     * @since 0.1.0
     */
    @FunctionalInterface
    interface EventChain
    {
        /**
         * Runs the workflow waiting for a sent event, without committing.
         *
         * @param target the resource the event is sent to, backed by the engine's own session
         * @param event the sent event
         * @param actor the user the sending execution acts for
         * @param depth how many sent events deep this one is
         * @throws WorkflowException when the event is refused, the workflow fails, or events are sent too deep
         * @throws PersistenceException when the workflow's writes fail
         */
        void send(Resource target, WorkflowEvent event, String actor, int depth)
            throws WorkflowException, PersistenceException;
    }
}

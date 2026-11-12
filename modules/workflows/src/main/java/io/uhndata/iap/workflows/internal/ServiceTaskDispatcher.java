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

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.history.api.RecordedAction;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.Event;
import io.uhndata.iap.workflows.models.StartEvent;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Performs service tasks and throws the messages of message events, the same way for both kinds of
 * workflow: a system workflow's run, and a running instance's through {@link #performer}. A handler therefore behaves
 * identically whichever kind reached it, and nothing here may assume which.
 *
 * <p>One dispatcher serves one delivery, including the workflows its events send to, working from the handlers
 * registered when it began. {@link ServiceTaskDispatchers} hands it out.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ServiceTaskDispatcher
{
    private final List<ServiceTaskHandler> handlers;

    /** How the engine admits an event a service task sends. */
    private final EventChain chain;

    /** Moves a system workflow's run from node to node. */
    private final FlowRouting routing;

    /** Hands out the runner for any instance a task starts. */
    private final InstanceRunners runners;

    /** What the delivery's message events announced, and what its steps said they did. */
    private final DeliveryConsequences consequences;

    /**
     * Constructor.
     *
     * @param handlers the registered service task handlers
     * @param chain how the engine admits an event a service task sends
     * @param routing moves a system workflow's run from node to node
     * @param runners hands out the runner for any instance a task starts
     * @param consequences what the delivery leaves behind besides its writes
     */
    ServiceTaskDispatcher(final List<ServiceTaskHandler> handlers, final EventChain chain, final FlowRouting routing,
        final InstanceRunners runners, final DeliveryConsequences consequences)
    {
        this.handlers = handlers;
        this.chain = chain;
        this.routing = routing;
        this.runners = runners;
        this.consequences = consequences;
    }

    /**
     * Throws the message a throwing event names, as part of this delivery. The notification it announces is about
     * what the event {@link Event#actsOn acts on}, and waits for the delivery to commit.
     *
     * @param source the throwing event reached
     * @param target the resource the event is aimed at, backed by the engine's own session
     * @param cause the event being delivered
     * @param actor the user the execution acts for
     * @param variables the execution's variables, which say what it created
     * @throws WorkflowDefinitionException when the event names an unknown subject
     */
    void throwMessage(final Event source, final Resource target, final WorkflowEvent cause, final String actor,
        final Map<String, Object> variables) throws WorkflowDefinitionException
    {
        this.consequences.hold(source, target, variables, cause, actor);
    }

    /**
     * What this delivery leaves behind besides its writes, where its steps note what they did and leave work for
     * after its commit.
     *
     * @return the delivery's consequences
     */
    DeliveryConsequences consequences()
    {
        return this.consequences;
    }

    /**
     * This delivery as its history records it, when a system workflow handled its event.
     *
     * @param actor the user who sent the event
     * @param event the event
     * @param start the start event of the system workflow that caught it
     * @return the action
     */
    RecordedAction recordedAsSystemWorkflow(final String actor, final WorkflowEvent event, final StartEvent start)
    {
        return this.consequences.recordedAsSystemWorkflow(actor, event, start);
    }

    /**
     * This delivery as its history records it, when its event completed a task of a running instance.
     *
     * @param actor the user who sent the event
     * @param event the event
     * @param task the task the event completed, as the event left it
     * @return the action
     */
    RecordedAction recordedAsTask(final String actor, final WorkflowEvent event, final Resource task)
    {
        return this.consequences.recordedAsTask(actor, event, task);
    }

    /**
     * Finishes what waited for this delivery's commit: its notifications, and the work its steps left.
     *
     * @param resolver the engine's resolver, which committed the delivery
     */
    void completeAfterCommit(final ResourceResolver resolver)
    {
        this.consequences.completeAfterCommit(resolver);
    }

    /**
     * Runs a system workflow to quiescence as part of this delivery, without committing.
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
        return new SystemWorkflowRunner(this, this.routing).run(target, event, start, actor, depth);
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
     * How an instance performs the steps it meets during this delivery.
     *
     * @param event the event being delivered
     * @param actor the user the instance is being moved for
     * @return a performer bound to this delivery
     */
    InstanceRunner.StepPerformer performer(final WorkflowEvent event, final String actor)
    {
        return new InstanceSteps(this, event, actor);
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
     * Runs the workflow waiting for an event a service task sent, as part of the sending execution: admitted by the
     * engine, then run in this delivery, so that what it announces is sent with the rest.
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
        runSystemWorkflow(target, event, this.chain.admit(target, event, actor, depth), actor, depth);
    }

    /**
     * How the engine admits an event a service task sent: which system workflow is waiting for it, and whether the
     * user the sending execution acts for may fire it.
     *
     * @version $Id$
     * @since 0.1.0
     */
    @FunctionalInterface
    interface EventChain
    {
        /**
         * Checks that the sending execution's user may send the event, and finds the start event waiting for it.
         *
         * @param target the resource the event is sent to, backed by the engine's own session
         * @param event the sent event
         * @param actor the user the sending execution acts for
         * @param depth how many sent events deep this one is
         * @return the start event to run the waiting workflow from
         * @throws WorkflowException when the event is refused, or events are sent too deep
         */
        StartEvent admit(Resource target, WorkflowEvent event, String actor, int depth) throws WorkflowException;
    }
}

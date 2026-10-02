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

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;

import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.Activity;
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
    private final List<ServiceTaskHandler> handlers;

    /** How the engine runs the workflow waiting for an event a service task sends. */
    private final EventChain chain;

    /**
     * Constructor.
     *
     * @param handlers the registered service task handlers
     * @param chain how the engine runs the workflow waiting for a sent event
     */
    ServiceTaskDispatcher(final List<ServiceTaskHandler> handlers, final EventChain chain)
    {
        this.handlers = handlers;
        this.chain = chain;
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
            new WorkflowTaskContextImpl(hostOf(instance), event, activity, variables, actor, this, 0));
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
     * The resource an instance drives, two levels up past its container.
     *
     * @param instance a running instance
     * @return the host resource
     */
    private Resource hostOf(final Resource instance)
    {
        return Objects.requireNonNull(Objects.requireNonNull(instance.getParent(),
            "An instance always lives in a container").getParent(), "A container always lives in its host");
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

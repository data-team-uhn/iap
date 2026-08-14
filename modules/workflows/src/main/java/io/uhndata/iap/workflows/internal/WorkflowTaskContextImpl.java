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

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The context handed to service task handlers by the engine: the pieces of one execution, and what a handler may
 * ask the engine to do within it.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class WorkflowTaskContextImpl implements WorkflowTaskContext
{
    private final Resource target;

    private final WorkflowEvent event;

    private final Activity activity;

    private final Map<String, Object> variables;

    private final String actor;

    /** How the execution performs service tasks, which the events it sends and the instances it starts share. */
    private final ServiceTaskDispatcher dispatcher;

    /** How many sent events deep the execution is. */
    private final int depth;

    /**
     * Constructor.
     *
     * @param target the resource the triggering event was aimed at
     * @param event the triggering event
     * @param activity the activity being performed
     * @param variables the execution's shared, mutable variables
     * @param actor the user the execution is acting for
     * @param dispatcher how the execution performs service tasks
     * @param depth how many sent events deep the execution is, 0 for an event that came in
     */
    WorkflowTaskContextImpl(final Resource target, final WorkflowEvent event, final Activity activity,
        final Map<String, Object> variables, final String actor, final ServiceTaskDispatcher dispatcher,
        final int depth)
    {
        this.target = target;
        this.event = event;
        this.activity = activity;
        this.variables = variables;
        this.actor = actor;
        this.dispatcher = dispatcher;
        this.depth = depth;
    }

    @Override
    public Resource getTarget()
    {
        return this.target;
    }

    @Override
    public String getActor()
    {
        return this.actor;
    }

    @Override
    public WorkflowEvent getEvent()
    {
        return this.event;
    }

    @Override
    public Activity getActivity()
    {
        return this.activity;
    }

    @Override
    public Object getVariable(final String name)
    {
        return this.variables.get(name);
    }

    @Override
    public void setVariable(final String name, final Object value)
    {
        this.variables.put(name, value);
    }

    @Override
    public ResourceResolver getResourceResolver()
    {
        return this.target.getResourceResolver();
    }

    @Override
    public void sendEvent(final Resource to, final WorkflowEvent sent) throws WorkflowException, PersistenceException
    {
        this.dispatcher.send(to, sent, this.actor, this.depth + 1);
    }

    @Override
    public void startWorkflow(final Resource host, final WorkflowVersion version)
        throws WorkflowException, PersistenceException
    {
        new InstanceRunner(getResourceResolver(), this.dispatcher.performer(this.event, this.actor), this.actor,
            new FlowRouting(this.dispatcher.conditions()), this.dispatcher.principals()).start(host, version);
        HostAccess.grantReaders(this.dispatcher.principals(), getResourceResolver(), host, version, this.actor);
    }
}

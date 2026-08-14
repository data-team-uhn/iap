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
package io.uhndata.iap.submissions.internal.handlers;

import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * A task context for calling a handler directly, the way the engine would.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class TaskContext implements WorkflowTaskContext
{
    private final Resource target;

    private final String actor;

    private final WorkflowEvent event;

    private final Map<String, Object> variables = new HashMap<>();

    /**
     * A context for one handler run.
     *
     * @param target what the event is aimed at
     * @param actor who sent the event
     * @param payload what the event carries
     */
    TaskContext(final Resource target, final String actor, final Map<String, Object> payload)
    {
        this.target = target;
        this.actor = actor;
        this.event = new WorkflowEvent("test", payload);
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
        throw new IllegalStateException("No activity was expected to be read here");
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
    public void sendEvent(final Resource to, final WorkflowEvent sent)
    {
        throw new IllegalStateException("No event was expected to be sent here");
    }

    @Override
    public void startWorkflow(final Resource host, final WorkflowVersion version)
    {
        throw new IllegalStateException("No workflow was expected to be started here");
    }
}

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
package io.uhndata.iap.schemas.editing.internal;

import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.models.Activity;
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

    private final WorkflowEvent event;

    private final Activity activity;

    private final Map<String, Object> variables = new HashMap<>();

    /**
     * A context for one handler run.
     *
     * @param target what the event is aimed at
     * @param payload what the event carries
     * @param configuration the activity's own properties
     */
    TaskContext(final Resource target, final Map<String, Object> payload, final Map<String, Object> configuration)
    {
        this.target = target;
        this.event = new WorkflowEvent("test", payload);
        this.activity = Mockito.mock(Activity.class);
        Mockito.when(this.activity.getPath()).thenReturn("/SystemWorkflows/test/v1/task");
        configuration.forEach((key, value) -> Mockito.when(this.activity.get(key)).thenReturn(value));
    }

    @Override
    public Resource getTarget()
    {
        return this.target;
    }

    @Override
    public String getActor()
    {
        return "admin";
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
}

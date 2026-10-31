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
import java.util.Map;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;

import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.Event;

/**
 * How a running instance performs the steps it meets during one delivery, through the dispatch a system workflow
 * uses. Both act on the instance's host. The variables belong to the delivery; an instance's persisted variables are
 * not yet exposed to handlers.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class InstanceSteps implements InstanceRunner.StepPerformer
{
    private final ServiceTaskDispatcher dispatcher;

    private final WorkflowEvent event;

    private final String actor;

    private final Map<String, Object> variables = new LinkedHashMap<>();

    /**
     * Constructor.
     *
     * @param dispatcher the delivery's dispatcher
     * @param event the event being delivered
     * @param actor the user the instance is being moved for
     */
    InstanceSteps(final ServiceTaskDispatcher dispatcher, final WorkflowEvent event, final String actor)
    {
        this.dispatcher = dispatcher;
        this.event = event;
        this.actor = actor;
    }

    @Override
    public void perform(final Activity activity, final Resource instance)
        throws WorkflowException, PersistenceException
    {
        this.dispatcher.perform(activity, new WorkflowTaskContextImpl(InstanceRecords.hostOf(instance), this.event,
            activity, this.variables, this.actor, this.dispatcher, 0));
    }

    @Override
    public void throwMessage(final Event source, final Resource instance)
    {
        this.dispatcher.getNotifications().announce(source, InstanceRecords.hostOf(instance), this.event, this.actor);
    }
}

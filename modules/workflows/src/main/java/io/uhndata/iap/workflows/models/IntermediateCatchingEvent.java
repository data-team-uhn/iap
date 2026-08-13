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
package io.uhndata.iap.workflows.models;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.Default;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.Nullable;

/**
 * A Sling Model wrapping a {@code wf:IntermediateCatchingEvent} node: a point mid-process where the workflow waits.
 * A token resting here is what makes the matching incoming event acceptable, so these are the places an outside
 * event can enter a running instance. Nested under an {@link Activity} instead of directly under the version, the
 * same node type is a boundary event, waiting only for as long as that activity is running.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = {FlowNode.class, Event.class, IntermediateEvent.class},
    resourceType = IntermediateCatchingEvent.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class IntermediateCatchingEvent extends IntermediateEvent
{
    /** The {@code sling:resourceType} of a {@code wf:IntermediateCatchingEvent} node. */
    public static final String RESOURCE_TYPE = "wf/IntermediateCatchingEvent";

    // The node type autocreates this as true, so it is normally present. The annotation is what covers the node
    // that got past that -- one of another type carrying this resource type, or one written before the property was
    // autocreated -- which would otherwise read as false here: the exact inversion of the BPMN cancelActivity
    // default, and a silent one.
    @ValueMapValue
    @Default(booleanValues = true)
    private boolean interrupting;

    /**
     * Whether firing this event cancels the activity being watched, or merely starts a parallel branch and lets the
     * work carry on. "Escalate after five days but keep waiting" and "give up after five days" are different
     * processes, and this is the only thing that tells them apart. Only meaningful when this event is attached to an
     * activity, which {@link #getActivity()} reports.
     *
     * @return {@code true} if firing cancels the watched activity
     */
    public boolean isInterrupting()
    {
        return this.interrupting;
    }

    /**
     * The activity this event is attached to, which is simply its parent. An event stored directly under the version
     * stands in the flow on its own and watches nothing, so this is how to tell a boundary event from a free-standing
     * one.
     *
     * @return the watched activity, or {@code null} if this event does not sit inside one
     */
    @Nullable
    public Activity getActivity()
    {
        return this.getParent(Activity.RESOURCE_TYPE, Activity.class);
    }
}

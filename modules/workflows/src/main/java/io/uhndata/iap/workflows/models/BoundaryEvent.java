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
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.Nullable;

/**
 * A Sling Model wrapping a {@code wf:BoundaryEvent} node: a catching event attached to an activity, listening only
 * for as long as that activity is running — a deadline expiring, a cancellation arriving, the work itself failing.
 * It is stored inside the activity it watches, which is what makes it a boundary event rather than a free-standing
 * one, and is reached through {@link Activity#getBoundaryEvents()}.
 *
 * <p>It is a catching intermediate event, but sits beside {@link IntermediateCatchingEvent} rather than
 * under it: every base in this hierarchy is abstract and every leaf concrete, and a model extending a
 * concrete model does not get its own fields injected.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class,
    adapters = {FlowNode.class, Event.class, IntermediateEvent.class},
    resourceType = BoundaryEvent.RESOURCE_TYPE, defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class BoundaryEvent extends IntermediateEvent
{
    /** The {@code sling:resourceType} of a {@code wf:BoundaryEvent} node. */
    public static final String RESOURCE_TYPE = "wf/BoundaryEvent";

    @ValueMapValue
    private boolean interrupting;

    /**
     * Whether firing this event cancels the activity being watched, or merely starts a parallel branch and lets the
     * work carry on. "Escalate after five days but keep waiting" and "give up after five days" are different
     * processes, and this is the only thing that tells them apart.
     *
     * @return {@code true} if firing cancels the watched activity
     */
    public boolean isInterrupting()
    {
        return this.interrupting;
    }

    /**
     * The activity this event is watching, which is simply its parent.
     *
     * @return the watched activity, or {@code null} if this event is stored outside one
     */
    @Nullable
    public Activity getActivity()
    {
        return this.getParent(Activity.RESOURCE_TYPE, Activity.class);
    }
}

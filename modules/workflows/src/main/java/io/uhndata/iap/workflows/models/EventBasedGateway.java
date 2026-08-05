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

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;

/**
 * A Sling Model wrapping a {@code wf:EventBasedGateway} node: a gateway that routes on what happens next rather
 * than on data. It parks the token, arms every catching event it leads to, and the first one to fire wins,
 * discarding the rest — which is how "approved, rejected, or nobody answered in time" is expressed.
 *
 * <p>Unlike the data-based gateways it never evaluates a condition and never picks a path itself, so a
 * {@link Gateway#getDefaultFlow() default flow} means nothing here — the events it is waiting for are the choice.
 * The inherited accessor still reports whatever the diagram marked; it is the engine that disregards it.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = {FlowNode.class, Gateway.class},
    resourceType = EventBasedGateway.RESOURCE_TYPE, defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class EventBasedGateway extends Gateway
{
    /** The {@code sling:resourceType} of a {@code wf:EventBasedGateway} node. */
    public static final String RESOURCE_TYPE = "wf/EventBasedGateway";

    @ValueMapValue
    private boolean instantiate;

    /**
     * Whether reaching this gateway starts a new instance rather than routing an existing one, the event-based
     * equivalent of offering several alternative start events.
     *
     * @return {@code true} if this gateway instantiates the workflow
     */
    public boolean isInstantiate()
    {
        return this.instantiate;
    }

    /**
     * The events this gateway is waiting for: the targets of its outgoing arcs, which BPMN requires to be catching
     * events. Whichever fires first decides the route, so this is the set the engine has to arm together and
     * disarm as one.
     *
     * @return the catching events reachable from this gateway, empty if its arcs dangle or lead elsewhere
     */
    @NotNull
    public List<Event> getAwaitedEvents()
    {
        return this.getOutgoingFlows().stream()
            .map(SequenceFlow::getTarget)
            .filter(Event.class::isInstance)
            .map(Event.class::cast)
            .filter(Event::isCatching)
            .toList();
    }
}

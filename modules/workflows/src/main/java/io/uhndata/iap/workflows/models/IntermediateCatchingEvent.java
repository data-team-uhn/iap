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
}

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

import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.Nullable;

/**
 * The abstract base shared by everything in a workflow graph that marks something happening rather than something
 * being done: a {@link StartEvent}, an {@link EndEvent}, or an intermediate event. Corresponds to the
 * {@code wf:Event} node type. Like {@link FlowNode}, it is not itself a registered Sling Model.
 *
 * <p>The one distinction that matters to the engine is {@link #isCatching() catching versus throwing}: a token
 * rests at a catching event, waiting for the outside world, and that resting place is what makes an incoming event
 * acceptable. A throwing event never waits.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public abstract class Event extends FlowNode
{
    /** The {@code sling:resourceType} of a {@code wf:Event} node. */
    public static final String RESOURCE_TYPE = "wf/Event";

    @ValueMapValue
    private boolean catching;

    @ValueMapValue
    private String messageName;

    /**
     * Whether this event waits for something to happen, rather than announcing that something has.
     *
     * <p>Which one an event is follows from its node type and never varies within one, so each concrete node type
     * autocreates the value it fixes and protects it against being written. That is what makes reading it here
     * safe: were the property merely declared with a default, it would never be written at all, and every start
     * and boundary event would quietly report as throwing.</p>
     *
     * @return {@code true} if a token reaching this event rests here until the event is triggered
     */
    public boolean isCatching()
    {
        return this.catching;
    }

    /**
     * The name of the BPMN message this event catches or throws — the domain event name the engine matches
     * incoming events against.
     *
     * @return a message name, or {@code null} for events that are not message-flavored
     */
    @Nullable
    public String getMessageName()
    {
        return this.messageName;
    }
}

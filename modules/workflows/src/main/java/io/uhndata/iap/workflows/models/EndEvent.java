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

/**
 * A Sling Model wrapping a {@code wf:EndEvent} node: where a branch of the workflow stops. Always
 * {@link Event#isCatching() throwing} — reaching one consumes the token rather than parking it, and an instance is
 * complete once every one of its tokens has reached an end event.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = {FlowNode.class, Event.class},
    resourceType = EndEvent.RESOURCE_TYPE, defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class EndEvent extends Event
{
    /** The {@code sling:resourceType} of a {@code wf:EndEvent} node. */
    public static final String RESOURCE_TYPE = "wf/EndEvent";

    @ValueMapValue
    private boolean terminate;

    /**
     * Whether reaching this event ends the whole instance at once, discarding every other token that is still in
     * flight, rather than just consuming the one that arrived. An ordinary end event finishes one branch; this
     * finishes the process.
     *
     * @return {@code true} if this event terminates the whole instance
     */
    public boolean isTerminate()
    {
        return this.terminate;
    }
}

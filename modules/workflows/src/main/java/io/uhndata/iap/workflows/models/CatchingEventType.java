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
import org.jetbrains.annotations.NotNull;

/**
 * A Sling Model wrapping a {@code wf:CatchingEventType} node: an event the workflow waits at, e.g. a plain or
 * message start event, or an intermediate catch event. A token resting on a node of this kind is what makes the
 * matching incoming event acceptable.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = FlowNodeType.class, resourceType = CatchingEventType.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class CatchingEventType extends FlowNodeType
{
    /** The {@code sling:resourceType} of a {@code wf:CatchingEventType} node. */
    public static final String RESOURCE_TYPE = "wf/CatchingEventType";

    /** The toolbar group used for Events entries that do not declare one of their own. */
    private static final String DEFAULT_CATEGORY = "Events";

    @Override
    @NotNull
    protected String getDefaultCategory()
    {
        return DEFAULT_CATEGORY;
    }
}

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
 * A Sling Model wrapping a {@code wf:ActivityType} node: a kind of work the workflow has to get done, e.g. a user
 * task or a service task. Every entry of this kind maps onto the single {@code wf:Activity} node type; which kind
 * of work a given activity stands for comes from its reference back to this entry.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = FlowNodeType.class, resourceType = ActivityType.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class ActivityType extends FlowNodeType
{
    /** The {@code sling:resourceType} of a {@code wf:ActivityType} node. */
    public static final String RESOURCE_TYPE = "wf/ActivityType";

    /** The toolbar group used for Activities entries that do not declare one of their own. */
    private static final String DEFAULT_CATEGORY = "Activities";

    @Override
    @NotNull
    protected String getDefaultCategory()
    {
        return DEFAULT_CATEGORY;
    }
}

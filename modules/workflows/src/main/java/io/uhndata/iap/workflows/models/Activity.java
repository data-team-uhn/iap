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
import org.jetbrains.annotations.NotNull;

/**
 * A Sling Model wrapping a {@code wf:Activity} node: a unit of work the workflow has to get done. Whether that work
 * falls to a person or to the platform is not a distinction in the node type but in the
 * {@link FlowNode#getFlowNodeType() vocabulary entry} the activity points at, which is what lets a new kind of task
 * be introduced without a new node type or a new model.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = FlowNode.class, resourceType = Activity.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Activity extends FlowNode
{
    /** The {@code sling:resourceType} of a {@code wf:Activity} node. */
    public static final String RESOURCE_TYPE = "wf/Activity";

    /**
     * The events watching this activity while it runs, e.g. a deadline expiring or a cancellation arriving. They
     * are stored inside the activity precisely because they only listen for as long as it runs. Not all of them
     * necessarily cancel it — see {@link BoundaryEvent#isInterrupting()}.
     *
     * @return a list of boundary events, empty if nothing is watching this activity
     */
    @NotNull
    public List<BoundaryEvent> getBoundaryEvents()
    {
        return this.getChildren(BoundaryEvent.RESOURCE_TYPE, BoundaryEvent.class);
    }
}

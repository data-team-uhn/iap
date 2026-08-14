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
import org.jetbrains.annotations.Nullable;

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

    @ValueMapValue
    private String handler;

    @ValueMapValue
    private String[] outcomes;

    /**
     * The name of the {@code ServiceTaskHandler} that performs this activity. Only meaningful on
     * service-task-typed activities: user tasks are performed by people, so they wait instead of naming a
     * handler.
     *
     * @return a handler name, or {@code null} when nothing performs this activity automatically
     */
    @Nullable
    public String getHandler()
    {
        return this.handler;
    }

    /**
     * The decisions a person may complete this task with, e.g. {@code approved} and {@code rejected} — the values a
     * {@link Gateway} downstream then routes on. Only meaningful on user tasks: nobody asks a service task what it
     * decided.
     *
     * <p>Declared here because a task list has to know what to offer, and the only other record of which outcomes
     * exist is the {@link SequenceFlow#getConditionExpression() condition} on some later gateway's arcs — which is
     * where they are <em>consumed</em>, not where they are announced, and which the person doing the task cannot
     * necessarily read. So an empty list is a statement, not a gap: this is a task there is nothing to decide
     * about, done or not done, and completing it records no decision.</p>
     *
     * @return the outcomes this task offers, empty when completing it is not a decision
     */
    @NotNull
    public List<String> getOutcomes()
    {
        return this.outcomes == null ? List.of() : List.of(this.outcomes);
    }

    /**
     * The events watching this activity while it runs, e.g. a deadline expiring or a cancellation arriving. They are
     * stored inside the activity precisely because they only listen for as long as it runs, and being stored here is
     * the whole of what makes them boundary events rather than free-standing ones. Not all of them necessarily
     * cancel the activity — see {@link IntermediateCatchingEvent#isInterrupting()}.
     *
     * @return a list of boundary events, empty if nothing is watching this activity
     */
    @NotNull
    public List<IntermediateCatchingEvent> getBoundaryEvents()
    {
        return this.getChildren(IntermediateCatchingEvent.RESOURCE_TYPE, IntermediateCatchingEvent.class);
    }
}

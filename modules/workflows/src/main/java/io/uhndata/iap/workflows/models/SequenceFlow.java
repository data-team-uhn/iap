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

import java.util.Optional;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.conditions.models.Condition;
import io.uhndata.iap.conditions.models.Conditionable;
import io.uhndata.iap.entities.models.EntityPart;

/**
 * A Sling Model wrapping a {@code wf:SequenceFlow} node: one directed arc of the workflow graph. An arc is stored
 * inside the node it leaves, so a node's outgoing arcs are simply its children, and the node it leads to is named
 * by {@link #getTargetRef() element identifier} rather than held as a reference — the graph is addressed the way
 * BPMN addresses it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = SequenceFlow.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class SequenceFlow extends EntityPart implements Conditionable
{
    /** The {@code sling:resourceType} of a {@code wf:SequenceFlow} node. */
    public static final String RESOURCE_TYPE = "wf/SequenceFlow";

    @ValueMapValue
    private String elementId;

    @ValueMapValue
    private String label;

    @ValueMapValue
    private String targetRef;

    @ValueMapValue(name = "isDefault")
    private boolean defaultFlow;

    /**
     * The stable BPMN identifier of this arc.
     *
     * @return a BPMN element identifier
     */
    @NotNull
    public String getElementId()
    {
        return this.elementId;
    }

    /**
     * The label shown along this arc in the editor, typically naming the choice it stands for, e.g. "Rejected".
     *
     * @return a label, or {@code null} if the arc is unlabelled
     */
    @Nullable
    public String getLabel()
    {
        return this.label;
    }

    /**
     * The {@link FlowNode#getElementId() element identifier} of the node this arc leads to.
     *
     * @return a BPMN element identifier
     */
    @NotNull
    public String getTargetRef()
    {
        return this.targetRef;
    }

    /**
     * The guard deciding whether this arc may be taken, evaluated when the arc leaves a conditional
     * {@link Gateway}. Structured rather than an expression, and the same mechanism schema items use to say when
     * they apply, so that what a process routes on is described once and read by one evaluator.
     *
     * @return a condition, or {@code null} if this arc is unconditional — which for a gateway means it is taken
     *         as soon as it is considered
     */
    @Override
    @Nullable
    public Condition getCondition()
    {
        return this.getChild("cond:condition", Condition.class);
    }

    /**
     * Whether this is the arc to fall back on when no other arc leaving the same gateway has a condition that
     * holds.
     *
     * @return {@code true} if this is the default outgoing flow of its source
     */
    public boolean isDefault()
    {
        return this.defaultFlow;
    }

    /**
     * The node this arc leaves, which is simply its parent. A parent carrying one of the abstract resource types
     * counts as no source rather than as a guessed-at one — see {@link ModelDispatch#isConcrete}.
     *
     * @return the source flow node, or {@code null} if this arc is stored outside one
     */
    @Nullable
    public FlowNode getSource()
    {
        final FlowNode source = this.getParent(FlowNode.RESOURCE_TYPE, FlowNode.class);
        return source != null && ModelDispatch.isConcrete(source) ? source : null;
    }

    /**
     * The node this arc leads to, resolved by looking its {@link #getTargetRef() element identifier} up in the
     * workflow version the arc belongs to.
     *
     * @return the target flow node, or {@code null} if the arc is stored outside a workflow version or names a node
     *         that version does not have
     */
    @Nullable
    public FlowNode getTarget()
    {
        return Optional.ofNullable(this.getSource())
            .map(FlowNode::getWorkflowVersion)
            .map(version -> version.getFlowNode(this.targetRef))
            .orElse(null);
    }
}

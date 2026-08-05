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
import java.util.Objects;
import java.util.stream.Stream;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.entities.models.EntityPart;

/**
 * The abstract base shared by everything that can appear as a node of a workflow graph: an {@link Event}, an
 * {@link Activity}, or a {@link Gateway}. Corresponds to the {@code wf:FlowNode} node type.
 *
 * <p>What a node <em>means</em> is not carried by its Java class but by its {@link #getFlowNodeType() type
 * reference} into the {@code /WorkflowTypes} vocabulary: a user task and a service task are both plain
 * {@link Activity} nodes, told apart by the {@link FlowNodeType} they point at. The Java hierarchy only captures
 * the distinctions the engine has to make structurally, which is why it is much shallower than BPMN's.</p>
 *
 * <p>Like the other abstract bases in the IAP data model, this class is deliberately not itself a registered Sling
 * Model (no {@code @Model} annotation): each concrete subtype instead lists the abstract bases it should answer for
 * in the {@code adapters} of its own {@code @Model}, so that {@code resource.adaptTo(FlowNode.class)} dispatches to
 * the actual subtype rather than yielding a generic {@code FlowNode} missing that subtype's fields.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public abstract class FlowNode extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code wf:FlowNode} node. */
    public static final String RESOURCE_TYPE = "wf/FlowNode";

    @ValueMapValue
    private String elementId;

    @ValueMapValue
    private String label;

    @ValueMapValue
    private String flowNodeType;

    /**
     * The stable BPMN identifier of this node, e.g. {@code task_1}. This, not the node name, is how the rest of the
     * graph refers to it: the node name is only whatever escaping the identifier needed to become a JCR name.
     *
     * @return a BPMN element identifier
     */
    @NotNull
    public String getElementId()
    {
        return this.elementId;
    }

    /**
     * The label shown for this node in the editor and in progress displays.
     *
     * @return a label, or {@code null} if the node is unlabelled
     */
    @Nullable
    public String getLabel()
    {
        return this.label;
    }

    /**
     * The vocabulary entry saying what kind of node this is, e.g. {@code MessageStartEvent}. Mandatory in the node
     * type, so this is only {@code null} when the reference dangles.
     *
     * @return a flow node type, or {@code null} if the reference cannot be resolved
     */
    @Nullable
    public FlowNodeType getFlowNodeType()
    {
        return this.getReference(this.flowNodeType, FlowNodeType.class);
    }

    /**
     * The arcs leaving this node, in the order they are stored. Which of them are actually followed is decided by
     * the node itself: an {@link ExclusiveGateway} takes one, a {@link ParallelGateway} takes all of them.
     *
     * @return a list of outgoing sequence flows, empty if this node is an exit point
     */
    @NotNull
    public List<SequenceFlow> getOutgoingFlows()
    {
        return this.getChildren(SequenceFlow.RESOURCE_TYPE, SequenceFlow.class);
    }

    /**
     * The arcs leading into this node, found by asking every node of the owning version which arcs it sends out.
     * A {@link ParallelGateway} join is defined by waiting for a token on each of these, so the engine needs them
     * even though the graph is stored pointing the other way.
     *
     * <p>Storing an arc inside the node it leaves makes walking forwards free and walking backwards a scan of the
     * version. That is the right trade for graphs this size — a workflow has tens of nodes, not thousands — and it
     * keeps one representation of each arc rather than two that can disagree.</p>
     *
     * @return a list of incoming sequence flows, empty if nothing leads here or this node is stored outside a
     *         workflow version
     */
    @NotNull
    public List<SequenceFlow> getIncomingFlows()
    {
        final WorkflowVersion version = this.getWorkflowVersion();
        if (version == null) {
            return List.of();
        }
        return version.getAllFlowNodes().stream()
            .flatMap(node -> node.getOutgoingFlows().stream())
            .filter(flow -> this.elementId.equals(flow.getTargetRef()))
            .toList();
    }

    /**
     * The flow nodes nested inside this one. Only an {@link Activity} has any — the boundary events that watch
     * it — but the graph is walked uniformly, so every node answers this.
     *
     * @return a list of nested flow nodes, empty for all but activities with boundary events
     */
    @NotNull
    public List<FlowNode> getNestedNodes()
    {
        return this.getChildren(RESOURCE_TYPE, FlowNode.class);
    }

    /**
     * The version of the workflow this node belongs to. Found by walking up the tree rather than by taking the
     * parent, since a boundary event sits under its activity rather than directly under the version.
     *
     * @return the owning workflow version, or {@code null} if this node is stored outside one
     */
    @Nullable
    public WorkflowVersion getWorkflowVersion()
    {
        return Stream.iterate(this.resource.getParent(), Objects::nonNull, Resource::getParent)
            .filter(ancestor -> ancestor.isResourceType(WorkflowVersion.RESOURCE_TYPE))
            .findFirst()
            .map(ancestor -> ancestor.adaptTo(WorkflowVersion.class))
            .orElse(null);
    }
}

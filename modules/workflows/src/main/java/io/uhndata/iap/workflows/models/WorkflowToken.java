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

import io.uhndata.iap.entities.models.EntityPart;

/**
 * A Sling Model wrapping a {@code wf:WorkflowToken} node: one branch of an execution, and the single fact of where
 * that branch has got to. Tokens are the whole of a workflow's runtime state — an instance is "at" wherever its
 * tokens are — and they are what decides whether an incoming event may be accepted: an event is only acceptable
 * when a token is resting on a node that catches it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = WorkflowToken.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class WorkflowToken extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code wf:WorkflowToken} node. */
    public static final String RESOURCE_TYPE = "wf/WorkflowToken";

    @ValueMapValue
    private String currentNodeId;

    /**
     * The {@link FlowNode#getElementId() element identifier} of the node this token is resting on. Stored rather
     * than referenced, since it names a node of the workflow definition, not one of the instance.
     *
     * @return a BPMN element identifier
     */
    @NotNull
    public String getCurrentNodeId()
    {
        return this.currentNodeId;
    }

    /**
     * The instance this token belongs to, which is simply its parent.
     *
     * @return the owning workflow instance, or {@code null} if this token is stored outside one
     */
    @Nullable
    public WorkflowInstance getWorkflowInstance()
    {
        return this.getParent(WorkflowInstance.RESOURCE_TYPE, WorkflowInstance.class);
    }

    /**
     * The node this token is resting on, resolved by looking its {@link #getCurrentNodeId() identifier} up in the
     * version its instance is executing.
     *
     * @return the current flow node, or {@code null} if the instance, its version, or the named node cannot be
     *         resolved
     */
    @Nullable
    public FlowNode getCurrentNode()
    {
        return Optional.ofNullable(this.getWorkflowInstance())
            .map(WorkflowInstance::getWorkflowVersion)
            .map(version -> version.getFlowNode(this.currentNodeId))
            .orElse(null);
    }
}

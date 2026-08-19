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

import io.uhndata.iap.content.models.Content;

/**
 * A Sling Model wrapping a {@code wf:WorkflowInstances} node: the container holding the workflows running over one
 * resource, autocreated on anything carrying the {@code wf:WorkflowAttachable} mixin.
 *
 * <p>Workflows live inside the thing they drive rather than being pointed at from it, so that they are found,
 * secured and deleted along with it. Callers normally reach this through the host — {@code
 * Submission.getWorkflowInstances()} — rather than adapting the container itself.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = WorkflowInstances.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class WorkflowInstances extends Content
{
    /** The {@code sling:resourceType} of a {@code wf:WorkflowInstances} node. */
    public static final String RESOURCE_TYPE = "wf/WorkflowInstances";

    /** The name of the container node the {@code wf:WorkflowAttachable} mixin autocreates. */
    public static final String NODE_NAME = "wf:instances";

    /**
     * The workflows running over the host resource, whether still running or finished.
     *
     * @return a list of workflow instances, empty if none has ever been started
     */
    @NotNull
    public List<WorkflowInstance> getInstances()
    {
        return this.getChildren(WorkflowInstance.RESOURCE_TYPE, WorkflowInstance.class);
    }
}

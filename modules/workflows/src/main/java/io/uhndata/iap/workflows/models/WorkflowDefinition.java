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

import io.uhndata.iap.entities.models.Entity;

/**
 * A Sling Model wrapping a {@code wf:WorkflowDefinition} node: a named process whose actual graph lives in its
 * {@link WorkflowVersion versions}. What an instance runs against is always a specific version, never the
 * definition itself.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = WorkflowDefinition.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class WorkflowDefinition extends Entity
{
    /** The {@code sling:resourceType} of a {@code wf:WorkflowDefinition} node. */
    public static final String RESOURCE_TYPE = "wf/WorkflowDefinition";

    @ValueMapValue
    private String title;

    /**
     * The human-readable name of this workflow.
     *
     * @return a title
     */
    @NotNull
    public String getTitle()
    {
        return this.title;
    }

    /**
     * Whether new instances may be created from this workflow at all: exactly whether one of its
     * {@link WorkflowVersion versions} is {@link WorkflowVersion.State#ACTIVE active}.
     *
     * <p>Computed on every call, so it can never disagree with the versions themselves.</p>
     *
     * @return {@code true} if this workflow accepts new instances
     */
    public boolean isActive()
    {
        return this.getActiveVersion() != null;
    }

    /**
     * Every version of this workflow, whatever state each one is in.
     *
     * @return a list of versions, empty if none
     */
    @NotNull
    public List<WorkflowVersion> getVersions()
    {
        return this.getChildren(WorkflowVersion.RESOURCE_TYPE, WorkflowVersion.class);
    }

    /**
     * The version of this workflow that new instances are currently created from. At most one version is expected
     * to be {@link WorkflowVersion.State#ACTIVE active} at a time: promoting a draft retires the version it
     * supersedes, in the same save.
     *
     * @return the active version, or {@code null} if this workflow has none — every version is still a draft, or
     *         the last active one was retired without a replacement
     */
    @Nullable
    public WorkflowVersion getActiveVersion()
    {
        return this.getVersions().stream()
            .filter(WorkflowVersion::isActive)
            .findFirst()
            .orElse(null);
    }
}

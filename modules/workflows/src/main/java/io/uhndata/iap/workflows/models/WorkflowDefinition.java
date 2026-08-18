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

import io.uhndata.iap.entities.models.Entity;

/**
 * A Sling Model wrapping a {@code wf:WorkflowDefinition} node: a named process whose actual graph lives in its
 * {@link WorkflowVersion versions}. Mirrors the way a schema holds its versions, and, in the same way, what an
 * instance runs against is always a specific version rather than the definition itself.
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

    @ValueMapValue
    private boolean active;

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
     * Whether new instances may be created from this workflow at all. Each {@link WorkflowVersion version} carries
     * its own flag as well, and both must be set for a version to accept new instances.
     *
     * @return {@code true} if this workflow accepts new instances
     */
    public boolean isActive()
    {
        return this.active;
    }

    /**
     * Every version of this workflow, whether active or not.
     *
     * @return a list of versions, empty if none
     */
    @NotNull
    public List<WorkflowVersion> getVersions()
    {
        return this.getChildren(WorkflowVersion.RESOURCE_TYPE, WorkflowVersion.class);
    }
}

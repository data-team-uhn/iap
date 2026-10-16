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
package io.uhndata.iap.workflows.internal.checks;

import java.util.Objects;

import org.apache.sling.api.resource.Resource;

import io.uhndata.iap.workflows.models.SystemWorkflowsHomepage;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.spi.ValidityCheck;

/**
 * A rule a version of a system workflow must follow to do a system workflow's job: run the platform's own behaviour
 * to its end inside the request that started it.
 *
 * @version $Id$
 * @since 0.1.0
 */
abstract class SystemWorkflowVersionCheck implements ValidityCheck
{
    @Override
    public boolean appliesTo(final Resource target)
    {
        return target.isResourceType(WorkflowVersion.RESOURCE_TYPE)
            && target.getPath().startsWith(SystemWorkflowsHomepage.HOMEPAGE_PATH + "/");
    }

    /**
     * The version being checked, as a model.
     *
     * @param version a resource this check applies to
     * @return the workflow version model
     */
    protected static WorkflowVersion asVersion(final Resource version)
    {
        return Objects.requireNonNull(version.adaptTo(WorkflowVersion.class), "It applies to workflow versions only");
    }
}

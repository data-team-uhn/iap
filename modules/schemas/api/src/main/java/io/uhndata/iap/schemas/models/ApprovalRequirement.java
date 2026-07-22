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
package io.uhndata.iap.schemas.models;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;

/**
 * A Sling Model wrapping a {@code sch:ApprovalRequirement} node: an approval that must be granted before a
 * submission is accepted, e.g. "REB approval".
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = Requirement.class, resourceType = ApprovalRequirement.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class ApprovalRequirement extends Requirement
{
    /** The {@code sling:resourceType} of a {@code sch:ApprovalRequirement} node. */
    public static final String RESOURCE_TYPE = "sch/ApprovalRequirement";

    @ValueMapValue
    private String approverGroup;

    /**
     * The principal name of the group whose members may grant this approval.
     *
     * @return a group name, or {@code null} if not set
     */
    public String getApproverGroup()
    {
        return this.approverGroup;
    }
}

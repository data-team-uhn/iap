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
package io.uhndata.iap.submissions.models;

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;

import io.uhndata.iap.entities.models.EntityHomepage;

/**
 * A Sling Model wrapping a {@code sub:SubmissionsHomepage} node, the root container of the {@code /Submissions}
 * tree.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = SubmissionsHomepage.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class SubmissionsHomepage extends EntityHomepage
{
    /** The {@code sling:resourceType} of a {@code sub:SubmissionsHomepage} node. */
    public static final String RESOURCE_TYPE = "sub/SubmissionsHomepage";

    /**
     * The submissions filed under this homepage.
     *
     * @return a list of submissions, empty if none
     */
    public List<Submission> getSubmissions()
    {
        return this.getChildren(Submission.RESOURCE_TYPE, Submission.class);
    }
}

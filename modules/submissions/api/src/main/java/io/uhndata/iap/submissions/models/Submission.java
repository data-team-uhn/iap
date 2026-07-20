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

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;

import io.uhndata.iap.entities.models.Entity;

/**
 * A Sling Model wrapping a {@code sub:Submission} node, a research proposal submitted by a researcher against a
 * specific protocol version. It holds the submitter's answers to the protocol questions, the attached documents, and
 * the reviews added by reviewers.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = "sub/Submission",
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Submission extends Entity
{
    @ValueMapValue
    private String title;

    @ValueMapValue
    private String protocolVersion;

    @ValueMapValue
    private String status;

    /**
     * The title of the research proposal.
     *
     * @return a title
     */
    public String getTitle()
    {
        return this.title;
    }

    /**
     * The identifier of the {@code pt:ProtocolVersion} this submission answers.
     *
     * @return an UUID
     */
    public String getProtocolVersion()
    {
        return this.protocolVersion;
    }

    /**
     * The current lifecycle state of the submission, managed by the attached user workflow.
     *
     * @return a status name, e.g. {@code draft} or {@code in-review}
     */
    public String getStatus()
    {
        return this.status;
    }
}

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
 * A Sling Model wrapping a {@code sch:DocumentRequirement} node: a document that the submitter is expected to
 * provide, e.g. the patient consent form, in an expected format described by this requirement.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = DocumentRequirement.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class DocumentRequirement extends Requirement
{
    /** The {@code sling:resourceType} of a {@code sch:DocumentRequirement} node. */
    public static final String RESOURCE_TYPE = "sch/DocumentRequirement";

    @ValueMapValue
    private String[] acceptedFileTypes;

    @ValueMapValue
    private String aiCheckPrompt;

    /**
     * The accepted MIME types for the uploaded document, e.g. {@code application/pdf}.
     *
     * @return a list of MIME types, or {@code null} if not restricted
     */
    public String[] getAcceptedFileTypes()
    {
        return this.acceptedFileTypes;
    }

    /**
     * The prompt for an AI agent that pre-checks whether the document provided by the submitter meets this
     * requirement.
     *
     * @return a prompt, or {@code null} if not set
     */
    public String getAiCheckPrompt()
    {
        return this.aiCheckPrompt;
    }

    /**
     * An optional blank template document offered to submitters.
     *
     * @return the template file resource, or {@code null} if none was provided
     */
    public Resource getTemplate()
    {
        return this.resource.getChild("template");
    }
}

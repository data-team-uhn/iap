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

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.Default;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A Sling Model wrapping a {@code sch:DocumentRequirement} node: a document the submitter provides, such as the patient
 * consent form.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = Requirement.class, resourceType = DocumentRequirement.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class DocumentRequirement extends Requirement
{
    /** The {@code sling:resourceType} of a {@code sch:DocumentRequirement} node. */
    public static final String RESOURCE_TYPE = "sch/DocumentRequirement";

    // Defaulted here too: the node type's default reaches only nodes created through JCR
    @ValueMapValue
    @Default(booleanValues = true)
    private boolean required;

    @ValueMapValue
    private String[] acceptedFileTypes;

    @ValueMapValue
    private String aiCheckPrompt;

    /**
     * Whether the submission is incomplete without this document. An optional document is still asked for.
     *
     * @return {@code true} if the document is required
     */
    public boolean isRequired()
    {
        return this.required;
    }

    /**
     * The media types the document may have, such as {@code application/pdf}.
     *
     * @return an unmodifiable list of media types; empty if any type is accepted
     */
    @NotNull
    public List<String> getAcceptedFileTypes()
    {
        return this.acceptedFileTypes == null ? List.of() : List.of(this.acceptedFileTypes);
    }

    /**
     * The prompt an AI agent is given to check whether the provided document meets this requirement.
     *
     * @return the prompt; {@code null} if none is set
     */
    @Nullable
    public String getAiCheckPrompt()
    {
        return this.aiCheckPrompt;
    }

    /**
     * A template of the document, offered to submitters.
     *
     * @return the {@code nt:file} resource; {@code null} if there is no template
     */
    @Nullable
    public Resource getTemplate()
    {
        return this.resource.getChild("template");
    }

    /**
     * The sections the document is expected to contain.
     *
     * @return the section requirements, in stored order; empty if there are none
     */
    @NotNull
    public List<SectionRequirement> getSections()
    {
        return this.getChildren(SectionRequirement.RESOURCE_TYPE, SectionRequirement.class);
    }
}

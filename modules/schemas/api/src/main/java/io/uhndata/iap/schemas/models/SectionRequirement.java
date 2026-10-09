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
 * A Sling Model wrapping a {@code sch:SectionRequirement} node: a section a {@link DocumentRequirement document} is
 * expected to contain, e.g. the funding section of a study protocol. It lets a {@link Topic} point at the part of a
 * long document worth reading, rather than at the whole document. Nothing is uploaded for it: it is
 * found in the document uploaded for the requirement it belongs to.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = Requirement.class, resourceType = SectionRequirement.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class SectionRequirement extends Requirement
{
    /** The {@code sling:resourceType} of a {@code sch:SectionRequirement} node. */
    public static final String RESOURCE_TYPE = "sch/SectionRequirement";

    // Defaulted here too: the node type's default only reaches nodes created through JCR
    @ValueMapValue
    @Default(booleanValues = true)
    private boolean required;

    @ValueMapValue
    private String[] headings;

    @ValueMapValue
    private String locationPrompt;

    /**
     * Whether the document must contain this section.
     *
     * @return {@code true} if a document without this section falls short of its requirement
     */
    public boolean isRequired()
    {
        return this.required;
    }

    /**
     * The headings this section usually goes by, e.g. "Funding", "Budget" and "Sources of support".
     *
     * @return a list of headings, empty if none are listed
     */
    @NotNull
    public List<String> getHeadings()
    {
        return this.headings == null ? List.of() : List.of(this.headings);
    }

    /**
     * The prompt an LLM is given to find where this section starts, when none of the document's headings is one of
     * the {@link #getHeadings() expected ones}.
     *
     * @return a prompt, or {@code null} if this section is only found by its headings
     */
    @Nullable
    public String getLocationPrompt()
    {
        return this.locationPrompt;
    }

    /**
     * The subsections this section is expected to contain.
     *
     * @return a list of section requirements in their stored order, empty if there are none
     */
    @NotNull
    public List<SectionRequirement> getSections()
    {
        return this.getChildren(RESOURCE_TYPE, SectionRequirement.class);
    }

    /**
     * The document this section is part of, through any number of enclosing sections.
     *
     * @return the document requirement, or {@code null} if this section is not stored under one
     */
    @Nullable
    public DocumentRequirement getDocument()
    {
        final SectionRequirement enclosing = this.getParent(RESOURCE_TYPE, SectionRequirement.class);
        return enclosing == null
            ? this.getParent(DocumentRequirement.RESOURCE_TYPE, DocumentRequirement.class)
            : enclosing.getDocument();
    }
}

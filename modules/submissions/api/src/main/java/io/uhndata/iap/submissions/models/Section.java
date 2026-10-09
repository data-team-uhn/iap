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
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.SectionRequirement;

/**
 * A Sling Model wrapping a {@code sub:Section} node: one section a document is expected to contain, as found in one
 * {@link DocumentVersion revision} of it. A section that was looked for and not found has no passages.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Section.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Section extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code sub:Section} node. */
    public static final String RESOURCE_TYPE = "sub/Section";

    /** The {@link #getMethod() method} of a section found by one of its requirement's headings. */
    public static final String HEADING_METHOD = "heading";

    /** The {@link #getMethod() method} of a section an LLM located. */
    public static final String MODEL_METHOD = "model";

    /** The {@link #getMethod() method} of a section whose heading a person named. */
    public static final String PICKED_HEADING_METHOD = "pickedHeading";

    /** The {@link #getMethod() method} of a section whose text a person selected. */
    public static final String SELECTED_TEXT_METHOD = "selectedText";

    @ValueMapValue
    private String fulfills;

    @ValueMapValue
    private String method;

    @ValueMapValue
    private Double confidence;

    @ValueMapValue
    private String checkedBy;

    /**
     * The section requirement this section fulfills.
     *
     * @return a section requirement, or {@code null} if not set or unresolvable
     */
    @Nullable
    public SectionRequirement getFulfills()
    {
        return this.getReference(this.fulfills, SectionRequirement.class);
    }

    /**
     * How the passages were found: one of the {@code *_METHOD} constants of this class.
     *
     * @return a method, or {@code null} if the section was not found
     */
    @Nullable
    public String getMethod()
    {
        return this.method;
    }

    /**
     * How sure the model that found this section is, from 0 to 1.
     *
     * @return a confidence, or {@code null} unless an LLM located the section
     */
    @Nullable
    public Double getConfidence()
    {
        return this.confidence;
    }

    /**
     * The person who checked where the section is: who picked or selected it, or who confirmed that a section not
     * found is indeed missing.
     *
     * @return a user id, or {@code null} if nobody has checked
     */
    @Nullable
    public String getCheckedBy()
    {
        return this.checkedBy;
    }

    /**
     * The stretches of the document this section consists of, in the order the document presents them.
     *
     * @return a list of passages, empty if the section was not found
     */
    @NotNull
    public List<Passage> getPassages()
    {
        return this.getChildren(Passage.RESOURCE_TYPE, Passage.class);
    }

    /**
     * Whether the section was found in its revision at all.
     *
     * @return {@code true} if it has at least one passage
     */
    public boolean isFound()
    {
        return !this.getPassages().isEmpty();
    }

    /**
     * Whether a person had to select the section's text, because no heading holds it. The document may then not
     * follow the expected protocol.
     *
     * @return {@code true} if the passages were selected by hand
     */
    public boolean isPotentialDeviation()
    {
        return SELECTED_TEXT_METHOD.equals(this.method);
    }
}

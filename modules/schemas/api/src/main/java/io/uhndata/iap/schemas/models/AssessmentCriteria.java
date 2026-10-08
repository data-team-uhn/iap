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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.entities.models.EntityPart;

/**
 * A Sling Model wrapping a {@code sch:AssessmentCriteria} node: something the approvers want assessed before they
 * grant an {@link ApprovalRequirement approval}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = AssessmentCriteria.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class AssessmentCriteria extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code sch:AssessmentCriteria} node. */
    public static final String RESOURCE_TYPE = "sch/AssessmentCriteria";

    @ValueMapValue
    private String text;

    @ValueMapValue
    private String description;

    @ValueMapValue
    private String assessmentPrompt;

    @ValueMapValue
    private String[] sources;

    /**
     * The criteria as shown to the submitter.
     *
     * @return the criteria text
     */
    @NotNull
    public String getText()
    {
        return this.text;
    }

    /**
     * An optional longer explanation displayed to the submitter.
     *
     * @return a description, or {@code null} if not set
     */
    @Nullable
    public String getDescription()
    {
        return this.description;
    }

    /**
     * The prompt an LLM is given to assess these criteria from the submission.
     *
     * @return a prompt, or {@code null} if these criteria are not assessed by an LLM
     */
    @Nullable
    public String getAssessmentPrompt()
    {
        return this.assessmentPrompt;
    }

    /**
     * The requirements and questions that bear on these criteria. They may be of any requirement kind, so they are
     * read as {@link EntityPart}s. The link is weak, so one removed since is skipped.
     *
     * @return a list of schema parts, empty if none are linked or none of them resolve
     */
    @NotNull
    public List<EntityPart> getSources()
    {
        if (this.sources == null) {
            return List.of();
        }
        return Arrays.stream(this.sources)
            .map(identifier -> this.getReference(identifier, EntityPart.class))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }
}

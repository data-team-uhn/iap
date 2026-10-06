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

import java.util.ArrayList;
import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.entities.models.EntityPart;

/**
 * A Sling Model wrapping a {@code sch:Risk} node: a risk the approvers want assessed before they grant an
 * {@link ApprovalRequirement approval}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Risk.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Risk extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code sch:Risk} node. */
    public static final String RESOURCE_TYPE = "sch/Risk";

    @ValueMapValue
    private String text;

    @ValueMapValue
    private String description;

    @ValueMapValue
    private String purpose;

    @ValueMapValue
    private String assessmentPrompt;

    @ValueMapValue
    private String[] questions;

    /**
     * The risk as shown to the submitter.
     *
     * @return the risk text
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
     * What the assessment is used to judge, in the reviewer's terms rather than the submitter's.
     *
     * @return a purpose, or {@code null} if not stated
     */
    @Nullable
    public String getPurpose()
    {
        return this.purpose;
    }

    /**
     * The prompt an LLM is given to assess this risk from the submitted documents.
     *
     * @return a prompt, or {@code null} if this risk is not assessed by an LLM
     */
    @Nullable
    public String getAssessmentPrompt()
    {
        return this.assessmentPrompt;
    }

    /**
     * The questions whose answers bear on this risk. The link is weak, so a question removed since is skipped.
     *
     * @return a list of questions, empty if none are linked or none of them resolve
     */
    @NotNull
    public List<Question> getQuestions()
    {
        if (this.questions == null) {
            return List.of();
        }
        final List<Question> result = new ArrayList<>();
        for (final String identifier : this.questions) {
            final Question question = this.getReference(identifier, Question.class);
            if (question != null) {
                result.add(question);
            }
        }
        return result;
    }
}

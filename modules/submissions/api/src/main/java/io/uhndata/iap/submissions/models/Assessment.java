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
import io.uhndata.iap.schemas.models.AssessmentCriteria;

/**
 * A Sling Model wrapping a {@code sub:Assessment} node: one assessment of a single schema {@link AssessmentCriteria},
 * in a reviewer's {@link Review} or in the AI's {@link Screening}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Assessment.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Assessment extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code sub:Assessment} node. */
    public static final String RESOURCE_TYPE = "sub/Assessment";

    @ValueMapValue
    private String assessmentCriteria;

    @ValueMapValue
    private String value;

    @ValueMapValue
    private String summary;

    @ValueMapValue
    private String[] sources;

    @ValueMapValue
    private Double confidence;

    /**
     * The criteria this assesses.
     *
     * @return the criteria, or {@code null} if not set or unresolvable
     */
    @Nullable
    public AssessmentCriteria getAssessmentCriteria()
    {
        return this.getReference(this.assessmentCriteria, AssessmentCriteria.class);
    }

    /**
     * Whether the reviewer has looked at the criteria for this assessment.
     *
     * @return the assessment, or {@code null} if not yet assessed
     */
    @Nullable
    public String getValue()
    {
        return this.value;
    }

    /**
     * A short summary of this assessment.
     *
     * @return the summary, or {@code null} if none was given
     */
    @Nullable
    public String getSummary()
    {
        return this.summary;
    }

    /**
     * What the assessment read: document revisions and answers. A newer revision or a changed answer makes it
     * stale. The link is weak, so one removed since is skipped.
     *
     * @return the parts of the submission read, empty if none are recorded or none of them resolve
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

    /**
     * How sure an automated assessment is.
     *
     * @return a value between 0 and 1, or {@code null} if not given
     */
    @Nullable
    public Double getConfidence()
    {
        return this.confidence;
    }

    /**
     * The comments raised about these criteria, the AI's concerns among them, in the order they were raised.
     *
     * @return a list of comments, empty if none
     */
    @NotNull
    public List<Comment> getComments()
    {
        return this.getChildren(Comment.RESOURCE_TYPE, Comment.class);
    }
}

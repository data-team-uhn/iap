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
import io.uhndata.iap.schemas.models.Question;

/**
 * A Sling Model wrapping a {@code sub:Answer} node: the answer to a single schema question. Only simple storage;
 * the value's expected type and meaning are dictated by the referenced question.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Answer.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Answer extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code sub:Answer} node. */
    public static final String RESOURCE_TYPE = "sub/Answer";

    @ValueMapValue
    private String question;

    @ValueMapValue
    private String[] value;

    @ValueMapValue
    private boolean extracted;

    @ValueMapValue
    private String extractedAnswer;

    @ValueMapValue
    private Double confidence;

    @ValueMapValue
    private String reasoning;

    @ValueMapValue
    private Long editDistance;

    @ValueMapValue
    private Double percentageDistance;

    /**
     * The question this answers.
     *
     * @return a question, or {@code null} if not set or unresolvable
     */
    @Nullable
    public Question getQuestion()
    {
        return this.getReference(this.question, Question.class);
    }

    /**
     * The submitted value(s).
     *
     * @return a copy of the stored value(s), or {@code null} if not yet answered
     */
    @Nullable
    public String[] getValue()
    {
        // A copy, since arrays are mutable and callers must not be able to alter the model's own state
        return this.value == null ? null : this.value.clone();
    }

    /**
     * Whether extraction ran and produced an answer. This tells "ran and found nothing" apart from "never ran",
     * which a null {@link #getExtractedAnswer() extracted answer} on its own cannot.
     *
     * @return {@code true} if an extracted answer was written, even an empty one
     */
    public boolean isExtracted()
    {
        return this.extracted;
    }

    /**
     * The answer a model read out of the submitted documents, as opposed to the value the submitter provided.
     *
     * @return an extracted answer, or {@code null} if extraction never ran or found nothing
     */
    @Nullable
    public String getExtractedAnswer()
    {
        return this.extractedAnswer;
    }

    /**
     * How sure the model is of the extracted answer, from 0 to 1.
     *
     * @return a confidence, or {@code null} if extraction never ran
     */
    @Nullable
    public Double getConfidence()
    {
        return this.confidence;
    }

    /**
     * The model's explanation of how it arrived at the extracted answer.
     *
     * @return an explanation, or {@code null} if extraction never ran
     */
    @Nullable
    public String getReasoning()
    {
        return this.reasoning;
    }

    /**
     * How far the extracted answer is from the submitter's value, as a raw edit distance.
     *
     * @return an edit distance, {@code -1} when there is no extracted answer to compare against, or {@code null}
     *         if the two were never compared
     */
    @Nullable
    public Long getEditDistance()
    {
        return this.editDistance;
    }

    /**
     * How far the extracted answer is from the submitter's value, as a percentage of the extracted answer's own
     * length.
     *
     * @return a percentage, {@code -1} when there is no extracted answer to compare against, or {@code null} if
     *         the two were never compared
     */
    @Nullable
    public Double getPercentageDistance()
    {
        return this.percentageDistance;
    }

    /**
     * The passages backing the extracted answer.
     *
     * @return a list of evidence, empty if the extraction cited nothing
     */
    @NotNull
    public List<Evidence> getEvidence()
    {
        return this.getChildren(Evidence.RESOURCE_TYPE, Evidence.class);
    }
}

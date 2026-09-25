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
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A Sling Model wrapping a {@code sch:Question} node: a single question the submitter must answer.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = FormItem.class, resourceType = Question.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Question extends FormItem
{
    /** The {@code sling:resourceType} of a {@code sch:Question} node. */
    public static final String RESOURCE_TYPE = "sch/Question";

    @ValueMapValue
    private String text;

    @ValueMapValue
    private String description;

    @ValueMapValue
    private String dataType;

    @ValueMapValue
    private Long minAnswers;

    @ValueMapValue
    private Long maxAnswers;

    // Legacy flags, read only when the counts are absent
    @ValueMapValue
    private boolean required;

    @ValueMapValue
    private boolean multiple;

    @ValueMapValue
    private Double minValue;

    @ValueMapValue
    private Double maxValue;

    @ValueMapValue
    private String pattern;

    @ValueMapValue
    private String patternMessage;

    @ValueMapValue
    private String optionsFrom;

    @ValueMapValue
    private String displayMode;

    @ValueMapValue
    private String purpose;

    @ValueMapValue
    private String extractionPrompt;

    @ValueMapValue
    private String responseShape;

    @ValueMapValue
    private String[] rubricTags;

    /**
     * The question text shown to the submitter.
     *
     * @return the question text
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
     * The expected answer type.
     *
     * @return a data type name, e.g. {@code text}, {@code long}, {@code boolean}, {@code file}
     */
    @NotNull
    public String getDataType()
    {
        return this.dataType;
    }

    /**
     * The fewest values an answer must give; {@code 0} or less asks for nothing. Falls back to the legacy
     * {@code required} flag on questions written before the counts existed.
     *
     * @return the minimum number of values
     */
    public long getMinAnswers()
    {
        if (this.minAnswers != null) {
            return this.minAnswers;
        }
        return this.required ? 1 : 0;
    }

    /**
     * The most values an answer may give; {@code 0} or less allows any number. Falls back to the legacy
     * {@code multiple} flag on questions written before the counts existed.
     *
     * @return the maximum number of values
     */
    public long getMaxAnswers()
    {
        if (this.maxAnswers != null) {
            return this.maxAnswers;
        }
        return this.multiple ? 0 : 1;
    }

    /**
     * Whether an answer must be provided before submitting: a reading of {@link #getMinAnswers()}.
     *
     * @return {@code true} if at least one value is required
     */
    public boolean isRequired()
    {
        return getMinAnswers() > 0;
    }

    /**
     * Whether more than one value may be provided: a reading of {@link #getMaxAnswers()}.
     *
     * @return {@code true} if multiple values are allowed
     */
    public boolean isMultiple()
    {
        return getMaxAnswers() != 1;
    }

    /**
     * For numeric answers, the smallest value accepted.
     *
     * @return the smallest accepted value, or {@code null} when unbounded
     */
    @Nullable
    public Double getMinValue()
    {
        return this.minValue;
    }

    /**
     * For numeric answers, the largest value accepted.
     *
     * @return the largest accepted value, or {@code null} when unbounded
     */
    @Nullable
    public Double getMaxValue()
    {
        return this.maxValue;
    }

    /**
     * For text answers, a regular expression every value must match in full.
     *
     * @return the pattern, or {@code null} when anything is accepted
     */
    @Nullable
    public String getPattern()
    {
        return this.pattern;
    }

    /**
     * What the submitter is told when a value does not match {@link #getPattern() the pattern}.
     *
     * @return the message, or {@code null} when none is configured
     */
    @Nullable
    public String getPatternMessage()
    {
        return this.patternMessage;
    }

    /**
     * A content path whose live items are the answers this question offers, in place of child
     * {@link AnswerOption} nodes.
     *
     * @return an absolute repository path, or {@code null} when the options, if any, are declared as children
     */
    @Nullable
    public String getOptionsFrom()
    {
        return this.optionsFrom;
    }

    /**
     * The answers this question offers as declared child nodes, in the order they are declared. A question
     * offering none is answered freely, in whatever its {@link #getDataType() data type} accepts.
     *
     * @return the declared options, an empty list if there are none
     */
    @NotNull
    public List<AnswerOption> getOptions()
    {
        return this.getChildren(AnswerOption.RESOURCE_TYPE, AnswerOption.class);
    }

    /**
     * How the question is rendered to the submitter.
     *
     * @return a display mode, or {@code null} to let the answer's data type decide
     */
    @Nullable
    public String getDisplayMode()
    {
        return this.displayMode;
    }

    /**
     * What the answer is used to judge, in the reviewer's terms rather than the submitter's.
     *
     * @return a purpose, or {@code null} if not stated
     */
    @Nullable
    public String getPurpose()
    {
        return this.purpose;
    }

    /**
     * The prompt an LLM is given to read this answer out of the submitted documents.
     *
     * @return a prompt, or {@code null} if this answer is not extracted
     */
    @Nullable
    public String getExtractionPrompt()
    {
        return this.extractionPrompt;
    }

    /**
     * The shape the LLM's reply must take, as a JSON schema.
     *
     * @return a JSON schema, or {@code null} if the reply is unconstrained
     */
    @Nullable
    public String getResponseShape()
    {
        return this.responseShape;
    }

    /**
     * The tags of the schema sections that may contain the answer. Chunk selection for extraction is tag-driven:
     * the union of the extracted questions' tags decides which chunks a model is given.
     *
     * @return a list of tags, empty if none were assigned
     */
    @NotNull
    public List<String> getRubricTags()
    {
        return this.rubricTags == null ? List.of() : List.of(this.rubricTags);
    }
}

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
    @Default(longValues = 0)
    private long minAnswers;

    // Defaulted here as well as in the node type: the CND default only reaches nodes created through JCR, and a
    // model reading an absent maximum as 0 would turn "one value" into "any number of values"
    @ValueMapValue
    @Default(longValues = 1)
    private long maxAnswers;

    @ValueMapValue
    private Double minValue;

    @ValueMapValue
    private Double maxValue;

    @ValueMapValue
    private String pattern;

    @ValueMapValue
    private String patternMessage;

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
     * The fewest values an answer must give, {@code 0} or a negative number asking for nothing. Falling short is
     * incompleteness rather than an error: answers are saved as they are given, so this is reported by the
     * completeness marking instead of blocking a save.
     *
     * @return the minimum number of values
     */
    public long getMinAnswers()
    {
        return this.minAnswers;
    }

    /**
     * The most values an answer may give, {@code 0} or a negative number allowing any amount. Unlike the minimum
     * this is enforced when answers are saved, because no form offers a way to exceed it.
     *
     * @return the maximum number of values
     */
    public long getMaxAnswers()
    {
        return this.maxAnswers;
    }

    /**
     * Whether an answer must be provided before submitting: a reading of {@link #getMinAnswers()}, not a fact of
     * its own, so the two can never disagree.
     *
     * @return {@code true} if at least one value is required
     */
    public boolean isRequired()
    {
        return this.minAnswers > 0;
    }

    /**
     * Whether more than one value may be provided: a reading of {@link #getMaxAnswers()}, not a fact of its own,
     * so the two can never disagree.
     *
     * @return {@code true} if multiple values are allowed
     */
    public boolean isMultiple()
    {
        return this.maxAnswers != 1;
    }

    /**
     * For numeric answers, the smallest value accepted. A hard bound: a save giving less is refused.
     *
     * @return the smallest accepted value, or {@code null} when unbounded
     */
    @Nullable
    public Double getMinValue()
    {
        return this.minValue;
    }

    /**
     * For numeric answers, the largest value accepted. A hard bound: a save giving more is refused.
     *
     * @return the largest accepted value, or {@code null} when unbounded
     */
    @Nullable
    public Double getMaxValue()
    {
        return this.maxValue;
    }

    /**
     * For text answers, a regular expression every given value must match in full; a save that does not is
     * refused.
     *
     * @return the pattern, or {@code null} when anything is accepted
     */
    @Nullable
    public String getPattern()
    {
        return this.pattern;
    }

    /**
     * What the submitter is told when a value does not match {@link #getPattern() the pattern}. Without one the
     * refusal quotes the pattern itself, which only its author can read.
     *
     * @return the message, or {@code null} when none is configured
     */
    @Nullable
    public String getPatternMessage()
    {
        return this.patternMessage;
    }

    /**
     * The answers this question offers, in the order they are declared.
     *
     * <p>
     * A question offering none is answered freely, in whatever its {@link #getDataType() data type} accepts; one
     * offering options is answered only with their values. That is the difference that lets a condition compare
     * against an agreed value rather than against whatever a submitter typed.
     * </p>
     *
     * @return the offered options, an empty list if the question is answered freely
     */
    @NotNull
    public List<AnswerOption> getOptions()
    {
        return this.getChildren(AnswerOption.RESOURCE_TYPE, AnswerOption.class);
    }
}

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

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.Default;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.utils.Referenceable;

/**
 * A Sling Model wrapping a {@code sch:Question} node: a question the submitter answers.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = FormItem.class, resourceType = Question.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Question extends FormItem implements Referenceable
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
    private long minAnswers;

    // Defaulted here too: the node type's default reaches only nodes created through JCR. An absent maximum
    // read as 0 would turn "one value" into "any number of values".
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

    /**
     * The question, as the submitter reads it.
     *
     * @return the text
     */
    @NotNull
    public String getText()
    {
        return this.text;
    }

    /**
     * A longer explanation the submitter reads under the question.
     *
     * @return the description; {@code null} if there is none
     */
    @Nullable
    public String getDescription()
    {
        return this.description;
    }

    /**
     * The type of value an answer holds.
     *
     * @return one of {@code text}, {@code long}, {@code double}, {@code boolean} and {@code date}
     */
    @NotNull
    public String getDataType()
    {
        return this.dataType;
    }

    /**
     * The fewest values an answer must give.
     *
     * @return the minimum number of values; {@code 0} or less if the question is optional
     */
    public long getMinAnswers()
    {
        return this.minAnswers;
    }

    /**
     * The most values an answer may give.
     *
     * @return the maximum number of values; {@code 0} or less if any number is allowed
     */
    public long getMaxAnswers()
    {
        return this.maxAnswers;
    }

    /**
     * Whether the question must be answered, which a {@link #getMinAnswers() minimum} above zero means.
     *
     * @return {@code true} if at least one value is required
     */
    public boolean isRequired()
    {
        return getMinAnswers() > 0;
    }

    /**
     * Whether an answer may give several values, which a {@link #getMaxAnswers() maximum} other than 1 means.
     *
     * @return {@code true} if several values are allowed
     */
    public boolean isMultiple()
    {
        return getMaxAnswers() != 1;
    }

    /**
     * For a numeric answer, the smallest value accepted.
     *
     * @return the smallest value; {@code null} if there is no lower bound
     */
    @Nullable
    public Double getMinValue()
    {
        return this.minValue;
    }

    /**
     * For a numeric answer, the largest value accepted.
     *
     * @return the largest value; {@code null} if there is no upper bound
     */
    @Nullable
    public Double getMaxValue()
    {
        return this.maxValue;
    }

    /**
     * For a text answer, a regular expression every value must match in full.
     *
     * @return the pattern; {@code null} if any text is accepted
     */
    @Nullable
    public String getPattern()
    {
        return this.pattern;
    }

    /**
     * What the submitter is told when a value does not match {@link #getPattern() the pattern}.
     *
     * @return the message; {@code null} if none is set
     */
    @Nullable
    public String getPatternMessage()
    {
        return this.patternMessage;
    }

    /**
     * A content path whose items are the options, in place of child {@link AnswerOption} nodes.
     *
     * @return an absolute path, such as {@code /Vocabularies/sites}; {@code null} if the question takes no options
     *         from elsewhere
     */
    @Nullable
    public String getOptionsFrom()
    {
        return this.optionsFrom;
    }

    /**
     * The options this question holds as children. A question with options is answered only with their values.
     *
     * @return the options, in stored order; empty if there are none
     */
    @NotNull
    public List<AnswerOption> getOptions()
    {
        return this.getChildren(AnswerOption.RESOURCE_TYPE, AnswerOption.class);
    }

    /**
     * How the question is shown to the submitter.
     *
     * @return a display mode; {@code null} if none is set
     */
    @Nullable
    public String getDisplayMode()
    {
        return this.displayMode;
    }

    /**
     * What the answer is used to judge, in the reviewer's terms.
     *
     * @return the purpose; {@code null} if none is set
     */
    @Nullable
    public String getPurpose()
    {
        return this.purpose;
    }

    /**
     * The prompt an LLM is given to read this answer out of the submitted documents.
     *
     * @return the prompt; {@code null} if this answer is not read out of documents
     */
    @Nullable
    public String getExtractionPrompt()
    {
        return this.extractionPrompt;
    }

    /**
     * The shape the LLM's reply must take, as a JSON schema.
     *
     * @return the JSON schema; {@code null} if none is set
     */
    @Nullable
    public String getResponseShape()
    {
        return this.responseShape;
    }

    @Override
    @NotNull
    public String getIdentifier()
    {
        return this.identifier;
    }

    /**
     * The answers this question offers, as the model is shown them and as its replies are matched against.
     *
     * <p>Read from {@link #getOptionsFrom()} when that path is set, otherwise from the declared
     * {@link #getOptions() children}, sorted by {@code defaultOrder}. The submission form lists
     * {@link #getOptions()} instead.</p>
     *
     * @return the offered options, an empty list if the question has none
     */
    @NotNull
    public List<OfferedOption> getOfferedOptions()
    {
        if (this.optionsFrom != null && !this.optionsFrom.isBlank()) {
            return OptionCatalog.read(this.resource.getResourceResolver(), this.optionsFrom);
        }
        // A stable sort, so options never numbered keep the order they are stored in
        return getOptions().stream()
            .sorted(Comparator.comparingLong(AnswerOption::getDefaultOrder))
            .map(option -> new OfferedOption(option.getValue(), option.getLabel(),
                Objects.requireNonNullElse(option.getDescription(), "")))
            .toList();
    }
}

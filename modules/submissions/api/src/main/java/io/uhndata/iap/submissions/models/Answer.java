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
import java.util.function.Predicate;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.OfferedOption;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.utils.Referenceable;

/**
 * A Sling Model wrapping a {@code sub:Answer} node: the answer to one question of the schema. The question says what
 * type the value has and what it means.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Answer.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Answer extends EntityPart implements Referenceable
{
    /** The {@code sling:resourceType} of a {@code sub:Answer} node. */
    public static final String RESOURCE_TYPE = "sub/Answer";

    @ValueMapValue
    private String question;

    @ValueMapValue
    private String[] value;

    /**
     * The question this answers.
     *
     * @return the question; {@code null} if none is set, or this session cannot resolve it
     */
    @Nullable
    public Question getQuestion()
    {
        return this.getReference(this.question, Question.class);
    }

    /**
     * The value or values the submitter typed or accepted. An extracted answer is not one of them until the
     * submitter accepts or edits it.
     *
     * @return a copy of the stored values; {@code null} if the answer holds no value
     */
    @Nullable
    public String[] getValue()
    {
        return this.value == null ? null : this.value.clone();
    }

    /**
     * Every extraction run against this answer, in the order they ran.
     *
     * @return the runs; empty if extraction has never run
     */
    @NotNull
    public List<Extraction> getExtractions()
    {
        return this.getChildren(Extraction.RESOURCE_TYPE, Extraction.class);
    }

    /**
     * Returns the newest extraction run. The submitter is shown this run, and the review step and the suggestion
     * distance use it too, so they all mean the same run. Older runs are kept but not shown.
     *
     * @return the newest run; {@code null} if extraction has never run
     */
    @Nullable
    public Extraction getLatestExtraction()
    {
        final List<Extraction> extractions = this.getExtractions();
        return extractions.isEmpty() ? null : extractions.get(extractions.size() - 1);
    }

    @Override
    @NotNull
    public String getIdentifier()
    {
        return this.identifier;
    }

    /**
     * Returns the newest run's suggestion, as the values this answer would hold if the submitter took it as it is.
     *
     * <p>For a question that takes several values, the model gives them as one comma-separated string, so
     * "St Michael's, Sunnybrook" is two values. The reply is read with {@link #readAnswer}, so the suggestion can
     * be compared with saved values.</p>
     *
     * @return the suggested values, empty when there is no run or the newest run found nothing
     */
    @NotNull
    public List<String> getSuggestedValues()
    {
        final Extraction latest = getLatestExtraction();
        final String suggested = latest == null ? null : latest.getExtractedAnswer();
        if (suggested == null) {
            return List.of();
        }
        return readAnswer(suggested, getQuestion());
    }

    /**
     * Returns the submitter's values, or, when they gave none, the newest run's suggestion. A workflow step that
     * must act on a reading before anyone checks it reads this, for example to route on whether a document is a
     * proposal. The form and form conditions read {@link #getValue()} instead.
     *
     * @return the non-blank values given, or else the suggested values; empty when there is neither
     */
    @NotNull
    public List<String> getValueOrSuggestion()
    {
        final List<String> given = this.value == null ? List.of()
            : Arrays.stream(this.value).filter(each -> !each.isBlank()).toList();
        return given.isEmpty() ? getSuggestedValues() : given;
    }

    /**
     * Reads one reply from the model as values for a question. The reply is split by {@link #splitAnswer}. When
     * the question offers options, each part is matched to an option by value or label, ignoring case: a model
     * writes an option's label, "English", but the saved value is the option's value, "english". A part that
     * matches no option is kept as the model wrote it, so the submitter can still see what it said.
     *
     * <p>The suggestion and the classification step's check of its pick both use this, so they read a reply the
     * same way.</p>
     *
     * @param answer the model's reply
     * @param question the question it answers, or {@code null} to read the reply as one value with no options
     * @return the values
     */
    @NotNull
    public static List<String> readAnswer(@NotNull final String answer, @Nullable final Question question)
    {
        if (question == null) {
            return splitAnswer(answer, false);
        }
        final List<OfferedOption> options = question.getOfferedOptions();
        return splitAnswer(answer, question.isMultiple()).stream()
            .map(value -> matchOption(value, options))
            .toList();
    }

    /** Returns the value of the option a reply part names, by value first, then by label; or else the part. */
    private static String matchOption(final String value, final List<OfferedOption> options)
    {
        final String byValue = findUniqueMatch(options, option -> value.equalsIgnoreCase(option.value()));
        if (byValue != null) {
            return byValue;
        }
        final String byLabel = findUniqueMatch(options, option -> value.equalsIgnoreCase(option.label()));
        return byLabel != null ? byLabel : value;
    }

    /**
     * Returns the value of the one option matching {@code test}, or {@code null} when none matches or options
     * with different values match.
     *
     * <p>Nothing stops two options from having the same label, for example two categories in an editable tree.
     * The reply cannot tell them apart, so a tie counts as no match: guessing could save the wrong category,
     * while the model's own words are easy for a person to correct.</p>
     */
    private static String findUniqueMatch(final List<OfferedOption> options, final Predicate<OfferedOption> test)
    {
        String match = null;
        for (final OfferedOption option : options) {
            if (test.test(option)) {
                if (match != null && !match.equals(option.value())) {
                    return null;
                }
                match = option.value();
            }
        }
        return match;
    }

    /**
     * Splits one reply from the model into values: trimmed, and for a question that takes several values, split
     * on commas.
     *
     * <p>Keep this the only place a reply is split. If two readers split it differently, a suggestion taken as it
     * is could be measured as an edit.</p>
     *
     * <p>A reply that is blank, or only commas and spaces, is kept whole, so what the model said is not lost.</p>
     *
     * @param answer the model's reply, as it came
     * @param multiple whether the question takes more than one value
     * @return the values, never empty
     */
    @NotNull
    public static List<String> splitAnswer(@NotNull final String answer, final boolean multiple)
    {
        if (!multiple) {
            final String value = answer.strip();
            return List.of(value.isEmpty() ? answer : value);
        }
        final List<String> values = Arrays.stream(answer.split(","))
            .map(String::strip)
            .filter(part -> !part.isEmpty())
            .toList();
        return values.isEmpty() ? List.of(answer) : values;
    }
}

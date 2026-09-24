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

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.OfferedOption;
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
     * The submitted value(s): what the submitter typed or approved. An extracted answer is not one of them until
     * the submitter accepts or edits it, so that no answer is ever attributed to a submitter who never saw it.
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
     * Every extraction run against this question, in the order they ran.
     *
     * @return a list of runs, empty if extraction has never run
     */
    @NotNull
    public List<Extraction> getExtractions()
    {
        return this.getChildren(Extraction.RESOURCE_TYPE, Extraction.class);
    }

    /**
     * The most recent extraction run, which is the one a submitter is shown.
     *
     * @return the newest run, or {@code null} if extraction has never run
     */
    @Nullable
    public Extraction getLatestExtraction()
    {
        final List<Extraction> extractions = this.getExtractions();
        return extractions.isEmpty() ? null : extractions.get(extractions.size() - 1);
    }

    /**
     * The extraction run the form shows, and so the one a submitter reviews: whichever was surest of itself.
     *
     * <p>Here rather than in whoever happens to need it, because two places need it - the projection that shows
     * a suggestion, and the handler that records what the submitter said about it. Two copies of this rule would
     * mean the form could show one run while the verdict landed on another, and nothing would say so: both would
     * work, and the confidence the submitter vouched for would not be the one that was stored.</p>
     *
     * <p>Ties go to the earlier run, since the first is as good an answer as any and picking consistently is the
     * whole point.</p>
     *
     * @return the surest run, or {@code null} if extraction has never run
     */
    @Nullable
    public Extraction getSurestExtraction()
    {
        Extraction best = null;
        for (final Extraction extraction : this.getExtractions()) {
            if (best == null || confidenceOf(extraction) > confidenceOf(best)) {
                best = extraction;
            }
        }
        return best;
    }

    /** How sure a run was, with a run that did not say counting as not sure at all. */
    private static double confidenceOf(final Extraction extraction)
    {
        final Double confidence = extraction.getConfidence();
        return confidence == null ? 0.0 : confidence;
    }

    /**
     * What the extraction suggested, as the values this answer would hold if nobody touched it.
     *
     * <p>Not the model's reply as it came: a question that takes several values is asked for them as one
     * comma-separated string, so a suggestion of "St Michael's, Sunnybrook" is two values and not one. The form
     * compares this against the live answer to tell an untouched suggestion from a corrected one, and comparing
     * the unsplit string against two stored values reported every multi-valued answer as corrected.</p>
     *
     * @return the suggested values, empty when nothing suggested an answer here
     */
    @NotNull
    public List<String> getSuggestedValues()
    {
        final Extraction surest = getSurestExtraction();
        final String suggested = surest == null ? null : surest.getExtractedAnswer();
        if (suggested == null) {
            return List.of();
        }
        return readAnswer(suggested, getQuestion());
    }

    /**
     * One reply from the model, as the values to store for a question: split by {@link #splitAnswer}, and for a
     * question that offers options, each part matched to an option. A model names an option the way a person
     * reads it - "English" - while what is stored and compared is the option's value, "english". A part that
     * matches no option is kept as the model gave it, so what it said is still there to see.
     *
     * <p>Shared by what the extraction writes down and what the form shows as the suggestion, for the reason
     * {@link #splitAnswer} gives.</p>
     *
     * @param answer the model's reply
     * @param question the question it answers, or {@code null} when it can no longer be read
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

    /** The value of the option a reply part names, by value or by label, ignoring case; or the part itself. */
    private static String matchOption(final String value, final List<OfferedOption> options)
    {
        for (final OfferedOption option : options) {
            if (value.equalsIgnoreCase(option.value()) || value.equalsIgnoreCase(option.label())) {
                return option.value();
            }
        }
        return value;
    }

    /**
     * One reply from the model, as the values it stands for.
     *
     * <p>The one place this rule lives, because two places need it and they must not drift: what the extraction
     * writes down and what the form shows as the suggestion have to be the same list, or a submitter is told they
     * corrected an answer they never touched.</p>
     *
     * <p>A reply that splits into nothing is kept whole. That is a model that answered with punctuation only, and
     * storing the question as unanswered would lose what it did say.</p>
     *
     * @param answer the model's reply, as it came
     * @param multiple whether the question takes more than one value
     * @return the values, never empty
     */
    @NotNull
    public static List<String> splitAnswer(@NotNull final String answer, final boolean multiple)
    {
        if (!multiple) {
            return List.of(answer);
        }
        final List<String> values = Arrays.stream(answer.split(","))
            .map(String::strip)
            .filter(part -> !part.isEmpty())
            .toList();
        return values.isEmpty() ? List.of(answer) : values;
    }
}

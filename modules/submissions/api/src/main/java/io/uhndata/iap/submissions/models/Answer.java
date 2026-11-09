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
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.entities.models.EntityPart;
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
     * Returns the newest extraction run. Its suggestion is the one the form can offer (see
     * {@link #getOfferedSuggestion()}), and the review step and the suggestion distance use this run too, so they
     * all mean the same run. Older runs are kept but not shown.
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
     * @return the suggested values, empty when there is no run or the newest run found nothing
     */
    @NotNull
    public List<String> getSuggestedValues()
    {
        final Extraction latest = getLatestExtraction();
        return latest == null ? List.of() : latest.getExtractedValues();
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
        final List<String> given = getGivenValues();
        return given.isEmpty() ? getSuggestedValues() : given;
    }

    /**
     * Returns the suggestion the form shows next to this answer, for the submitter to take or leave.
     *
     * <ul>
     * <li>No value from the submitter yet: the newest run's suggestion.</li>
     * <li>A value: the newest run's suggestion only when it differs from both that value and the run before it.
     * The answer stays as it is; the new suggestion is shown beside it so the submitter can choose. A suggestion
     * equal to the answer has nothing to offer, and one equal to the run before was already seen and passed
     * over.</li>
     * </ul>
     *
     * <p>Values are compared as a set, ignoring case and spaces at either end.</p>
     *
     * @return the suggested values to show, empty when there is nothing to offer
     */
    @NotNull
    public List<String> getOfferedSuggestion()
    {
        final List<String> suggested = getSuggestedValues();
        final List<String> given = getGivenValues();
        if (given.isEmpty() || suggested.isEmpty()) {
            return suggested;
        }
        final List<Extraction> runs = getExtractions();
        final List<String> earlier = runs.size() < 2 ? List.of() : runs.get(runs.size() - 2).getExtractedValues();
        return isSameAnswer(suggested, given) || isSameAnswer(suggested, earlier) ? List.of() : suggested;
    }

    /** The submitter's own non-blank values. A blank one is a field clicked past, not an answer. */
    private List<String> getGivenValues()
    {
        return this.value == null ? List.of() : Arrays.stream(this.value).filter(each -> !each.isBlank()).toList();
    }

    /** Whether two lists of values say the same thing: the same set, ignoring case and outer spaces. */
    private static boolean isSameAnswer(final List<String> one, final List<String> other)
    {
        return normalize(one).equals(normalize(other));
    }

    private static Set<String> normalize(final List<String> values)
    {
        return values.stream().map(each -> each.strip().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }
}

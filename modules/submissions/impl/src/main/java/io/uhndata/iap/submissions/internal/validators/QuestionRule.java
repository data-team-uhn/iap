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
package io.uhndata.iap.submissions.internal.validators;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.internal.ApplicableItems;
import io.uhndata.iap.submissions.models.Submission;

/**
 * A constraint a question may carry on the values its answer holds, judged one question at a time.
 *
 * <p>Only the questions the submitter is shown are judged, the same set the completeness marking judges. Only
 * non-blank values count: an emptied field stores nothing, and a missing value is incompleteness, not a reason to
 * refuse a save.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
interface QuestionRule
{
    /**
     * Whether a question carries this rule's constraint at all.
     *
     * @param question the question
     * @return {@code true} if its answer is to be judged
     */
    boolean constrains(Question question);

    /**
     * Judges the values one question's answer holds.
     *
     * @param question a question this rule {@link #constrains constrains}
     * @param values the answer's non-blank values, possibly none
     * @return a reason to refuse, or {@code null} when the values fit
     */
    String refusal(Question question, List<String> values);

    /**
     * Judges every question of a submission that the submitter is shown and that carries this rule's constraint.
     *
     * @param submission the submission, carrying the answers the save wrote
     * @param conditions decides which questions the submitter is shown
     * @return the first question's reason to refuse, or {@code null} when every answer fits
     */
    default String firstRefusalIn(final Submission submission, final ConditionEvaluator conditions)
    {
        final Map<String, List<String>> answers = submission.getAnswersByQuestion();
        return ApplicableItems.getQuestions(submission, conditions).stream()
            .filter(this::constrains)
            .map(question -> refusal(question, answers.getOrDefault(question.getPath(), List.of()).stream()
                .filter(value -> !value.isBlank())
                .collect(Collectors.toList())))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }
}

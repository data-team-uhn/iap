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
package io.uhndata.iap.submissions.internal;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.AnswerValidator;

/**
 * Refuses a save giving a question more values than its {@code maxAnswers} takes.
 *
 * <p>
 * The maximum is the one half of the answer-count pair that is an error rather than incompleteness: a form never
 * offers a way to exceed it — a single-valued control holds one value, a capped list of checkboxes stops offering —
 * so a payload that does was not a submitter filling things in. Falling <em>short</em> of a demanded count is the
 * other half, and it is deliberately not judged here: answers arrive one at a time, so a shortfall is what the
 * completeness marking reports rather than something a save may be refused over.
 * </p>
 *
 * <p>
 * Only the questions currently asked are judged, and only their non-blank values are counted — the same reading of
 * "what is asked and what was answered" the completeness decision uses, so the two can never disagree about the
 * same submission.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component
public class MaxAnswersValidator implements AnswerValidator
{
    @Override
    public String validate(final Submission submission, final String actor)
    {
        final Map<String, List<String>> answers = submission.getAnswersByQuestion();
        return submission.getQuestions().stream()
            // Zero or a negative maximum means the question takes any number of values
            .filter(question -> question.getMaxAnswers() > 0)
            .map(question -> this.refusal(question, answers.getOrDefault(question.getPath(), List.of())))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    /**
     * Whether one question has been given more than it takes.
     *
     * @param question the question to judge
     * @param values the values its answer holds
     * @return a reason to refuse, or {@code null} when the answer fits
     */
    private String refusal(final Question question, final List<String> values)
    {
        final long given = values.stream().filter(value -> !value.isBlank()).count();
        if (given <= question.getMaxAnswers()) {
            return null;
        }
        // Numbers as labelled values rather than in a sentence: a count inside one needs plural agreement,
        // which is the one thing a translator cannot fix
        return "Too many values for \"" + question.getText() + "\". Given: " + given + ". Allowed: "
            + question.getMaxAnswers() + ".";
    }
}

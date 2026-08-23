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
 * Refuses a save whose numeric answer falls outside the question's {@code minValue}/{@code maxValue} bounds.
 *
 * <p>
 * Both bounds are hard: a schema that wants "suspicious but possible" says so some other way. A value that is not a
 * number at all is passed over rather than refused — whether an answer fits its declared data type is a different
 * question from whether its number is in range, and this rule only answers the second.
 * </p>
 *
 * <p>
 * Only the questions currently asked are judged, the same set the completeness decision judges.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component
public class ValueRangeValidator implements AnswerValidator
{
    @Override
    public String validate(final Submission submission, final String actor)
    {
        final Map<String, List<String>> answers = submission.getAnswersByQuestion();
        return submission.getQuestions().stream()
            .filter(question -> question.getMinValue() != null || question.getMaxValue() != null)
            .map(question -> this.refusal(question, answers.getOrDefault(question.getPath(), List.of())))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    /**
     * Whether any of one question's values is out of range.
     *
     * @param question the question to judge
     * @param values the values its answer holds
     * @return a reason to refuse, or {@code null} when every value is in range
     */
    private String refusal(final Question question, final List<String> values)
    {
        return values.stream()
            .filter(value -> !value.isBlank())
            .map(value -> this.refusalFor(question, value))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    /**
     * Whether one value is out of range.
     *
     * @param question the question, carrying the bounds
     * @param value one value of its answer
     * @return a reason to refuse, or {@code null} when the value is in range or is not a number
     */
    private String refusalFor(final Question question, final String value)
    {
        final double number;
        try {
            number = Double.parseDouble(value);
        } catch (final NumberFormatException notANumber) {
            // Not this rule's job: range says nothing about a value that is not a number
            return null;
        }
        // Each bound is read once into a local: asking a @Nullable accessor twice around a null check is what
        // makes it look safe to dereference
        final Double min = question.getMinValue();
        final Double max = question.getMaxValue();
        if (min != null && number < min) {
            return "The answer to \"" + question.getText() + "\" must be at least " + plain(min) + ".";
        }
        if (max != null && number > max) {
            return "The answer to \"" + question.getText() + "\" must be at most " + plain(max) + ".";
        }
        return null;
    }

    /**
     * A bound written the way its author wrote it: without the trailing zero a whole number would otherwise carry.
     *
     * @param bound the configured bound
     * @return the bound as text
     */
    private static String plain(final double bound)
    {
        return bound == Math.floor(bound) ? String.valueOf((long) bound) : String.valueOf(bound);
    }
}

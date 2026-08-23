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
import java.util.Objects;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.AnswerValidator;

/**
 * Refuses a save whose numeric answer falls outside the question's {@code minValue}/{@code maxValue} bounds.
 *
 * <p>Both bounds are hard. A value that is not a number is passed over, since whether an answer fits its data
 * type is a separate question.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = AnswerValidator.class)
public class ValueRangeValidator implements AnswerValidator, QuestionRule
{
    @Reference
    private ConditionEvaluator conditions;

    @Override
    public String validate(final Submission submission, final String actor)
    {
        return firstRefusalIn(submission, this.conditions);
    }

    @Override
    public boolean constrains(final Question question)
    {
        return question.getMinValue() != null || question.getMaxValue() != null;
    }

    @Override
    public String refusal(final Question question, final List<String> values)
    {
        return values.stream()
            .map(value -> refusalFor(question, value))
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
    private static String refusalFor(final Question question, final String value)
    {
        final double number;
        try {
            number = Double.parseDouble(value);
        } catch (final NumberFormatException notANumber) {
            return null;
        }
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

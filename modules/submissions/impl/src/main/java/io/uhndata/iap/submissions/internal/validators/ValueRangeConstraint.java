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

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.spi.AnswerConstraint;

/**
 * Refuses a numeric value outside a question's {@code minValue}/{@code maxValue} bounds. Both bounds are hard. A
 * value that is not a finite number is left to {@link NumericTypeConstraint}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = AnswerConstraint.class, property = "service.ranking:Integer=30")
public final class ValueRangeConstraint implements AnswerConstraint
{
    @Override
    public Optional<String> refusal(final Question question, final List<String> values)
    {
        if (question.getMinValue() == null && question.getMaxValue() == null) {
            return Optional.empty();
        }
        return values.stream()
            .map(value -> refusalFor(question, value))
            .filter(Objects::nonNull)
            .findFirst();
    }

    /**
     * Whether one value is out of range.
     *
     * @param question the question, carrying the bounds
     * @param value one value of its answer
     * @return a reason to refuse, or {@code null} when the value is in range or is not a finite number
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
     * A bound written the way its author wrote it, without a trailing zero or an exponent.
     *
     * @param bound the configured bound
     * @return the bound as text
     */
    private static String plain(final double bound)
    {
        return BigDecimal.valueOf(bound).stripTrailingZeros().toPlainString();
    }
}

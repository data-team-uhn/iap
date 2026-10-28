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
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Predicate;

import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.spi.AnswerConstraint;

/**
 * Refuses a value that is not a finite number for a {@code long} or {@code double} question, and one that is not
 * whole for a {@code long} question.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = AnswerConstraint.class, property = "service.ranking:Integer=20")
public final class NumericTypeConstraint implements AnswerConstraint
{
    private static final String LONG_TYPE = "long";

    private static final String DOUBLE_TYPE = "double";

    @Override
    public Optional<String> refusal(final Question question, final List<String> values)
    {
        final String dataType = question.getDataType();
        if (!LONG_TYPE.equals(dataType) && !DOUBLE_TYPE.equals(dataType)) {
            return Optional.empty();
        }
        final boolean whole = LONG_TYPE.equals(dataType);
        final Predicate<String> fits = whole ? NumericTypeConstraint::isWhole : NumericTypeConstraint::isFinite;
        return values.stream()
            .filter(fits.negate())
            .findFirst()
            .map(value -> "The answer to \"" + question.getText() + "\" must be a " + (whole ? "whole " : "")
                + "number.");
    }

    private static boolean isFinite(final String value)
    {
        return finite(value).isPresent();
    }

    private static boolean isWhole(final String value)
    {
        return finite(value).stream().anyMatch(number -> number == Math.rint(number));
    }

    private static OptionalDouble finite(final String value)
    {
        try {
            final double number = Double.parseDouble(value);
            return Double.isFinite(number) ? OptionalDouble.of(number) : OptionalDouble.empty();
        } catch (final NumberFormatException notANumber) {
            return OptionalDouble.empty();
        }
    }
}

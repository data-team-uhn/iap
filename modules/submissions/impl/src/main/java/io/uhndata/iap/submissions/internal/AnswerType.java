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

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.Arrays;

import io.uhndata.iap.schemas.models.AnswerOption;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.workflows.api.InvalidPayloadException;

/**
 * How an answer is stored, chosen by its question's {@code dataType}: a whole number as a JCR {@code LONG}, a number
 * as a {@code DOUBLE}, yes or no as a {@code BOOLEAN}, and anything else as the text submitted.
 *
 * <p>
 * A date stays text: it has no time and no zone, and {@code yyyy-mm-dd} already orders chronologically as text.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public enum AnswerType
{
    /** A {@code long} question: a whole number, written as {@code 3}, {@code 3.0} or {@code 3e0}. */
    WHOLE_NUMBER("long", Long.class, "must be a whole number")
    {
        @Override
        Object parse(final String value)
        {
            try {
                return new BigDecimal(value).longValueExact();
            } catch (final ArithmeticException notWhole) {
                throw new IllegalArgumentException(notWhole);
            }
        }

        @Override
        public boolean matches(final String first, final String second)
        {
            return sameNumber(first, second);
        }
    },

    /** A {@code double} question: a finite number. */
    NUMBER("double", Double.class, "must be a number")
    {
        @Override
        Object parse(final String value)
        {
            final double number = Double.parseDouble(value);
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException("Not a finite number: " + value);
            }
            return number;
        }

        @Override
        public boolean matches(final String first, final String second)
        {
            return sameNumber(first, second);
        }
    },

    /** A {@code boolean} question: {@code true} or {@code false}, as the form sends yes and no. */
    YES_OR_NO("boolean", Boolean.class, "must be yes or no")
    {
        @Override
        Object parse(final String value)
        {
            if (!"true".equals(value) && !"false".equals(value)) {
                throw new IllegalArgumentException("Neither true nor false: " + value);
            }
            return Boolean.valueOf(value);
        }
    },

    /** Any other question, dates included: the text as submitted. */
    TEXT(null, String.class, null)
    {
        @Override
        Object parse(final String value)
        {
            return value;
        }
    };

    /** The {@code dataType} naming this type, {@code null} for the type every other data type falls back to. */
    private final String dataType;

    /** The Java type Sling stores as the matching JCR property type. */
    private final Class<?> storedAs;

    /** How a refusal ends: what a value must be. */
    private final String requirement;

    AnswerType(final String dataType, final Class<?> storedAs, final String requirement)
    {
        this.dataType = dataType;
        this.storedAs = storedAs;
        this.requirement = requirement;
    }

    /**
     * How a question's answers are stored.
     *
     * @param question the question answered
     * @return the type its {@code dataType} names, or {@link #TEXT} for any other data type
     */
    public static AnswerType of(final Question question)
    {
        return Arrays.stream(values())
            .filter(type -> question.getDataType().equals(type.dataType))
            .findFirst()
            .orElse(TEXT);
    }

    /**
     * A stored value written the way the option it picks is, so that it can be matched as text: a number question
     * offering {@code 2} stores {@code 2.0}.
     *
     * @param question the question answered
     * @param stored a stored value, as text
     * @return the value of the option it picks, or the stored value when it picks none
     */
    public static String asOffered(final Question question, final String stored)
    {
        final AnswerType type = of(question);
        return question.getOptions().stream()
            .map(AnswerOption::getValue)
            .filter(offered -> type.matches(offered, stored))
            .findFirst()
            .orElse(stored);
    }

    /**
     * The submitted values in the Java type this answer type is stored as, so that Sling writes the matching JCR
     * property type.
     *
     * @param question the question answered, which names the answer in a refusal
     * @param values the values as submitted, none of them blank
     * @return an array of the stored Java type holding each value converted, in the order given
     * @throws InvalidPayloadException if a value does not fit the type, such as {@code 3.5} for a whole number
     */
    public Object[] store(final Question question, final String[] values) throws InvalidPayloadException
    {
        final Object[] stored = (Object[]) Array.newInstance(this.storedAs, values.length);
        for (int i = 0; i < values.length; ++i) {
            try {
                stored[i] = parse(values[i].strip());
            } catch (final IllegalArgumentException doesNotFit) {
                throw new InvalidPayloadException(
                    "The answer to \"" + question.getText() + "\" " + this.requirement + ".");
            }
        }
        return stored;
    }

    /**
     * Whether two values written as text stand for the same answer of this type: {@code 2.50} and {@code 2.5} do
     * for a number.
     *
     * @param first one value, such as an offered option's
     * @param second the other, such as a stored answer's
     * @return {@code true} if they are the same answer
     */
    public boolean matches(final String first, final String second)
    {
        return first.equals(second);
    }

    /**
     * One submitted value in the Java type this answer type is stored as.
     *
     * @param value the value as submitted, without surrounding blanks
     * @return the converted value
     * @throws IllegalArgumentException if the value does not fit the type
     */
    abstract Object parse(String value);

    private static boolean sameNumber(final String first, final String second)
    {
        try {
            return new BigDecimal(first.strip()).compareTo(new BigDecimal(second.strip())) == 0;
        } catch (final NumberFormatException notANumber) {
            return false;
        }
    }
}

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
package io.uhndata.iap.conditions.api;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * An optional fold collapsing an operand's value set into a single value before the comparison, as indicated in
 * the {@code aggregate} property of a {@code cond:ConditionOperand} node — e.g. summing the answers given across
 * repeated blocks, or counting the options picked in a multi-select answer. Aggregating an empty operand always
 * yields an empty operand, so absence stays detectable (and fails ordering comparisons) instead of silently
 * becoming a number.
 *
 * @version $Id$
 * @since 0.1.0
 */
public enum Aggregator
{
    /** The number of values. Compared as a number regardless of the values' own type. */
    COUNT("count")
    {
        @Override
        public OperandType getOutputType()
        {
            return OperandType.LONG;
        }

        @Override
        public Operand apply(final Operand input, final OperandType comparisonType)
        {
            return input.isEmpty() ? Operand.EMPTY
                : Operand.of((long) input.size(), OperandType.LONG).coerce(comparisonType);
        }
    },

    /** The sum of the values. Only applicable when the values are compared as numbers. */
    SUM("sum")
    {
        @Override
        public Operand apply(final Operand input, final OperandType comparisonType)
        {
            final Operand coerced = input.coerce(comparisonType);
            if (coerced.isEmpty()) {
                return Operand.EMPTY;
            }
            final Object sum = switch (comparisonType) {
                case LONG -> coerced.rawStream().map(Long.class::cast).reduce(0L, Long::sum);
                case DOUBLE -> coerced.rawStream().map(Double.class::cast).reduce(0.0d, Double::sum);
                case DECIMAL -> coerced.rawStream().map(BigDecimal.class::cast)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                default -> null;
            };
            return sum == null ? null : new Operand(List.of(sum), comparisonType);
        }
    },

    /** The arithmetic mean of the values, computed and compared as a floating point number. */
    AVG("avg")
    {
        @Override
        public OperandType getOutputType()
        {
            return OperandType.DOUBLE;
        }

        @Override
        public Operand apply(final Operand input, final OperandType comparisonType)
        {
            final Operand coerced = input.coerce(OperandType.DOUBLE);
            if (coerced.isEmpty()) {
                return Operand.EMPTY;
            }
            final double mean = coerced.rawStream()
                .map(Double.class::cast)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElseThrow();
            return Operand.of(mean, OperandType.DOUBLE).coerce(comparisonType);
        }
    },

    /** The smallest value, by the natural order of the compared type — usable for dates too. */
    MIN("min")
    {
        @Override
        public Operand apply(final Operand input, final OperandType comparisonType)
        {
            final Operand coerced = input.coerce(comparisonType);
            return coerced.isEmpty() ? Operand.EMPTY
                : new Operand(List.of(coerced.stream().min(Comparator.naturalOrder()).orElseThrow()),
                    comparisonType);
        }
    },

    /** The largest value, by the natural order of the compared type — usable for dates too. */
    MAX("max")
    {
        @Override
        public Operand apply(final Operand input, final OperandType comparisonType)
        {
            final Operand coerced = input.coerce(comparisonType);
            return coerced.isEmpty() ? Operand.EMPTY
                : new Operand(List.of(coerced.stream().max(Comparator.naturalOrder()).orElseThrow()),
                    comparisonType);
        }
    };

    private final String name;

    Aggregator(final String name)
    {
        this.name = name;
    }

    /**
     * The name of this aggregator, as stored in the {@code aggregate} property of an operand node.
     *
     * @return an aggregator name, e.g. {@code sum}
     */
    public String getName()
    {
        return this.name;
    }

    /**
     * The type this aggregator's result holds, when it differs from the type of the input values — e.g. a count is
     * a number no matter what is being counted. When present, this type takes part in the comparison type
     * unification in place of the input values' own type.
     *
     * @return an operand type, or {@code null} if the result has the same type as the input values
     */
    public OperandType getOutputType()
    {
        return null;
    }

    /**
     * Fold the values of an operand into a single value.
     *
     * @param input the resolved operand, with values still in their stored form
     * @param comparisonType the unified type the comparison is performed as
     * @return the folded operand, empty if the input is empty, or {@code null} if this aggregator cannot operate
     *         under the given comparison type (e.g. summing non-numbers)
     */
    public abstract Operand apply(Operand input, OperandType comparisonType);

    /**
     * Convert the value of the {@code aggregate} property of an operand node into an enum item.
     *
     * @param name the value stored in the operand node
     * @return an enum instance
     * @throws IllegalArgumentException if the value passed is not a known aggregator
     */
    public static Aggregator parse(final String name)
    {
        return Arrays.stream(values())
            .filter(aggregator -> aggregator.name.equals(name))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown aggregator: " + name));
    }
}

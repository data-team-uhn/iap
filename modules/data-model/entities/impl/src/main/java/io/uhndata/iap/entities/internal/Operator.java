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
package io.uhndata.iap.entities.internal;

import java.util.Locale;
import java.util.function.BinaryOperator;

/**
 * The comparison operators accepted in a pagination request, each knowing how to serialize itself into a JCR-SQL2
 * condition given a property reference and an already escaped literal value.
 *
 * @version $Id$
 * @since 0.1.0
 */
enum Operator
{
    /** Simple equality. */
    EQ("="),

    /**
     * Negated equality. A literal {@code x <> y} is evaluated on each entry of a multi-valued property and never
     * matches an empty one, while {@code not x = y} behaves intuitively for both single and multi-valued
     * properties, so the latter is used.
     */
    NEQ("<>", (property, value) -> "not " + property + " = '" + value + '\''),

    /** Strictly lower than. */
    LT("<"),

    /** Lower than or equal. */
    LTE("<="),

    /** Strictly greater than. */
    GT(">"),

    /** Greater than or equal. */
    GTE(">="),

    /** Pattern matching, with {@code %} and {@code _} as wildcards. */
    LIKE("LIKE"),

    /** Negated pattern matching. */
    NOT_LIKE("NOT LIKE", (property, value) -> "not " + property + " LIKE '" + value + '\''),

    /** Case-insensitive pattern matching, which JCR-SQL2 doesn't have natively: lowercase both sides. */
    ILIKE("ILIKE", (property, value) -> "LOWER(" + property + ") LIKE '" + lowercase(value) + '\''),

    /** Negated case-insensitive pattern matching. */
    NOT_ILIKE("NOT ILIKE", (property, value) -> "not LOWER(" + property + ") LIKE '" + lowercase(value) + '\''),

    /** The property is not set. No value is compared against. */
    IS_NULL("IS NULL", true),

    /** The property is set. No value is compared against. */
    IS_NOT_NULL("IS NOT NULL", true);

    /** How the operator is spelled in a request. */
    private final String symbol;

    /** Whether the operator stands on its own, without comparing against a value. */
    private final boolean valueless;

    /** Turns a property reference and an escaped literal value into a JCR-SQL2 condition. */
    private final BinaryOperator<String> serializer;

    /**
     * Constructor for a plain binary operator whose JCR-SQL2 spelling is {@code property symbol 'value'}.
     *
     * @param symbol how the operator is spelled in a request
     */
    Operator(final String symbol)
    {
        this(symbol, false);
    }

    /**
     * Constructor for an operator using the default JCR-SQL2 spelling, {@code property symbol 'value'} for a binary
     * operator or just {@code property symbol} for a valueless one.
     *
     * @param symbol how the operator is spelled in a request
     * @param valueless whether the operator stands on its own, without comparing against a value
     */
    Operator(final String symbol, final boolean valueless)
    {
        this.symbol = symbol;
        this.valueless = valueless;
        this.serializer = valueless
            ? (property, value) -> property + ' ' + symbol
            : (property, value) -> property + ' ' + symbol + " '" + value + '\'';
    }

    /**
     * Constructor for a binary operator needing a custom JCR-SQL2 serialization.
     *
     * @param symbol how the operator is spelled in a request
     * @param serializer turns a property reference and an escaped literal value into a JCR-SQL2 condition
     */
    Operator(final String symbol, final BinaryOperator<String> serializer)
    {
        this.symbol = symbol;
        this.valueless = false;
        this.serializer = serializer;
    }

    /**
     * Whether the operator stands on its own, without comparing against a value, like {@code IS NULL}.
     *
     * @return {@code true} if the operator doesn't need a value
     */
    boolean isValueless()
    {
        return this.valueless;
    }

    /**
     * Serializes a condition applying this operator.
     *
     * @param property the property reference to compare, e.g. {@code n.[status]}
     * @param value the escaped literal value to compare against, ignored by valueless operators
     * @return a JCR-SQL2 condition
     */
    String apply(final String property, final String value)
    {
        return this.serializer.apply(property, value);
    }

    /**
     * Converts the comparator sent in a request into an operator.
     *
     * @param symbol the requested comparator, may be {@code null}
     * @return the matching operator, or {@link #EQ} if the symbol isn't a supported comparator
     */
    static Operator parse(final String symbol)
    {
        for (final Operator operator : values()) {
            if (operator.symbol.equals(symbol)) {
                return operator;
            }
        }
        return EQ;
    }

    /**
     * Lowercases a value for the case-insensitive operators.
     *
     * @param value the value to lowercase
     * @return the value in lowercase
     */
    private static String lowercase(final String value)
    {
        return value.toLowerCase(Locale.ROOT);
    }
}

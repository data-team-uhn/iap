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
package io.uhndata.iap.search.internal;

import java.util.Locale;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

/**
 * The comparators a pagination request can use. Each writes a JCR-SQL2 condition from a property reference and the
 * bind variable holding the value to compare against.
 *
 * <p>
 * No operator writes the value into the statement. An operator that needs the value in another form, such as in
 * lower case, transforms what is bound.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
enum Operator
{
    /** Simple equality. */
    EQ("="),

    /**
     * Negated equality, written {@code not x = y}. On a multi-valued property, {@code x <> y} would match when any one
     * value differs, and it never matches a property that is not set.
     */
    NEQ("<>", (property, variable) -> "not " + property + " = " + variable),

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
    NOT_LIKE("NOT LIKE", (property, variable) -> "not " + property + " LIKE " + variable),

    /** Pattern matching that ignores case. JCR-SQL2 has no such operator, so both sides are lowercased. */
    ILIKE("ILIKE", (property, variable) -> "LOWER(" + property + ") LIKE " + variable, Operator::lowercase),

    /** Negated pattern matching that ignores case. */
    NOT_ILIKE("NOT ILIKE", (property, variable) -> "not LOWER(" + property + ") LIKE " + variable,
        Operator::lowercase),

    /** The property is not set. No value is compared against. */
    IS_NULL("IS NULL", true),

    /** The property is set. No value is compared against. */
    IS_NOT_NULL("IS NOT NULL", true);

    /** How the operator is spelled in a request. */
    private final String symbol;

    /** Whether the operator takes no value. */
    private final boolean valueless;

    /** Writes the JCR-SQL2 condition from a property reference and a bind variable reference. */
    private final BinaryOperator<String> serializer;

    /** Turns the requested value into the form this operator compares against. */
    private final UnaryOperator<String> valuePreparation;

    /**
     * Creates an operator written {@code property symbol $variable}.
     *
     * @param symbol how the operator is spelled in a request
     */
    Operator(final String symbol)
    {
        this(symbol, false);
    }

    /**
     * Creates an operator written {@code property symbol $variable}, or {@code property symbol} if it takes no
     * value.
     *
     * @param symbol how the operator is spelled in a request
     * @param valueless whether the operator takes no value
     */
    Operator(final String symbol, final boolean valueless)
    {
        this.symbol = symbol;
        this.valueless = valueless;
        this.serializer = valueless
            ? (property, variable) -> property + ' ' + symbol
            : (property, variable) -> property + ' ' + symbol + ' ' + variable;
        this.valuePreparation = UnaryOperator.identity();
    }

    /**
     * Creates an operator that writes its condition its own way.
     *
     * @param symbol how the operator is spelled in a request
     * @param serializer writes the JCR-SQL2 condition from a property reference and a bind variable reference
     */
    Operator(final String symbol, final BinaryOperator<String> serializer)
    {
        this(symbol, serializer, UnaryOperator.identity());
    }

    /**
     * Creates an operator that also transforms the requested value before it is bound.
     *
     * @param symbol how the operator is spelled in a request
     * @param serializer writes the JCR-SQL2 condition from a property reference and a bind variable reference
     * @param valuePreparation turns the requested value into the form this operator compares against
     */
    Operator(final String symbol, final BinaryOperator<String> serializer,
        final UnaryOperator<String> valuePreparation)
    {
        this.symbol = symbol;
        this.valueless = false;
        this.serializer = serializer;
        this.valuePreparation = valuePreparation;
    }

    /**
     * Whether the operator takes no value, as {@code IS NULL} does.
     *
     * @return {@code true} if the operator takes no value
     */
    boolean isValueless()
    {
        return this.valueless;
    }

    /**
     * Writes a condition applying this operator.
     *
     * @param property the property reference to compare, such as {@code n.[createdBy]}
     * @param variable the bind variable holding the value to compare against, such as {@code $p0}; ignored by an
     *            operator that takes no value
     * @return a JCR-SQL2 condition
     */
    String apply(final String property, final String variable)
    {
        return this.serializer.apply(property, variable);
    }

    /**
     * Turns a requested value into the form this operator compares against. For most operators that is the value
     * itself.
     *
     * @param value the value as it was requested
     * @return the value to bind
     */
    String prepareValue(final String value)
    {
        return this.valuePreparation.apply(value);
    }

    /**
     * Converts the comparator sent in a request into an operator.
     *
     * @param symbol the requested comparator, such as {@code ILIKE}; {@code null} if none was sent
     * @return the matching operator; {@link #EQ} if the symbol names none
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
     * Lowercases a value for the operators that ignore case.
     *
     * @param value the value to lowercase
     * @return the value in lower case
     */
    private static String lowercase(final String value)
    {
        return value.toLowerCase(Locale.ROOT);
    }
}

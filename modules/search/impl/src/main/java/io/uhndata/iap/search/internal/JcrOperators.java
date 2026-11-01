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

import io.uhndata.iap.search.api.Operator;

/**
 * Writes a listing's comparators as JCR-SQL2 conditions.
 *
 * <p>
 * No condition holds its value: it names a bind variable, and the value is bound separately, in the form
 * {@link #boundValue} gives it.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class JcrOperators
{
    private JcrOperators()
    {
        // Utility class
    }

    /**
     * A JCR-SQL2 condition applying a comparator.
     *
     * @param operator the comparator
     * @param property the property reference compared, such as {@code n.[status]}
     * @param variable the bind variable holding the value, such as {@code $p0}; ignored by a comparator that takes
     *            no value
     * @return the condition, such as {@code n.[status] >= $p0}
     */
    static String condition(final Operator operator, final String property, final String variable)
    {
        return switch (operator) {
            // A literal `x <> y` is evaluated on each entry of a multi-valued property and never matches an
            // unset one, while `not x = y` matches both as a reader expects
            case NEQ -> "not " + property + " = " + variable;
            case NOT_LIKE -> "not " + property + " LIKE " + variable;
            // JCR-SQL2 has no case-insensitive LIKE: both sides are lowercased
            case ILIKE -> "LOWER(" + property + ") LIKE " + variable;
            case NOT_ILIKE -> "not LOWER(" + property + ") LIKE " + variable;
            case IS_NULL, IS_NOT_NULL -> property + ' ' + operator.getSymbol();
            default -> property + ' ' + operator.getSymbol() + ' ' + variable;
        };
    }

    /**
     * The value to bind for a comparator: the value as requested, except lowercased for the case-insensitive ones.
     *
     * @param operator the comparator
     * @param value the value as the request sent it
     * @return the value to bind
     */
    static String boundValue(final Operator operator, final String value)
    {
        return switch (operator) {
            case ILIKE, NOT_ILIKE -> value.toLowerCase(Locale.ROOT);
            default -> value;
        };
    }
}

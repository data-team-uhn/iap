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
package io.uhndata.iap.search.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The comparators a condition in an {@link EntityListing} may use, spelled as a pagination request spells them.
 * Whatever runs the listing, a JCR query or an index, decides how each one is evaluated.
 *
 * @version $Id$
 * @since 0.1.0
 */
public enum Operator
{
    /** Simple equality. */
    EQ("="),

    /** Negated equality. A property that is not set does not equal anything, and so matches. */
    NEQ("<>"),

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
    NOT_LIKE("NOT LIKE"),

    /** Case-insensitive pattern matching. */
    ILIKE("ILIKE"),

    /** Negated case-insensitive pattern matching. */
    NOT_ILIKE("NOT ILIKE"),

    /** The property is not set. No value is compared against. */
    IS_NULL("IS NULL", true),

    /** The property is set. No value is compared against. */
    IS_NOT_NULL("IS NOT NULL", true);

    private final String symbol;

    private final boolean valueless;

    Operator(final String symbol)
    {
        this(symbol, false);
    }

    Operator(final String symbol, final boolean valueless)
    {
        this.symbol = symbol;
        this.valueless = valueless;
    }

    /**
     * How the comparator is spelled in a request.
     *
     * @return the comparator's symbol, such as {@code <>} or {@code NOT ILIKE}
     */
    @NotNull
    public String getSymbol()
    {
        return this.symbol;
    }

    /**
     * Whether the comparator stands on its own, without comparing against a value, like {@code IS NULL}.
     *
     * @return {@code true} if the comparator takes no value
     */
    public boolean isValueless()
    {
        return this.valueless;
    }

    /**
     * Reads the comparator a request sent.
     *
     * @param symbol the comparator as the request spelled it, such as {@code >=}; {@code null} if it sent none
     * @return the comparator with that symbol, or {@link #EQ} if the request sent none or one that is not supported
     */
    @NotNull
    public static Operator parse(@Nullable final String symbol)
    {
        for (final Operator operator : values()) {
            if (operator.symbol.equals(symbol)) {
                return operator;
            }
        }
        return EQ;
    }
}

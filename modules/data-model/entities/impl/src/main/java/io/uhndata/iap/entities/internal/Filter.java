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

import java.util.List;

/**
 * One property condition parsed from a pagination request: a property name, a comparator, and, unless the comparator
 * is a valueless one like {@code IS NULL}, a value to compare against. A condition may also name a group: conditions
 * sharing a group are ORed together in the final query, while distinct groups are ANDed.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class Filter
{
    /** The comparators accepted in a request; anything else falls back to {@code =}. */
    private static final List<String> COMPARATORS =
        List.of("=", "<>", "<", "<=", ">", ">=", "LIKE", "ILIKE", "NOT ILIKE", "IS NULL", "IS NOT NULL");

    /** The comparators that don't compare against a value. */
    private static final List<String> VALUELESS_COMPARATORS = List.of("IS NULL", "IS NOT NULL");

    private final String name;

    private final String comparator;

    private final String value;

    private final String group;

    /**
     * Simple constructor for an ungrouped condition.
     *
     * @param name the property name; only validated later, when the condition is added to a query
     * @param comparator the requested comparator; {@code =} is used instead if this isn't a supported comparator
     * @param value the value to compare against, ignored for valueless comparators
     */
    Filter(final String name, final String comparator, final String value)
    {
        this(name, comparator, value, null);
    }

    /**
     * Simple constructor.
     *
     * @param name the property name; only validated later, when the condition is added to a query
     * @param comparator the requested comparator; {@code =} is used instead if this isn't a supported comparator
     * @param value the value to compare against, ignored for valueless comparators
     * @param group the group this condition is ORed within, or {@code null} for a standalone condition
     */
    Filter(final String name, final String comparator, final String value, final String group)
    {
        this.name = name;
        this.comparator = comparator != null && COMPARATORS.contains(comparator) ? comparator : "=";
        this.value = value;
        this.group = group;
    }

    /**
     * The name of the property that the condition applies to.
     *
     * @return a property name, as sent in the request
     */
    String getName()
    {
        return this.name;
    }

    /**
     * The comparator to use in the condition.
     *
     * @return one of the supported comparators
     */
    String getComparator()
    {
        return this.comparator;
    }

    /**
     * The value to compare the property against.
     *
     * @return a value, may be {@code null} for valueless comparators
     */
    String getValue()
    {
        return this.value;
    }

    /**
     * The group this condition belongs to. Conditions sharing a group are ORed together; distinct groups (and
     * conditions with no group at all) are ANDed.
     *
     * @return an opaque group identifier, or {@code null} for a standalone condition
     */
    String getGroup()
    {
        return this.group;
    }

    /**
     * Whether the comparator stands on its own, without comparing against a value, like {@code IS NULL}.
     *
     * @return {@code true} if the comparator doesn't need a value
     */
    boolean isValueless()
    {
        return VALUELESS_COMPARATORS.contains(this.comparator);
    }
}

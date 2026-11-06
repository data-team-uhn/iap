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

/**
 * One condition on a property, from a pagination request. It holds the property name, a comparator, and a value to
 * compare against, unless the comparator takes none, as {@code IS NULL} does. A condition may also name a group.
 * Conditions in the same group are ORed together, and groups are ANDed.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class Filter
{
    private final String name;

    private final Operator comparator;

    private final String value;

    private final String group;

    /**
     * Creates a condition in no group.
     *
     * @param name the property name; checked only when the condition is written into a statement
     * @param comparator the requested comparator, such as {@code <=}; {@code =} if it names none
     * @param value the value to compare against; ignored by a comparator that takes none
     */
    Filter(final String name, final String comparator, final String value)
    {
        this(name, comparator, value, null);
    }

    /**
     * Creates a condition.
     *
     * @param name the property name; checked only when the condition is written into a statement
     * @param comparator the requested comparator, such as {@code <=}; {@code =} if it names none
     * @param value the value to compare against; ignored by a comparator that takes none
     * @param group the name of the group this condition is ORed within; {@code null} for none
     */
    Filter(final String name, final String comparator, final String value, final String group)
    {
        this.name = name;
        this.comparator = Operator.parse(comparator);
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
     * @return the operator
     */
    Operator getComparator()
    {
        return this.comparator;
    }

    /**
     * The value to compare the property against.
     *
     * @return the value as sent; {@code null} if none was sent
     */
    String getValue()
    {
        return this.value;
    }

    /**
     * The group this condition belongs to.
     *
     * @return the group's name, as the request sent it; {@code null} if the condition is in no group
     */
    String getGroup()
    {
        return this.group;
    }

    /**
     * Whether the comparator takes no value, as {@code IS NULL} does.
     *
     * @return {@code true} if the comparator takes no value
     */
    boolean isValueless()
    {
        return this.comparator.isValueless();
    }
}

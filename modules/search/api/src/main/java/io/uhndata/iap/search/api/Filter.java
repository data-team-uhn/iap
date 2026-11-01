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
 * One condition on a property, as a pagination request states it: a property name, a comparator, and, unless the
 * comparator takes no value, a value to compare against. Conditions sharing a group are ORed together; distinct
 * groups, and conditions with no group, are ANDed.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class Filter
{
    private final String name;

    private final Operator comparator;

    private final String value;

    private final String group;

    /**
     * A condition in no group.
     *
     * @param name the name of the property, such as {@code reviewer}; checked only when the condition is evaluated
     * @param comparator the comparator's symbol, such as {@code <>}; {@code null} or an unsupported symbol means
     *            {@code =}
     * @param value the value to compare against, as text; ignored by a comparator that takes no value
     */
    public Filter(@NotNull final String name, @Nullable final String comparator, @Nullable final String value)
    {
        this(name, comparator, value, null);
    }

    /**
     * A condition, possibly in a group.
     *
     * @param name the name of the property, such as {@code reviewer}; checked only when the condition is evaluated
     * @param comparator the comparator's symbol, such as {@code <>}; {@code null} or an unsupported symbol means
     *            {@code =}
     * @param value the value to compare against, as text; ignored by a comparator that takes no value
     * @param group the group this condition is ORed within, any text the request chose; {@code null} if it stands
     *            alone
     */
    public Filter(@NotNull final String name, @Nullable final String comparator, @Nullable final String value,
        @Nullable final String group)
    {
        this.name = name;
        this.comparator = Operator.parse(comparator);
        this.value = value;
        this.group = group;
    }

    /**
     * The property the condition applies to.
     *
     * @return the property name as the request sent it
     */
    @NotNull
    public String getName()
    {
        return this.name;
    }

    /**
     * The comparator the condition uses.
     *
     * @return the comparator, {@link Operator#EQ} if the request named none or an unsupported one
     */
    @NotNull
    public Operator getComparator()
    {
        return this.comparator;
    }

    /**
     * The value the property is compared against.
     *
     * @return the value as text, or {@code null} if the request sent none, as for {@code IS NULL}
     */
    @Nullable
    public String getValue()
    {
        return this.value;
    }

    /**
     * The group this condition is ORed within.
     *
     * @return the group's identifier as the request chose it, or {@code null} if the condition stands alone
     */
    @Nullable
    public String getGroup()
    {
        return this.group;
    }

    /**
     * Whether the comparator stands on its own, without comparing against a value, like {@code IS NULL}.
     *
     * @return {@code true} if the comparator takes no value
     */
    public boolean isValueless()
    {
        return this.comparator.isValueless();
    }
}

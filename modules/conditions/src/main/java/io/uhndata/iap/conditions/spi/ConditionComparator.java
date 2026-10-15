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
package io.uhndata.iap.conditions.spi;

import java.util.List;

import jakarta.json.JsonObjectBuilder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.autodoc.api.DocumentedItem;
import io.uhndata.iap.conditions.api.Operand;

/**
 * A way to compare the two operands of a condition, named by the {@code comparator} property of a
 * {@code cond:SingleCondition}. To add one, a module registers an implementation as a service. The evaluator, the
 * schema checks, the {@code /ConditionTypes} catalogue and the condition builder then all know it.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface ConditionComparator extends DocumentedItem
{
    /** The catalogue category comparators are listed under. */
    String DOCUMENTATION_CATEGORY = "Comparators";

    /**
     * Whether the two operands pass this comparison.
     *
     * @param left the first operand
     * @param right the second operand, empty for a unary comparator
     * @return {@code true} if the condition holds
     */
    boolean evaluate(@NotNull Operand left, @NotNull Operand right);

    /**
     * The words that come between the two operands when the condition is put into words, e.g.
     * {@code is at least}.
     *
     * @return a phrase
     */
    @NotNull
    String getPhrase();

    /**
     * The words to use instead of {@link #getPhrase()} when the second operand is a single value, e.g.
     * {@code includes} instead of {@code includes all of}.
     *
     * @return a phrase, or {@code null} if {@link #getPhrase()} reads well before a single value too
     */
    @Nullable
    default String getSingleValuePhrase()
    {
        return null;
    }

    /**
     * Whether the comparison takes no second operand, e.g. {@code is empty}.
     *
     * @return {@code true} for a unary comparator
     */
    default boolean isUnary()
    {
        return false;
    }

    /**
     * Whether the comparison puts one value before or after another, such as {@code is at least}. Both operands
     * must then hold a single value, of a type that has an order, such as a number or a date.
     *
     * @return {@code true} for an ordering comparator
     */
    default boolean isOrdering()
    {
        return false;
    }

    /**
     * Whether the comparison tests one set of values against another, such as {@code includes all of}. The first
     * operand may then hold several values.
     *
     * @return {@code true} for a set comparator
     */
    default boolean isSets()
    {
        return false;
    }

    @Override
    @NotNull
    default String getDocumentationLabel()
    {
        return getPhrase();
    }

    @Override
    @NotNull
    default List<String> getDocumentationCategories()
    {
        return List.of(DOCUMENTATION_CATEGORY);
    }

    @Override
    @NotNull
    default JsonObjectBuilder documentationJsonBuilder()
    {
        final JsonObjectBuilder json = DocumentedItem.super.documentationJsonBuilder()
            .add("phrase", getPhrase())
            .add("unary", isUnary())
            .add("ordering", isOrdering())
            .add("sets", isSets());
        final String singleValuePhrase = getSingleValuePhrase();
        if (singleValuePhrase != null) {
            json.add("singleValuePhrase", singleValuePhrase);
        }
        return json;
    }
}

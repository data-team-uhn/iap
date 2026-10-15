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
 * {@code cond:SingleCondition}. A module adds one by registering an implementation as a service. It is then
 * evaluated, accepted by the checks, listed in the {@code /ConditionTypes} catalogue, and offered by the condition
 * builder, which reads that catalogue.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface ConditionComparator extends DocumentedItem
{
    /** The catalogue category comparators are listed under. */
    String CATEGORY = "Comparators";

    /**
     * Whether the two operands pass this comparison.
     *
     * @param left the first operand
     * @param right the second operand, empty for a unary comparator
     * @return {@code true} if the condition holds
     */
    boolean evaluate(@NotNull Operand left, @NotNull Operand right);

    /**
     * How the comparison reads between its two operands, e.g. {@code is at least}.
     *
     * @return a phrase
     */
    @NotNull
    String getPhrase();

    /**
     * How the comparison reads before a single value, when that differs from {@link #getPhrase()}, e.g.
     * {@code includes} for {@code includes all of}.
     *
     * @return a phrase, or {@code null} when the phrase reads the same before one value
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
     * Whether the comparison orders one value against one other, so that both operands must hold a single value of
     * a type with an order.
     *
     * @return {@code true} for an ordering comparator
     */
    default boolean isOrdering()
    {
        return false;
    }

    /**
     * Whether the comparison checks the values of an operand that may hold several against some values.
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
        return List.of(CATEGORY);
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

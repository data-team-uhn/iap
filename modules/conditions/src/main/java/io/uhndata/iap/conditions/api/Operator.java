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

import java.util.Arrays;
import java.util.function.BiPredicate;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.conditions.spi.ConditionComparator;

/**
 * The comparators every instance has, named by the {@code comparator} property of a {@code cond:SingleCondition}
 * node, with the logic evaluating them on actual operand values. Except where noted, they accept multi-valued
 * operands; the ordering comparators require both operands to hold exactly one value and evaluate to {@code false}
 * otherwise. A module adds others as {@link ConditionComparator} services.
 *
 * @version $Id$
 * @since 0.1.0
 */
public enum Operator implements ConditionComparator
{
    /** Check that the two operands hold the same set of values. */
    EQUALS("equals", "is", null, Kind.ANY, "Holds when the two operands hold the same values.",
        (left, right) -> left.stream()
            .allMatch(lv -> right.stream().anyMatch(rv -> lv.compareTo(rv) == 0))
            && right.stream()
                .allMatch(rv -> left.stream().anyMatch(lv -> lv.compareTo(rv) == 0))),

    /** Check that the two operands do not hold the same set of values. */
    NOT_EQUALS("not equals", "is not", null, Kind.ANY, "Holds when the two operands do not hold the same values.",
        (left, right) -> !EQUALS.evaluate(left, right)),

    /** Check that the first operand is less than the second. Requires single valued operands. */
    LESS_THAN("less than", "is less than", null, Kind.ORDERING, "Holds when the first value comes before the second.",
        (left, right) -> left.get(0).compareTo(right.get(0)) < 0),

    /** Check that the first operand is less than or equal to the second. Requires single valued operands. */
    LESS_OR_EQUAL("less or equal", "is at most", null, Kind.ORDERING,
        "Holds when the first value does not come after the second.",
        (left, right) -> left.get(0).compareTo(right.get(0)) <= 0),

    /** Check that the first operand is greater than the second. Requires single valued operands. */
    GREATER_THAN("greater than", "is more than", null, Kind.ORDERING,
        "Holds when the first value comes after the second.",
        (left, right) -> left.get(0).compareTo(right.get(0)) > 0),

    /** Check that the first operand is greater than or equal to the second. Requires single valued operands. */
    GREATER_OR_EQUAL("greater or equal", "is at least", null, Kind.ORDERING,
        "Holds when the first value does not come before the second.",
        (left, right) -> left.get(0).compareTo(right.get(0)) >= 0),

    /** Check that the first operand holds no values; the second operand is ignored. */
    IS_EMPTY("is empty", "is empty", null, Kind.UNARY, "Holds when the operand holds no value.",
        (left, right) -> left.isEmpty()),

    /** Check that the first operand holds at least one value; the second operand is ignored. */
    IS_NOT_EMPTY("is not empty", "is not empty", null, Kind.UNARY, "Holds when the operand holds a value.",
        (left, right) -> !left.isEmpty()),

    /** Check that the first operand contains every value of the second. */
    INCLUDES("includes", "includes all of", "includes", Kind.SETS,
        "Holds when the first operand holds every value of the second.",
        (left, right) -> right.stream()
            .allMatch(rv -> left.stream().anyMatch(lv -> lv.compareTo(rv) == 0))),

    /** Check that the first operand contains at least one value of the second. */
    INCLUDES_ANY("includes any", "includes any of", "includes", Kind.SETS,
        "Holds when the first operand holds at least one value of the second.",
        (left, right) -> right.stream()
            .anyMatch(rv -> left.stream().anyMatch(lv -> lv.compareTo(rv) == 0))),

    /** Check that the first operand contains no value of the second. */
    EXCLUDES("excludes", "includes none of", "does not include", Kind.SETS,
        "Holds when the first operand holds no value of the second.",
        (left, right) -> right.stream()
            .noneMatch(rv -> left.stream().anyMatch(lv -> lv.compareTo(rv) == 0))),

    /** Check that the first operand lacks at least one value of the second. */
    EXCLUDES_ANY("excludes any", "does not include all of", "does not include", Kind.SETS,
        "Holds when the first operand lacks at least one value of the second.",
        (left, right) -> right.stream()
            .anyMatch(rv -> left.stream().noneMatch(lv -> lv.compareTo(rv) == 0)));

    /**
     * What a comparator expects of its operands.
     *
     * @version $Id$
     * @since 0.1.0
     */
    private enum Kind
    {
        ANY, ORDERING, UNARY, SETS
    }

    private final String name;

    private final String phrase;

    private final String singleValuePhrase;

    private final Kind kind;

    private final String description;

    private final BiPredicate<Operand, Operand> evaluator;

    Operator(final String name, final String phrase, final String singleValuePhrase, final Kind kind,
        final String description, final BiPredicate<Operand, Operand> evaluator)
    {
        this.name = name;
        this.phrase = phrase;
        this.singleValuePhrase = singleValuePhrase;
        this.kind = kind;
        this.description = description;
        this.evaluator = evaluator;
    }

    /**
     * The name of this operator, as stored in the {@code comparator} property of a condition node.
     *
     * @return an operator name, e.g. {@code less than}
     */
    @Override
    @NotNull
    public String getName()
    {
        return this.name;
    }

    @Override
    @NotNull
    public String getDescription()
    {
        return this.description;
    }

    @Override
    @NotNull
    public String getPhrase()
    {
        return this.phrase;
    }

    @Override
    @Nullable
    public String getSingleValuePhrase()
    {
        return this.singleValuePhrase;
    }

    @Override
    public boolean isUnary()
    {
        return this.kind == Kind.UNARY;
    }

    @Override
    public boolean isOrdering()
    {
        return this.kind == Kind.ORDERING;
    }

    @Override
    public boolean isSets()
    {
        return this.kind == Kind.SETS;
    }

    /**
     * Evaluate the two operands according to the rules of this operator.
     *
     * @param left the first operand
     * @param right the second operand, may be empty if this is an unary operator
     * @return {@code true} if the two operands pass this operator, {@code false} otherwise
     */
    @Override
    public boolean evaluate(@NotNull final Operand left, @NotNull final Operand right)
    {
        if (this.kind == Kind.ORDERING && (left.size() != 1 || right.size() != 1)) {
            return false;
        }
        return this.evaluator.test(left, right);
    }

    /**
     * Convert the value of the {@code comparator} property of a condition node into an enum item.
     *
     * @param name the value stored in the condition node
     * @return an enum instance
     * @throws IllegalArgumentException if the value passed is not a known operator
     */
    @NotNull
    public static Operator parse(@NotNull final String name)
    {
        return Arrays.stream(values())
            .filter(operator -> operator.name.equals(name))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown comparator: " + name));
    }
}

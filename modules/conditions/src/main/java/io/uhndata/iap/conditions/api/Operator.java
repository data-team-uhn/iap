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

/**
 * The comparator of a condition, as indicated in the {@code comparator} property of a {@code cond:SingleCondition}
 * node, including the logic evaluating it on actual operand values. Except where noted, operators accept
 * multi-valued operands; the ordering operators require both operands to hold exactly one value and evaluate to
 * {@code false} otherwise.
 *
 * @version $Id$
 * @since 0.1.0
 */
public enum Operator
{
    /** Check that the two operands hold the same set of values. */
    EQUALS("equals", true, (left, right) -> left.stream()
        .allMatch(lv -> right.stream().anyMatch(rv -> lv.compareTo(rv) == 0))
        && right.stream()
            .allMatch(rv -> left.stream().anyMatch(lv -> lv.compareTo(rv) == 0))),

    /** Check that the two operands do not hold the same set of values. */
    NOT_EQUALS("not equals", true, (left, right) -> !EQUALS.evaluate(left, right)),

    /** Check that the first operand is less than the second. Requires single valued operands. */
    LESS_THAN("less than", false, (left, right) -> left.get(0).compareTo(right.get(0)) < 0),

    /** Check that the first operand is less than or equal to the second. Requires single valued operands. */
    LESS_OR_EQUAL("less or equal", false, (left, right) -> left.get(0).compareTo(right.get(0)) <= 0),

    /** Check that the first operand is greater than the second. Requires single valued operands. */
    GREATER_THAN("greater than", false, (left, right) -> left.get(0).compareTo(right.get(0)) > 0),

    /** Check that the first operand is greater than or equal to the second. Requires single valued operands. */
    GREATER_OR_EQUAL("greater or equal", false, (left, right) -> left.get(0).compareTo(right.get(0)) >= 0),

    /** Check that the first operand holds no values; the second operand is ignored. */
    IS_EMPTY("is empty", true, (left, right) -> left.isEmpty()),

    /** Check that the first operand holds at least one value; the second operand is ignored. */
    IS_NOT_EMPTY("is not empty", true, (left, right) -> !left.isEmpty()),

    /** Check that the first operand contains every value of the second. */
    INCLUDES("includes", true, (left, right) -> right.stream()
        .allMatch(rv -> left.stream().anyMatch(lv -> lv.compareTo(rv) == 0))),

    /** Check that the first operand contains at least one value of the second. */
    INCLUDES_ANY("includes any", true, (left, right) -> right.stream()
        .anyMatch(rv -> left.stream().anyMatch(lv -> lv.compareTo(rv) == 0))),

    /** Check that the first operand contains no value of the second. */
    EXCLUDES("excludes", true, (left, right) -> right.stream()
        .noneMatch(rv -> left.stream().anyMatch(lv -> lv.compareTo(rv) == 0))),

    /** Check that the first operand lacks at least one value of the second. */
    EXCLUDES_ANY("excludes any", true, (left, right) -> right.stream()
        .anyMatch(rv -> left.stream().noneMatch(lv -> lv.compareTo(rv) == 0)));

    private final String name;

    private final boolean supportsMultivalue;

    private final BiPredicate<Operand, Operand> evaluator;

    Operator(final String name, final boolean supportsMultivalue, final BiPredicate<Operand, Operand> evaluator)
    {
        this.name = name;
        this.supportsMultivalue = supportsMultivalue;
        this.evaluator = evaluator;
    }

    /**
     * The name of this operator, as stored in the {@code comparator} property of a condition node.
     *
     * @return an operator name, e.g. {@code less than}
     */
    public String getName()
    {
        return this.name;
    }

    /**
     * Evaluate the two operands according to the rules of this operator.
     *
     * @param left the first operand
     * @param right the second operand, may be empty if this is an unary operator
     * @return {@code true} if the two operands pass this operator, {@code false} otherwise
     */
    public boolean evaluate(final Operand left, final Operand right)
    {
        if (!this.supportsMultivalue && (left.size() != 1 || right.size() != 1)) {
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
    public static Operator parse(final String name)
    {
        return Arrays.stream(values())
            .filter(operator -> operator.name.equals(name))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown comparator: " + name));
    }
}

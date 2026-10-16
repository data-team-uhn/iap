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

import java.util.List;

import jakarta.json.JsonObject;

import org.junit.jupiter.api.Test;

import io.uhndata.iap.conditions.spi.ConditionComparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Operator}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class OperatorTest
{
    private static final String A = "a";

    private static final String B = "b";

    private static final String C = "c";

    private static final String D = "d";

    private static final String IGNORED = "ignored";

    @Test
    void equalsComparesAsSets()
    {
        assertTrue(Operator.EQUALS.evaluate(text(A, B), text(B, A)));
        assertTrue(Operator.EQUALS.evaluate(Operand.EMPTY, Operand.EMPTY));
        // Each side must be covered by the other, in both directions
        assertFalse(Operator.EQUALS.evaluate(text(A), text(A, B)));
        assertFalse(Operator.EQUALS.evaluate(text(A, B), text(A)));
    }

    @Test
    void notEqualsNegatesEquals()
    {
        assertTrue(Operator.NOT_EQUALS.evaluate(text(A), text(B)));
        assertFalse(Operator.NOT_EQUALS.evaluate(text(A), text(A)));
        // The same values in another order are equal
        assertFalse(Operator.NOT_EQUALS.evaluate(text(A, B), text(B, A)));
    }

    @Test
    void orderingOperatorsCompareSingleValues()
    {
        assertTrue(Operator.LESS_THAN.evaluate(longs("1"), longs("2")));
        assertFalse(Operator.LESS_THAN.evaluate(longs("2"), longs("1")));

        assertTrue(Operator.LESS_OR_EQUAL.evaluate(longs("1"), longs("1")));
        assertFalse(Operator.LESS_OR_EQUAL.evaluate(longs("2"), longs("1")));

        assertTrue(Operator.GREATER_THAN.evaluate(longs("2"), longs("1")));
        assertFalse(Operator.GREATER_THAN.evaluate(longs("1"), longs("2")));

        assertTrue(Operator.GREATER_OR_EQUAL.evaluate(longs("1"), longs("1")));
        assertFalse(Operator.GREATER_OR_EQUAL.evaluate(longs("1"), longs("2")));
    }

    @Test
    void orderingOperatorsRejectNonSingleOperands()
    {
        assertFalse(Operator.LESS_THAN.evaluate(longs("1", "2"), longs("3")));
        assertFalse(Operator.GREATER_THAN.evaluate(longs("3"), longs("1", "2")));
        assertFalse(Operator.LESS_OR_EQUAL.evaluate(Operand.EMPTY, longs("1")));
        assertFalse(Operator.GREATER_OR_EQUAL.evaluate(longs("1"), Operand.EMPTY));
    }

    @Test
    void emptinessOperatorsOnlyConsiderTheFirstOperand()
    {
        assertTrue(Operator.IS_EMPTY.evaluate(Operand.EMPTY, text(IGNORED)));
        assertFalse(Operator.IS_EMPTY.evaluate(text(A), Operand.EMPTY));

        assertTrue(Operator.IS_NOT_EMPTY.evaluate(text(A), Operand.EMPTY));
        assertFalse(Operator.IS_NOT_EMPTY.evaluate(Operand.EMPTY, text(IGNORED)));
    }

    @Test
    void includesRequiresAllValues()
    {
        assertTrue(Operator.INCLUDES.evaluate(text(A, B, C), text(A, C)));
        // Vacuously true on an empty second operand
        assertTrue(Operator.INCLUDES.evaluate(text(A), Operand.EMPTY));
        assertFalse(Operator.INCLUDES.evaluate(text(A, B), text(A, D)));
    }

    @Test
    void includesAnyRequiresOneValue()
    {
        assertTrue(Operator.INCLUDES_ANY.evaluate(text(A, B), text(B, D)));
        assertFalse(Operator.INCLUDES_ANY.evaluate(text(A, B), text(C, D)));
    }

    @Test
    void excludesRejectsAllValues()
    {
        assertTrue(Operator.EXCLUDES.evaluate(text(A, B), text(C, D)));
        assertFalse(Operator.EXCLUDES.evaluate(text(A, B), text(B, C)));
    }

    @Test
    void excludesAnyRejectsOneValue()
    {
        assertTrue(Operator.EXCLUDES_ANY.evaluate(text(A, B), text(B, C)));
        assertFalse(Operator.EXCLUDES_ANY.evaluate(text(A, B), text(A, B)));
    }

    @Test
    void parsesEveryOperatorByName()
    {
        for (final Operator operator : Operator.values()) {
            assertEquals(operator, Operator.parse(operator.getName()));
        }
    }

    @Test
    void describesWhatEachExpectsOfItsOperands()
    {
        assertTrue(Operator.IS_EMPTY.isUnary());
        assertTrue(Operator.LESS_THAN.isOrdering());
        assertTrue(Operator.INCLUDES.isSets());
        assertFalse(Operator.EQUALS.isUnary() || Operator.EQUALS.isOrdering() || Operator.EQUALS.isSets());
        assertEquals("is at least", Operator.GREATER_OR_EQUAL.getPhrase());
        assertEquals("does not include", Operator.EXCLUDES.getSingleValuePhrase());
        assertNull(Operator.EQUALS.getSingleValuePhrase());
        for (final Operator operator : Operator.values()) {
            assertNotNull(operator.getDescription());
            assertEquals(operator.getPhrase(), operator.getDocumentationLabel());
            assertEquals(List.of(ConditionComparator.CATEGORY), operator.getDocumentationCategories());
        }
    }

    @Test
    void documentsItsPhrasesAndKindInTheCatalogue()
    {
        final JsonObject includes = Operator.INCLUDES.documentationJsonBuilder().build();
        assertEquals("includes", includes.getString("name"));
        assertEquals("includes all of", includes.getString("phrase"));
        assertEquals("includes", includes.getString("singleValuePhrase"));
        assertTrue(includes.getBoolean("sets"));
        assertFalse(includes.getBoolean("unary"));
        assertFalse(includes.getBoolean("ordering"));
        // Left out rather than null when it reads the same before one value
        assertFalse(Operator.EQUALS.documentationJsonBuilder().build().containsKey("singleValuePhrase"));
    }

    @Test
    void rejectsUnknownComparators()
    {
        assertThrows(IllegalArgumentException.class, () -> Operator.parse("resembles"));
    }

    private static Operand text(final String... values)
    {
        return Operand.of(values).coerce(OperandType.TEXT);
    }

    private static Operand longs(final String... values)
    {
        return Operand.of(values).coerce(OperandType.LONG);
    }
}

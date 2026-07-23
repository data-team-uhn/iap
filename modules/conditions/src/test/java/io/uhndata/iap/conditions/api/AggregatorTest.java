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

import java.math.BigDecimal;
import java.util.Calendar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Aggregator}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class AggregatorTest
{
    @Test
    void countProducesTheNumberOfValues()
    {
        final Operand result =
            Aggregator.COUNT.apply(Operand.of(new String[]{ "a", "b", "c" }), OperandType.LONG);

        assertEquals(1, result.size());
        assertEquals(3L, result.get(0));
    }

    @Test
    void countFollowsTheComparisonType()
    {
        // Counting anything is a number, but the other side may impose e.g. a floating point comparison
        final Operand result =
            Aggregator.COUNT.apply(Operand.of(new String[]{ "a", "b" }), OperandType.DOUBLE);

        assertEquals(2.0d, result.get(0));
    }

    @Test
    void sumFoldsNumbers()
    {
        assertEquals(6L,
            Aggregator.SUM.apply(Operand.of(new String[]{ "1", "2", "3" }), OperandType.LONG).get(0));
        assertEquals(4.0d,
            Aggregator.SUM.apply(Operand.of(new String[]{ "1.5", "2.5" }), OperandType.DOUBLE).get(0));
        assertEquals(new BigDecimal("3.3"),
            Aggregator.SUM.apply(Operand.of(new String[]{ "1.1", "2.2" }), OperandType.DECIMAL).get(0));
    }

    @Test
    void sumIsNotApplicableToNonNumbers()
    {
        assertNull(Aggregator.SUM.apply(Operand.of(new String[]{ "a", "b" }), OperandType.TEXT));
    }

    @Test
    void avgComputesTheMean()
    {
        final Operand result =
            Aggregator.AVG.apply(Operand.of(new String[]{ "1", "2", "3" }), OperandType.DOUBLE);

        assertEquals(2.0d, result.get(0));
        assertEquals(OperandType.DOUBLE, result.getType());
    }

    @Test
    void minAndMaxUseTheNaturalOrderOfTheComparedType()
    {
        assertEquals("apple",
            Aggregator.MIN.apply(Operand.of(new String[]{ "cherry", "apple", "banana" }), OperandType.TEXT).get(0));
        assertEquals(30L,
            Aggregator.MAX.apply(Operand.of(new String[]{ "9", "30", "27" }), OperandType.LONG).get(0));

        // Dates order chronologically, not textually
        final Operand latest = Aggregator.MAX
            .apply(Operand.of(new String[]{ "2026-07-23", "2026-11-02", "2026-08-01" }), OperandType.DATE);
        assertEquals(Calendar.NOVEMBER, ((Calendar) (Object) latest.get(0)).get(Calendar.MONTH));
    }

    @Test
    void aggregatingNothingYieldsNothing()
    {
        // Absence stays detectable instead of silently becoming a number
        assertTrue(Aggregator.COUNT.apply(Operand.EMPTY, OperandType.LONG).isEmpty());
        assertTrue(Aggregator.SUM.apply(Operand.EMPTY, OperandType.LONG).isEmpty());
        assertTrue(Aggregator.AVG.apply(Operand.EMPTY, OperandType.DOUBLE).isEmpty());
        assertTrue(Aggregator.MIN.apply(Operand.EMPTY, OperandType.TEXT).isEmpty());
        assertTrue(Aggregator.MAX.apply(Operand.EMPTY, OperandType.TEXT).isEmpty());
    }

    @Test
    void declaresTypeChangingOutputs()
    {
        assertEquals(OperandType.LONG, Aggregator.COUNT.getOutputType());
        assertEquals(OperandType.DOUBLE, Aggregator.AVG.getOutputType());
        assertNull(Aggregator.SUM.getOutputType());
        assertNull(Aggregator.MIN.getOutputType());
        assertNull(Aggregator.MAX.getOutputType());
    }

    @Test
    void parsesEveryAggregatorByName()
    {
        for (final Aggregator aggregator : Aggregator.values()) {
            assertEquals(aggregator, Aggregator.parse(aggregator.getName()));
        }
    }

    @Test
    void rejectsUnknownAggregators()
    {
        assertThrows(IllegalArgumentException.class, () -> Aggregator.parse("median"));
    }
}

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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Operand}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class OperandTest
{
    @Test
    void wrapsSingleRawValues()
    {
        final Operand operand = Operand.of("42");

        assertEquals(1, operand.size());
        assertEquals("42", operand.get(0));
        assertNull(operand.getType());
        assertFalse(operand.isEmpty());
    }

    @Test
    void wrapsRawValueArrays()
    {
        final Operand operand = Operand.of(new String[]{ "a", "b" });

        assertEquals(2, operand.size());
        assertEquals(List.of("a", "b"), operand.stream().toList());
    }

    @Test
    void treatsNullAsEmpty()
    {
        assertTrue(Operand.of(null).isEmpty());
        assertTrue(Operand.EMPTY.isEmpty());
        assertEquals(0, Operand.EMPTY.size());
        assertNull(Operand.EMPTY.getType());
    }

    @Test
    void keepsTheDeclaredTypeOnEmptyValues()
    {
        final Operand operand = Operand.of(null, OperandType.LONG);

        assertTrue(operand.isEmpty());
        assertEquals(OperandType.LONG, operand.getType());
    }

    @Test
    void coercesValuesAndDropsTheUninterpretable()
    {
        final Operand operand = Operand.of(new String[]{ "1", "one", "2" }).coerce(OperandType.LONG);

        assertEquals(List.of(1L, 2L), operand.stream().toList());
        assertEquals(OperandType.LONG, operand.getType());
    }

    @Test
    void prefersTheDeclaredTypeAsEffectiveType()
    {
        assertEquals(OperandType.DATE, Operand.of("2026-07-23", OperandType.DATE).getEffectiveType());
    }

    @Test
    void infersTheEffectiveTypeFromValues()
    {
        assertEquals(OperandType.LONG, Operand.of(42L).getEffectiveType());
        // Strings deliberately infer nothing, they follow the other side of the comparison
        assertNull(Operand.of("42").getEffectiveType());
        assertNull(Operand.EMPTY.getEffectiveType());
    }
}

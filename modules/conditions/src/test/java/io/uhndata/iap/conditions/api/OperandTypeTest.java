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
import java.util.Date;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link OperandType}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class OperandTypeTest
{
    @Test
    void coercesText()
    {
        assertEquals("abc", OperandType.TEXT.coerce("abc"));
        assertEquals("5", OperandType.TEXT.coerce(5L));
        assertNull(OperandType.TEXT.coerce(null));
    }

    @Test
    void coercesBooleans()
    {
        assertEquals(Boolean.TRUE, OperandType.BOOLEAN.coerce(Boolean.TRUE));
        assertEquals(Boolean.TRUE, OperandType.BOOLEAN.coerce("TRUE"));
        assertEquals(Boolean.FALSE, OperandType.BOOLEAN.coerce("no"));
    }

    @Test
    void coercesLongs()
    {
        assertEquals(5L, OperandType.LONG.coerce(5L));
        assertEquals(7L, OperandType.LONG.coerce(7));
        assertEquals(42L, OperandType.LONG.coerce(" 42 "));
        assertNull(OperandType.LONG.coerce("forty-two"));
    }

    @Test
    void coercesDoubles()
    {
        assertEquals(2.5d, OperandType.DOUBLE.coerce(2.5d));
        assertEquals(7.0d, OperandType.DOUBLE.coerce(7));
        assertEquals(3.14d, OperandType.DOUBLE.coerce("3.14"));
        assertNull(OperandType.DOUBLE.coerce("pi"));
    }

    @Test
    void coercesDecimals()
    {
        assertEquals(new BigDecimal("2.5"), OperandType.DECIMAL.coerce(new BigDecimal("2.5")));
        assertEquals(0, OperandType.DECIMAL.coerce(7).compareTo(new BigDecimal("7")));
        assertEquals(new BigDecimal("3.14"), OperandType.DECIMAL.coerce("3.14"));
        assertNull(OperandType.DECIMAL.coerce("pi"));
    }

    @Test
    void coercesDates()
    {
        final Calendar calendar = Calendar.getInstance();
        assertEquals(calendar, OperandType.DATE.coerce(calendar));

        final Date date = calendar.getTime();
        final Calendar fromDate = (Calendar) (Object) OperandType.DATE.coerce(date);
        assertEquals(calendar.getTimeInMillis(), fromDate.getTimeInMillis());

        final Calendar parsed = (Calendar) (Object) OperandType.DATE.coerce("2026-07-23");
        assertEquals(2026, parsed.get(Calendar.YEAR));
        assertEquals(Calendar.JULY, parsed.get(Calendar.MONTH));
        assertEquals(23, parsed.get(Calendar.DAY_OF_MONTH));

        assertNull(OperandType.DATE.coerce("yesterday-ish"));
    }

    @Test
    void infersTypesFromStoredValues()
    {
        assertEquals(OperandType.BOOLEAN, OperandType.infer(Boolean.TRUE));
        assertEquals(OperandType.DECIMAL, OperandType.infer(new BigDecimal("2.5")));
        assertEquals(OperandType.DOUBLE, OperandType.infer(2.5d));
        assertEquals(OperandType.DOUBLE, OperandType.infer(2.5f));
        assertEquals(OperandType.LONG, OperandType.infer(42L));
        assertEquals(OperandType.LONG, OperandType.infer(42));
        assertEquals(OperandType.DATE, OperandType.infer(Calendar.getInstance()));
        assertEquals(OperandType.DATE, OperandType.infer(new Date()));
        assertNull(OperandType.infer("flexible"));
    }

    @Test
    void identifiesNumericTypes()
    {
        assertTrue(OperandType.LONG.isNumeric());
        assertTrue(OperandType.DOUBLE.isNumeric());
        assertTrue(OperandType.DECIMAL.isNumeric());
        assertFalse(OperandType.TEXT.isNumeric());
        assertFalse(OperandType.BOOLEAN.isNumeric());
        assertFalse(OperandType.DATE.isNumeric());
    }

    @Test
    void parsesLabelsCaseInsensitively()
    {
        assertEquals(OperandType.LONG, OperandType.parse("long"));
        assertEquals(OperandType.TEXT, OperandType.parse("TEXT"));
        assertEquals(OperandType.DATE, OperandType.parse("Date"));
    }

    @Test
    void fallsBackToTextForUnknownLabels()
    {
        assertEquals(OperandType.TEXT, OperandType.parse("timestamp"));
        assertEquals(OperandType.TEXT, OperandType.parse(null));
    }
}

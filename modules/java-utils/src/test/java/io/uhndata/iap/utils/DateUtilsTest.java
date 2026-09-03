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

package io.uhndata.iap.utils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.TimeZone;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DateUtils}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class DateUtilsTest
{
    // A fixed, non-UTC timezone with a stable January offset (-05:00), so that assertions on
    // timezone-dependent parsing and formatting are deterministic regardless of the host's zone.
    private static final TimeZone TEST_ZONE = TimeZone.getTimeZone("America/Toronto");

    private static final String ISO_PATTERN = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}([+-]\\d{2}:\\d{2}|Z)";

    private TimeZone originalZone;

    @BeforeEach
    public void setup()
    {
        this.originalZone = TimeZone.getDefault();
        TimeZone.setDefault(TEST_ZONE);
    }

    @AfterEach
    public void teardown()
    {
        TimeZone.setDefault(this.originalZone);
    }

    @Test
    public void testParseCalendarReturnsNullForBlankOrNull()
    {
        Assertions.assertNull(DateUtils.parseCalendar(null));
        Assertions.assertNull(DateUtils.parseCalendar(""));
        Assertions.assertNull(DateUtils.parseCalendar("   "));
    }

    @Test
    public void testParseCalendarReturnsNullForUnparseableInput()
    {
        Assertions.assertNull(DateUtils.parseCalendar("not a date"));
    }

    @Test
    public void testParseCalendarParsesDateOnly()
    {
        final Calendar result = DateUtils.parseCalendar("2025-01-15");

        Assertions.assertNotNull(result);
        Assertions.assertEquals(2025, result.get(Calendar.YEAR));
        Assertions.assertEquals(Calendar.JANUARY, result.get(Calendar.MONTH));
        Assertions.assertEquals(15, result.get(Calendar.DAY_OF_MONTH));
    }

    @Test
    public void testParseCalendarParsesSlashFormat()
    {
        final Calendar result = DateUtils.parseCalendar("1/15/2025");

        Assertions.assertNotNull(result);
        Assertions.assertEquals(2025, result.get(Calendar.YEAR));
        Assertions.assertEquals(Calendar.JANUARY, result.get(Calendar.MONTH));
        Assertions.assertEquals(15, result.get(Calendar.DAY_OF_MONTH));
    }

    @Test
    public void testParseDateReturnsNullForBlankOrNull()
    {
        Assertions.assertNull(DateUtils.parseDate(null));
        Assertions.assertNull(DateUtils.parseDate(""));
    }

    @Test
    public void testParseDateReturnsNullForUnparseableInput()
    {
        Assertions.assertNull(DateUtils.parseDate("not a date"));
    }

    @Test
    public void testParseDateReadsTheDayOfAWholeDateTime()
    {
        // The stored form of a `date` answer carries a time and an offset; neither may move the day
        Assertions.assertEquals(LocalDate.of(2025, 1, 15),
            DateUtils.parseDate("2025-01-15T23:30:00.000+05:00"));
        Assertions.assertEquals(LocalDate.of(2025, 1, 15), DateUtils.parseDate("2025-01-15T10:30:00"));
    }

    @Test
    public void testParseDateReadsABareDate()
    {
        Assertions.assertEquals(LocalDate.of(2025, 1, 15), DateUtils.parseDate("2025-01-15"));
        Assertions.assertEquals(LocalDate.of(2025, 1, 15), DateUtils.parseDate("1/15/2025"));
    }

    @Test
    public void testParseDateTimeReturnsNullForBlankOrNull()
    {
        Assertions.assertNull(DateUtils.parseDateTime(null));
        Assertions.assertNull(DateUtils.parseDateTime(""));
    }

    @Test
    public void testParseDateTimeReturnsNullForUnparseableInput()
    {
        Assertions.assertNull(DateUtils.parseDateTime("not a date"));
    }

    @Test
    public void testParseDateTimeWithExplicitOffset()
    {
        final ZonedDateTime result = DateUtils.parseDateTime("2025-01-15T10:30:00.000+05:00");

        Assertions.assertNotNull(result);
        Assertions.assertEquals(2025, result.getYear());
        Assertions.assertEquals(10, result.getHour());
        Assertions.assertEquals(ZoneOffset.ofHours(5), result.getOffset());
    }

    @Test
    public void testParseDateTimeWithoutOffsetUsesSystemDefault()
    {
        final ZonedDateTime result = DateUtils.parseDateTime("2025-01-15T10:30:00");

        Assertions.assertNotNull(result);
        Assertions.assertEquals(10, result.getHour());
        Assertions.assertEquals(30, result.getMinute());
        Assertions.assertEquals(ZoneId.systemDefault(), result.getZone());
    }

    @Test
    public void testAtMidnightZonedDateTime()
    {
        final ZonedDateTime source = ZonedDateTime.of(2025, 1, 15, 10, 30, 45, 123, ZoneOffset.ofHours(-5));

        final ZonedDateTime result = DateUtils.atMidnight(source);

        Assertions.assertEquals(0, result.getHour());
        Assertions.assertEquals(0, result.getMinute());
        Assertions.assertEquals(0, result.getSecond());
        Assertions.assertEquals(0, result.getNano());
        Assertions.assertEquals(15, result.getDayOfMonth());
    }

    @Test
    public void testAtMidnightCalendarDoesNotModifyOriginal()
    {
        final Calendar source = Calendar.getInstance();
        source.set(2025, Calendar.JANUARY, 15, 10, 30, 45);
        source.set(Calendar.MILLISECOND, 123);

        final Calendar result = DateUtils.atMidnight(source);

        Assertions.assertEquals(0, result.get(Calendar.HOUR_OF_DAY));
        Assertions.assertEquals(0, result.get(Calendar.MINUTE));
        Assertions.assertEquals(0, result.get(Calendar.SECOND));
        Assertions.assertEquals(0, result.get(Calendar.MILLISECOND));
        Assertions.assertEquals(15, result.get(Calendar.DAY_OF_MONTH));
        // The original must be left untouched
        Assertions.assertEquals(10, source.get(Calendar.HOUR_OF_DAY));
    }

    @Test
    public void testToStringCalendarReturnsNullForNull()
    {
        Assertions.assertNull(DateUtils.toString((Calendar) null));
    }

    @Test
    public void testToStringCalendarProducesIsoString()
    {
        final Calendar source = Calendar.getInstance();
        source.set(2025, Calendar.JANUARY, 15, 10, 30, 45);
        source.set(Calendar.MILLISECOND, 0);

        final String result = DateUtils.toString(source);

        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.matches(ISO_PATTERN), "Unexpected format: " + result);
    }

    @Test
    public void testToStringTemporalAccessorReturnsNullForNull()
    {
        Assertions.assertNull(DateUtils.toString((java.time.temporal.TemporalAccessor) null));
    }

    @Test
    public void testToStringTemporalAccessorFormatsZonedDateTime()
    {
        final ZonedDateTime source = ZonedDateTime.of(2025, 1, 15, 10, 30, 0, 0, ZoneOffset.ofHours(-5));

        Assertions.assertEquals("2025-01-15T10:30:00.000-05:00", DateUtils.toString(source));
    }

    @Test
    public void testToStringTemporalAccessorReturnsNullWhenNotFormattable()
    {
        // A LocalDate has no time fields, so the datetime formatter cannot format it and returns null
        Assertions.assertNull(DateUtils.toString(LocalDate.of(2025, 1, 15)));
    }

    @Test
    public void testNormalizeReturnsNullForNull()
    {
        Assertions.assertNull(DateUtils.normalize(null));
    }

    @Test
    public void testNormalizeReturnsNullForUnparseableInput()
    {
        Assertions.assertNull(DateUtils.normalize("not a date"));
    }

    @Test
    public void testNormalizeAddsMissingTimeAndOffset()
    {
        Assertions.assertEquals("2025-01-15T00:00:00.000-05:00", DateUtils.normalize("2025-01-15"));
    }

    @Test
    public void testNormalizeReformatsFullDateTime()
    {
        Assertions.assertEquals("2025-01-15T10:30:00.000-05:00", DateUtils.normalize("2025-01-15T10:30:00"));
    }
}

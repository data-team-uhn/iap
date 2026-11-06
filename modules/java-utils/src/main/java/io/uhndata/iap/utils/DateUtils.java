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

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.util.Calendar;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reads dates written in any of several formats, and writes them in one.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class DateUtils
{
    /**
     * The format dates are written in, such as {@code 2025-01-15T10:30:00.000-05:00}. JCR accepts it as a
     * {@code DATE} value. The offset is always numeric, so UTC is written {@code +00:00}, never {@code Z}.
     */
    public static final DateTimeFormatter PREFERRED_DATETIME_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx");

    /**
     * The formats dates are read in, most specific first. A format without an offset reads the date in the JVM's
     * default time zone, and a format without a time reads midnight.
     */
    public static final List<DateTimeFormatter> DATETIME_FORMATS = List.of(
        PREFERRED_DATETIME_FORMAT,
        getFormat("yyyy-MM-dd'T'HH:mm:ss.SSSz"),
        getFormat("yyyy-MM-dd' 'HH:mm:ss.SSSz"),
        getFormat("yyyy-MM-dd'T'HH:mm:ssz"),
        getFormat("yyyy-MM-dd' 'HH:mm:ssz"),
        getFormat("yyyy-MM-dd'T'HH:mm:ss.SSS"),
        getFormat("yyyy-MM-dd' 'HH:mm:ss.SSS"),
        getFormat("yyyy-MM-dd'T'HH:mm:ss"),
        getFormat("yyyy-MM-dd' 'HH:mm:ss"),
        getFormat("yyyy-MM-dd'T'HH:mm"),
        getFormat("yyyy-MM-dd' 'HH:mm"),
        getFormat("yyyy-MM-dd"),
        getFormat("M/d/y"));

    private DateUtils()
    {
    }

    private static DateTimeFormatter getFormat(final String format)
    {
        return new DateTimeFormatterBuilder()
            .appendPattern(format)
            .parseDefaulting(ChronoField.NANO_OF_SECOND, 0)
            .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
            .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
            .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
            .toFormatter();
    }

    /**
     * Reads a date as a {@link Calendar} in the JVM's default time zone. The instant is kept, and the string's own
     * offset is not.
     *
     * @param str a date in one of the {@link #DATETIME_FORMATS}; {@code null} or blank when there is no date
     * @return the date; {@code null} if the string is {@code null}, blank, or matches none of the formats
     */
    @Nullable
    public static Calendar parseCalendar(@Nullable final String str)
    {
        final ZonedDateTime date = parseDateTime(str);
        if (date == null) {
            return null;
        }
        final Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(date.toInstant().toEpochMilli());
        return calendar;
    }

    /**
     * Reads a date as a {@link ZonedDateTime}, in the zone the string names, or the JVM's default if it names none.
     *
     * @param str a date in one of the {@link #DATETIME_FORMATS}; {@code null} or blank when there is no date
     * @return the date; {@code null} if the string is {@code null}, blank, or matches none of the formats
     */
    @Nullable
    public static ZonedDateTime parseDateTime(@Nullable final String str)
    {
        return parseWithFirstMatchingFormat(str, format -> {
            final TemporalAccessor result = format.parseBest(str, ZonedDateTime::from, LocalDateTime::from);
            return result instanceof ZonedDateTime zoned ? zoned
                : LocalDateTime.from(result).atZone(ZoneId.systemDefault());
        });
    }

    /**
     * Reads the day a string names. Any time and offset it also carries are ignored, so the offset never moves the
     * day into another zone: {@code 2025-01-15T02:00:00.000+05:00} reads as January 15 whatever the JVM's zone.
     *
     * @param str a date as {@code yyyy-MM-dd}, or a timestamp in one of the {@link #DATETIME_FORMATS}; {@code null}
     *     or blank when there is no date
     * @return the day as written in the string; {@code null} if the string is {@code null}, blank, or matches none of
     *     the supported formats
     * @since 0.1.0
     */
    @Nullable
    public static LocalDate parseDate(@Nullable final String str)
    {
        return parseWithFirstMatchingFormat(str, format -> LocalDate.from(format.parse(str)));
    }

    /**
     * Reads a date string with the first of the supported formats that matches it.
     *
     * @param <T> the type the string is read into, such as {@code LocalDate}
     * @param str a date or timestamp in one of the {@link #DATETIME_FORMATS}; {@code null} or blank when there is
     *     no date
     * @param reading reads the string with one format, throwing a {@code DateTimeException} when it does not match
     * @return what the first matching format read; {@code null} if the string is {@code null} or blank, or no format
     *     matches it
     */
    private static <T> T parseWithFirstMatchingFormat(final String str, final Function<DateTimeFormatter, T> reading)
    {
        if (StringUtils.isBlank(str)) {
            return null;
        }
        return DATETIME_FORMATS.stream().map(format -> {
            try {
                return reading.apply(format);
            } catch (final DateTimeException ex) {
                // This format does not match, so try the next one
                return null;
            }
        }).filter(Objects::nonNull).findFirst().orElse(null);
    }

    /**
     * The start of a date's day, in the date's own zone.
     *
     * @param date the date
     * @return the same day at {@code 00:00}
     */
    @NotNull
    public static ZonedDateTime atMidnight(@NotNull final ZonedDateTime date)
    {
        return date.truncatedTo(ChronoUnit.DAYS);
    }

    /**
     * The start of a date's day, in the date's own time zone.
     *
     * @param date the date, which is left unchanged
     * @return a new calendar on the same day at {@code 00:00}
     */
    @NotNull
    public static Calendar atMidnight(@NotNull final Calendar date)
    {
        final Calendar result = (Calendar) date.clone();
        result.set(Calendar.HOUR_OF_DAY, 0);
        result.set(Calendar.MINUTE, 0);
        result.set(Calendar.SECOND, 0);
        result.set(Calendar.MILLISECOND, 0);
        return result;
    }

    /**
     * Writes a date in the {@link #PREFERRED_DATETIME_FORMAT}, in the calendar's own time zone.
     *
     * @param date the date; {@code null} when there is none
     * @return the date, such as {@code 1999-12-31T20:30:00.000+05:00}; {@code null} if the date is {@code null}
     */
    @Nullable
    public static String toString(@Nullable final Calendar date)
    {
        if (date == null) {
            return null;
        }
        try {
            return PREFERRED_DATETIME_FORMAT.format(date.toInstant().atZone(date.getTimeZone().toZoneId()));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Writes a date in the {@link #PREFERRED_DATETIME_FORMAT}.
     *
     * @param date a date with a time and an offset, such as a {@link ZonedDateTime}; {@code null} when there is none
     * @return the date, such as {@code 1999-12-31T20:30:00.000+05:00}; {@code null} if the date is {@code null}, or
     *         lacks a time or an offset, as a {@link LocalDate} does
     */
    @Nullable
    public static String toString(@Nullable final TemporalAccessor date)
    {
        if (date == null) {
            return null;
        }
        try {
            return PREFERRED_DATETIME_FORMAT.format(date);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Rewrites a date in the {@link #PREFERRED_DATETIME_FORMAT}. A date without a time gets midnight, and one
     * without an offset gets the JVM's default time zone.
     *
     * @param date a date in one of the {@link #DATETIME_FORMATS}; {@code null} or blank when there is no date
     * @return the date, such as {@code 1999-12-31T20:30:00.000+05:00}; {@code null} if the string is {@code null},
     *         blank, or matches none of the formats
     */
    @Nullable
    public static String normalize(@Nullable final String date)
    {
        return toString(parseDateTime(date));
    }
}

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
 * Various utility methods for working with dates.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class DateUtils
{
    /**
     * The preferred date format, as a formatter object that can format {@code ZonedDateTime} instances. The
     * {@code xxx} offset pattern always writes a numeric offset, so a zero UTC offset is serialized as
     * {@code +00:00} rather than {@code Z}.
     */
    public static final DateTimeFormatter PREFERRED_DATETIME_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx");

    /** Supported date formats. */
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

    /**
     * Hide the utility class constructor.
     */
    private DateUtils()
    {
        // Nothing to do, just making the class uninstantiable
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
     * Parses a date from the given input string as a {@link Calendar} object.
     *
     * @param str the serialized date to parse
     * @return the parsed date, or {@code null} if the date cannot be parsed
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
     * Parses a date from the given input string as a {@link ZonedDateTime} object.
     *
     * @param str the serialized date to parse
     * @return the parsed date, or {@code null} if the date cannot be parsed
     */
    @Nullable
    public static ZonedDateTime parseDateTime(@Nullable final String str)
    {
        return parseWithFirstMatchingFormat(str, format -> {
            // The date string may or may not have a timezone offset, try both a zoned and a local datetime
            final TemporalAccessor result = format.parseBest(str, ZonedDateTime::from, LocalDateTime::from);
            // No timezone in the string means the system default
            return result instanceof ZonedDateTime zoned ? zoned
                : LocalDateTime.from(result).atZone(ZoneId.systemDefault());
        });
    }

    /**
     * Parses the day out of the given input string, ignoring any time and zone it also carries.
     *
     * <p>
     * Use it to read a stored {@code date} answer. That is usually the bare {@code yyyy-MM-dd} a date input stores,
     * but may be a full timestamp, whose offset must not move the day to the reader's zone.
     * </p>
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
        // The day is read straight off the string, before any offset can move it
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
        // The formats are listed in descending order of specificity
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
     * Return a copy of the passed date at midnight.
     *
     * @param date the source date+time, will not be modified.
     * @return a date instance at the same date, but at midnight
     */
    @NotNull
    public static ZonedDateTime atMidnight(@NotNull final ZonedDateTime date)
    {
        return date.truncatedTo(ChronoUnit.DAYS);
    }

    /**
     * Return a copy of the passed date at midnight.
     *
     * @param date the source date+time, will not be modified.
     * @return a date instance at the same date, but at midnight
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
     * Serialize a date to the canonical format expected by JCR.
     *
     * @param date a date object, may be null
     * @return a date serialization in the {@code 1999-12-31T20:30:00.000+05:00} format, using the date's own timezone,
     *         or {@code null} if the input date was {@code null}
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
     * Serialize a date to the canonical format expected by JCR.
     *
     * @param date a date object, may be null
     * @return a date serialization in the {@code 1999-12-31T20:30:00.000+05:00} format, or {@code null} if the input
     *         date was {@code null}
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
     * Reformat a date string to the format expected by JCR. This may involve adding missing information, e.g. setting
     * the time to midnight if no time is provided, or using the system default timezone if one isn't specified.
     *
     * @param date a date string in any of the supported {@link #DATETIME_FORMATS formats} may be {@code null}
     * @return a date serialization in the {@code 1999-12-31T20:30:00.000+05:00} format, or {@code null} if the input
     *         date was {@code null} or in an unsupported format
     */
    @Nullable
    public static String normalize(@Nullable final String date)
    {
        return toString(parseDateTime(date));
    }
}

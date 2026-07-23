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
import java.util.Locale;
import java.util.function.Function;

import io.uhndata.iap.utils.DateUtils;

/**
 * How operand values are to be treated when performing a comparison. There is no declared comparison type on
 * conditions: the evaluator unifies what the two sides are known to hold — the type a resolver declares (e.g. the
 * referenced question's data type), or failing that the intrinsic type of the stored values — and each type here
 * turns raw values into mutually comparable objects.
 *
 * @version $Id$
 * @since 0.1.0
 */
@SuppressWarnings("unchecked")
public enum OperandType
{
    /** Compare values as strings, the default. */
    TEXT(value -> (Comparable<Object>) (Object) value.toString()),

    /** Compare values as booleans; strings other than {@code true} (ignoring case) parse as {@code false}. */
    BOOLEAN(value -> (Comparable<Object>) (value instanceof Boolean
        ? value : Boolean.parseBoolean(value.toString()))),

    /** Compare values as integer numbers. */
    LONG(value -> {
        try {
            return (Comparable<Object>) (Object) (value instanceof Number
                ? ((Number) value).longValue() : Long.parseLong(value.toString().trim()));
        } catch (final NumberFormatException ex) {
            return null;
        }
    }),

    /** Compare values as floating point numbers. */
    DOUBLE(value -> {
        try {
            return (Comparable<Object>) (Object) (value instanceof Number
                ? ((Number) value).doubleValue() : Double.parseDouble(value.toString().trim()));
        } catch (final NumberFormatException ex) {
            return null;
        }
    }),

    /** Compare values as arbitrary-precision decimal numbers. */
    DECIMAL(value -> {
        try {
            return (Comparable<Object>) (value instanceof BigDecimal
                ? value : new BigDecimal(value.toString().trim()));
        } catch (final NumberFormatException ex) {
            return null;
        }
    }),

    /** Compare values as dates, parsed leniently from the formats accepted by {@link DateUtils#parseCalendar}. */
    DATE(value -> {
        if (value instanceof Calendar) {
            return (Comparable<Object>) value;
        }
        if (value instanceof Date) {
            final Calendar calendar = Calendar.getInstance();
            calendar.setTime((Date) value);
            return (Comparable<Object>) (Object) calendar;
        }
        return (Comparable<Object>) (Object) DateUtils.parseCalendar(value.toString());
    });

    private final Function<Object, Comparable<Object>> valueCoercer;

    OperandType(final Function<Object, Comparable<Object>> valueCoercer)
    {
        this.valueCoercer = valueCoercer;
    }

    /**
     * Turn a raw value into an object of this type that can be compared with other coerced values.
     *
     * @param rawValue a raw value, as stored in a condition operand or read from the repository, may be
     *            {@code null}
     * @return a comparable object, or {@code null} if the value is {@code null} or cannot be interpreted as this
     *         type
     */
    public Comparable<Object> coerce(final Object rawValue)
    {
        return rawValue == null ? null : this.valueCoercer.apply(rawValue);
    }

    /**
     * Whether values of this type are numbers, e.g. suitable for summing.
     *
     * @return {@code true} for the numeric types
     */
    public boolean isNumeric()
    {
        return this == LONG || this == DOUBLE || this == DECIMAL;
    }

    /**
     * Convert a stored type label, e.g. a question's declared data type, into an enum item.
     *
     * @param label the stored label
     * @return an enum instance, {@code TEXT} if the passed label isn't one of the known types
     */
    public static OperandType parse(final String label)
    {
        try {
            return valueOf(label.toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException | NullPointerException ex) {
            return TEXT;
        }
    }

    /**
     * The type a stored value intrinsically holds, judging by its Java type — the fallback used when no type is
     * declared for an operand. Strings (and unrecognized types) deliberately infer nothing rather than
     * {@code TEXT}: a string is the flexible side of a comparison, coercible to whatever the other side calls for.
     *
     * @param value a raw stored value
     * @return an enum instance, or {@code null} if the value doesn't determine a type
     */
    public static OperandType infer(final Object value)
    {
        if (value instanceof Boolean) {
            return BOOLEAN;
        }
        if (value instanceof BigDecimal) {
            return DECIMAL;
        }
        if (value instanceof Number) {
            return value instanceof Double || value instanceof Float ? DOUBLE : LONG;
        }
        if (value instanceof Calendar || value instanceof Date) {
            return DATE;
        }
        return null;
    }
}

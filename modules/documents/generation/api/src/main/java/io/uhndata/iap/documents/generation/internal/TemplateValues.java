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
package io.uhndata.iap.documents.generation.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

/**
 * Turns the values {@link io.uhndata.iap.documents.generation.spi.TemplateContextProvider providers} supply into
 * the JSON the worker receives, accepting the types that SPI lists and nothing else.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class TemplateValues
{
    private TemplateValues()
    {
        // Utility
    }

    /**
     * Convert one value, and everything nested in it.
     *
     * @param value the value to convert
     * @param path where the value sits, e.g. {@code submission.answers}, to name it if it cannot be converted
     * @return its JSON form
     * @throws IllegalArgumentException when the value, or something nested in it, is of a type templates cannot
     *             hold, naming where it is
     */
    static JsonValue toJson(final Object value, final String path)
    {
        return switch (value) {
            case JsonValue json -> json;
            case CharSequence text -> Json.createValue(text.toString());
            case Boolean flag -> flag ? JsonValue.TRUE : JsonValue.FALSE;
            case Number number -> number(number, path);
            case Enum<?> constant -> Json.createValue(constant.name());
            case null, default -> dateOrCollection(value, path);
        };
    }

    private static JsonValue dateOrCollection(final Object value, final String path)
    {
        return switch (value) {
            case null -> JsonValue.NULL;
            case Calendar calendar -> dateTime(calendar.toInstant().atZone(calendar.getTimeZone().toZoneId()));
            // Not toInstant(), which java.sql.Date and Time refuse
            case Date date -> dateTime(Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()));
            // Its toString() appends [Zone], which is not ISO-8601
            case ZonedDateTime zoned -> dateTime(zoned);
            // The other java.time types print themselves as ISO-8601
            case TemporalAccessor temporal -> Json.createValue(temporal.toString());
            case Map<?, ?> map -> object(map, path);
            case Iterable<?> items -> array(items, path);
            case Object[] items -> array(Arrays.asList(items), path);
            default -> throw new IllegalArgumentException(
                path + " is a " + value.getClass().getName() + ", which a template cannot hold");
        };
    }

    private static JsonValue dateTime(final ZonedDateTime dateTime)
    {
        return Json.createValue(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(dateTime));
    }

    private static JsonValue number(final Number number, final String path)
    {
        if (number instanceof Integer || number instanceof Long || number instanceof Short
            || number instanceof Byte) {
            return Json.createValue(number.longValue());
        }
        if (number instanceof BigInteger big) {
            return Json.createValue(big);
        }
        if (number instanceof BigDecimal decimal) {
            return Json.createValue(decimal);
        }
        final double floating = number.doubleValue();
        if (!Double.isFinite(floating)) {
            throw new IllegalArgumentException(path + " is " + floating + ", which JSON cannot hold");
        }
        return Json.createValue(floating);
    }

    private static JsonValue object(final Map<?, ?> map, final String path)
    {
        final JsonObjectBuilder object = Json.createObjectBuilder();
        for (final Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(path + " has a key that is not text: " + entry.getKey());
            }
            object.add(key, toJson(entry.getValue(), path + "." + key));
        }
        return object.build();
    }

    private static JsonValue array(final Iterable<?> items, final String path)
    {
        final JsonArrayBuilder array = Json.createArrayBuilder();
        int index = 0;
        for (final Object item : items) {
            array.add(toJson(item, path + "[" + index + "]"));
            ++index;
        }
        return array.build();
    }
}

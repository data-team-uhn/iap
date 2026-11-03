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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import jakarta.json.Json;
import jakarta.json.JsonValue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TemplateValues}: every type the SPI promises arrives as JSON a template can use, and anything
 * else is refused with where it sits, instead of reaching a letter as its {@code toString()}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class TemplateValuesTest
{
    private enum Decision
    {
        APPROVED
    }

    @Test
    void plainValuesKeepTheirJsonType()
    {
        assertEquals(Json.createValue("text"), TemplateValues.toJson("text", "x"));
        assertEquals(JsonValue.TRUE, TemplateValues.toJson(true, "x"));
        assertEquals(JsonValue.FALSE, TemplateValues.toJson(false, "x"));
        assertEquals(JsonValue.NULL, TemplateValues.toJson(null, "x"));
        assertEquals(JsonValue.EMPTY_JSON_OBJECT, TemplateValues.toJson(JsonValue.EMPTY_JSON_OBJECT, "x"));
    }

    @Test
    void numbersKeepTheirPrecision()
    {
        assertEquals(Json.createValue(3L), TemplateValues.toJson(3, "x"));
        assertEquals(Json.createValue(new BigInteger("123456789012345678901234567890")),
            TemplateValues.toJson(new BigInteger("123456789012345678901234567890"), "x"));
        assertEquals(Json.createValue(new BigDecimal("2.50")), TemplateValues.toJson(new BigDecimal("2.50"), "x"));
        assertEquals(Json.createValue(0.5), TemplateValues.toJson(0.5f, "x"));
    }

    @Test
    void anEnumIsItsName()
    {
        assertEquals(Json.createValue("APPROVED"), TemplateValues.toJson(Decision.APPROVED, "x"));
    }

    @Test
    void datesBecomeIsoTextInTheirOwnTimeZone()
    {
        final Calendar calendar = new GregorianCalendar(TimeZone.getTimeZone("America/Toronto"));
        calendar.clear();
        calendar.set(2026, Calendar.OCTOBER, 20, 9, 30);

        assertEquals(Json.createValue("2026-10-20T09:30:00-04:00"), TemplateValues.toJson(calendar, "x"));
        assertEquals(Json.createValue("2026-10-20"), TemplateValues.toJson(LocalDate.of(2026, 10, 20), "x"));
        // A Date has no zone of its own, so it is read in the platform's
        assertEquals(Json.createValue(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            calendar.toInstant().atZone(ZoneId.systemDefault()))), TemplateValues.toJson(calendar.getTime(), "x"));
    }

    @Test
    void aZonedDateTimeLeavesItsZoneNameOut()
    {
        assertEquals(Json.createValue("2026-10-20T09:30:00-04:00"), TemplateValues.toJson(
            ZonedDateTime.of(2026, 10, 20, 9, 30, 0, 0, ZoneId.of("America/Toronto")), "x"));
    }

    @Test
    void anSqlDateIsTakenToo()
    {
        final LocalDate day = LocalDate.of(2026, 10, 20);

        assertEquals(Json.createValue(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            day.atStartOfDay(ZoneId.systemDefault()))), TemplateValues.toJson(java.sql.Date.valueOf(day), "x"));
    }

    @Test
    void aNullInsideACollectionStaysNull()
    {
        final Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("conditions", null);

        assertEquals(Json.createObjectBuilder().addNull("conditions").build(),
            TemplateValues.toJson(withNull, "x"));
    }

    @Test
    void mapsListsAndArraysNest()
    {
        final Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("title", "Test my drug");
        submission.put("reviewers", List.of(Map.of("name", "X"), Map.of("name", "Y")));
        submission.put("tags", new String[] { "urgent" });

        assertEquals(
            Json.createObjectBuilder()
                .add("title", "Test my drug")
                .add("reviewers", Json.createArrayBuilder()
                    .add(Json.createObjectBuilder().add("name", "X"))
                    .add(Json.createObjectBuilder().add("name", "Y")))
                .add("tags", Json.createArrayBuilder().add("urgent"))
                .build(),
            TemplateValues.toJson(submission, "submission"));
    }

    @Test
    void anUnknownTypeIsRefusedNamingWhereItSits()
    {
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> TemplateValues.toJson(Map.of("answers", List.of(new Object())), "submission"));

        assertTrue(refused.getMessage().startsWith("submission.answers[0] is a java.lang.Object"),
            refused.getMessage());
    }

    @Test
    void aKeyThatIsNotTextIsRefused()
    {
        assertThrows(IllegalArgumentException.class, () -> TemplateValues.toJson(Map.of(1, "one"), "x"));
    }

    @Test
    void aNumberJsonCannotHoldIsRefused()
    {
        assertThrows(IllegalArgumentException.class, () -> TemplateValues.toJson(Double.NaN, "x"));
    }
}

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
package io.uhndata.iap.statistics.api;

import jakarta.json.JsonObject;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MetricValue}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class MetricValueTest
{
    @Test
    void carriesEverythingItWasToldAndNothingElse()
    {
        final MetricValue value = MetricValue.of("timeToAuth", "Time to authorization")
            .describedAs("How long it takes")
            .inCategory("Turnaround")
            .measuredIn("days")
            .valued(32.5, 120)
            .splitBy(new MetricValue.Slice("achen", 30.0, 80))
            .over(new MetricValue.Slice("2026-08", 31.0, 60))
            .build();

        assertEquals("timeToAuth", value.getName());
        assertEquals("Time to authorization", value.getLabel());
        assertEquals("How long it takes", value.getDescription());
        assertEquals("Turnaround", value.getCategory());
        assertEquals("days", value.getUnit());
        assertEquals(32.5, value.getValue());
        assertEquals(120, value.getSampleSize());
        assertEquals(1, value.getBreakdown().size());
        assertEquals("achen", value.getBreakdown().get(0).key());
        assertEquals(1, value.getSeries().size());
        assertEquals("2026-08", value.getSeries().get(0).key());
    }

    @Test
    void leavesOutWhatItWasNotTold()
    {
        final MetricValue value = MetricValue.of("bare", "Bare").valued(null, 0).build();

        assertNull(value.getDescription());
        assertNull(value.getCategory());
        assertNull(value.getUnit());
        assertNull(value.getValue());
        assertTrue(value.getBreakdown().isEmpty());
        assertTrue(value.getSeries().isEmpty());
    }

    @Test
    void writesItselfAsJson()
    {
        final JsonObject json = MetricValue.of("timeToAuth", "Time to authorization")
            .describedAs("How long")
            .inCategory("Turnaround")
            .measuredIn("days")
            .valued(32.5, 120)
            .splitBy(new MetricValue.Slice("achen", 30.0, 80))
            .over(new MetricValue.Slice("2026-08", 31.0, 60))
            .build()
            .toJson();

        assertEquals("timeToAuth", json.getString("name"));
        assertEquals("Time to authorization", json.getString("label"));
        assertEquals("How long", json.getString("description"));
        assertEquals("Turnaround", json.getString("category"));
        assertEquals("days", json.getString("unit"));
        assertEquals(32.5, json.getJsonNumber("value").doubleValue());
        assertEquals(120, json.getInt("sampleSize"));
        assertEquals("achen", json.getJsonArray("breakdown").getJsonObject(0).getString("key"));
        assertEquals(80, json.getJsonArray("breakdown").getJsonObject(0).getInt("sampleSize"));
        assertEquals("2026-08", json.getJsonArray("series").getJsonObject(0).getString("key"));
    }

    // Null rather than absent and rather than zero: a client that showed an unmeasured metric as 0
    // would be telling its reader something nobody measured
    @Test
    void writesAnUnmeasuredValueAsNull()
    {
        final JsonObject json = MetricValue.of("bare", "Bare")
            .valued(null, 0)
            .splitBy(new MetricValue.Slice("nobody", null, 0))
            .build()
            .toJson();

        assertTrue(json.isNull("value"));
        assertTrue(json.getJsonArray("breakdown").getJsonObject(0).isNull("value"));
        assertTrue(json.getJsonArray("series").isEmpty());
        assertEquals(0, json.getInt("sampleSize"));
    }

    @Test
    void leavesTheOptionalFieldsOutOfTheJsonEntirely()
    {
        final JsonObject json = MetricValue.of("bare", "Bare").valued(1.0, 1).build().toJson();

        assertTrue(!json.containsKey("description"));
        assertTrue(!json.containsKey("category"));
        assertTrue(!json.containsKey("unit"));
    }
}

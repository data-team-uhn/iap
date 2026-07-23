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
package io.uhndata.iap.status.spi;

import jakarta.json.JsonObject;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for the {@link StatusReport} value object.
 *
 * @version $Id$
 * @since 0.1.0
 */
class StatusReportTest
{
    @Test
    void exposesItsComponents()
    {
        final StatusReport report = new StatusReport("Uptime", StatusReport.Status.INFO, "All day long");

        assertEquals("Uptime", report.getName());
        assertEquals(StatusReport.Status.INFO, report.getStatus());
        assertEquals("All day long", report.getText());
        assertEquals("Uptime: INFO", report.toString());
    }

    @Test
    void serializesToJson()
    {
        final JsonObject json = new StatusReport("Uptime", StatusReport.Status.SUCCESS, "All day long").toJson();

        assertEquals("Uptime", json.getString("name"));
        assertEquals("SUCCESS", json.getString("status"));
        assertEquals("All day long", json.getString("text"));
    }

    @Test
    void toleratesMissingText()
    {
        final StatusReport report = new StatusReport("Silence", StatusReport.Status.WARNING, null);

        assertNull(report.getText());
        assertEquals("", report.toJson().getString("text"));
    }
}

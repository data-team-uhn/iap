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
package io.uhndata.iap.status.internal;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.Test;

import io.uhndata.iap.status.spi.StatusReport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link UptimeStatusReporter}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class UptimeStatusReporterTest
{
    private final UptimeStatusReporter reporter = new UptimeStatusReporter();

    @Test
    void reportsTheStartupTime()
    {
        final StatusReport report = this.reporter.report(true);

        assertEquals("System Started", report.getName());
        assertEquals(StatusReport.Status.INFO, report.getStatus());
        // The reported time is a valid ISO timestamp, not later than the present
        final ZonedDateTime started = ZonedDateTime.parse(report.getText(), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        assertFalse(started.isAfter(ZonedDateTime.now()));
        // The startup time is not sensitive, the same report is served in unprivileged mode
        assertEquals(report.getText(), this.reporter.report(false).getText());
    }

    @Test
    void describesItself()
    {
        assertEquals("System Started", this.reporter.getName());
        assertTrue(this.reporter.getTags().contains("status"));
        assertTrue(this.reporter.getTags().contains("systemStarted"));
    }
}

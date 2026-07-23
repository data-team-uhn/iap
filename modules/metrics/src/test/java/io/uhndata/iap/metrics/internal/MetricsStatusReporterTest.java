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
package io.uhndata.iap.metrics.internal;

import java.lang.reflect.Field;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.metrics.api.Metric;
import io.uhndata.iap.metrics.api.MetricsManager;
import io.uhndata.iap.status.spi.StatusReport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link MetricsStatusReporter}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class MetricsStatusReporterTest
{
    private static final ZonedDateTime RESET_DATE = ZonedDateTime.of(2026, 7, 20, 8, 30, 0, 0, ZoneOffset.UTC);

    private final MetricsManager manager = Mockito.mock(MetricsManager.class);

    private MetricsStatusReporter reporter;

    @BeforeEach
    void setUp() throws Exception
    {
        this.reporter = new MetricsStatusReporter();
        final Field reference = MetricsStatusReporter.class.getDeclaredField("metricsManager");
        reference.setAccessible(true);
        reference.set(this.reporter, this.manager);
    }

    @Test
    void identifiesItself()
    {
        assertEquals("Metrics", this.reporter.getName());
        assertEquals(Set.of("metrics", "activity"), this.reporter.getTags());
    }

    @Test
    void nothingToReportWithoutMetrics()
    {
        final List<Metric> metrics = List.of();
        Mockito.when(this.manager.getMetrics()).thenReturn(metrics);
        assertNull(this.reporter.report(false));
    }

    @Test
    void nothingToReportWhenAllMetricsAreRestricted()
    {
        final List<Metric> metrics = List.of(
            metric("Secret", null, Metric.AccessLevel.ADMIN, 5, 0, 0, null));
        Mockito.when(this.manager.getMetrics()).thenReturn(metrics);
        assertNull(this.reporter.report(true));
    }

    @Test
    void listsMetricsWithoutHeadingsWhenNothingIsCategorized()
    {
        final List<Metric> metrics = List.of(
            metric("First metric", null, Metric.AccessLevel.PUBLIC, 12, 0, 0, null),
            metric("Second metric", null, Metric.AccessLevel.PUBLIC, 7, 3, 5, RESET_DATE));
        Mockito.when(this.manager.getMetrics()).thenReturn(metrics);

        final StatusReport report = this.reporter.report(false);

        assertEquals("Metrics", report.getName());
        assertEquals(StatusReport.Status.INFO, report.getStatus());
        assertEquals("- First metric: 12\n"
            + "- Second metric: 7 (+3 since 2026-07-20; previous period: +5)", report.getText());
    }

    @Test
    void groupsMetricsByCategory()
    {
        final List<Metric> metrics = List.of(
            metric("Loose metric", null, Metric.AccessLevel.PUBLIC, 1, 0, 0, null),
            metric("Submitted", "Submissions", Metric.AccessLevel.PUBLIC, 12, -2, 4, RESET_DATE),
            metric("Errors", "Problems", Metric.AccessLevel.PUBLIC, 3, 0, 0, null));
        Mockito.when(this.manager.getMetrics()).thenReturn(metrics);

        final StatusReport report = this.reporter.report(false);

        assertEquals("Uncategorized:\n"
            + "- Loose metric: 1\n"
            + "\n"
            + "Problems:\n"
            + "- Errors: 3\n"
            + "\n"
            + "Submissions:\n"
            + "- Submitted: 12 (-2 since 2026-07-20; previous period: +4)", report.getText());
    }

    @Test
    void unprivilegedReportsOnlyIncludePublicMetrics()
    {
        final List<Metric> metrics = List.of(
            metric("Visible", null, Metric.AccessLevel.PUBLIC, 4, 0, 0, null),
            metric("Secret", null, Metric.AccessLevel.ADMIN, 5, 0, 0, null));
        Mockito.when(this.manager.getMetrics()).thenReturn(metrics);

        assertEquals("- Visible: 4", this.reporter.report(true).getText());
    }

    @Test
    void privilegedReportsIncludeRestrictedMetrics()
    {
        final List<Metric> metrics = List.of(
            metric("Visible", null, Metric.AccessLevel.PUBLIC, 4, 0, 0, null),
            metric("Secret", null, Metric.AccessLevel.ADMIN, 5, 0, 0, null));
        Mockito.when(this.manager.getMetrics()).thenReturn(metrics);

        assertEquals("- Visible: 4\n- Secret: 5", this.reporter.report(false).getText());
    }

    private Metric metric(final String label, final String category, final Metric.AccessLevel accessLevel,
        final long currentValue, final long currentDelta, final long lastDelta, final ZonedDateTime lastRollover)
    {
        final Metric mocked = Mockito.mock(Metric.class);
        Mockito.when(mocked.getLabel()).thenReturn(label);
        Mockito.when(mocked.getCategory()).thenReturn(category);
        Mockito.when(mocked.getAccessLevel()).thenReturn(accessLevel);
        Mockito.when(mocked.getCurrentValue()).thenReturn(currentValue);
        Mockito.when(mocked.getCurrentDelta()).thenReturn(currentDelta);
        Mockito.when(mocked.getLastDelta()).thenReturn(lastDelta);
        Mockito.when(mocked.getLastRollover()).thenReturn(lastRollover);
        return mocked;
    }
}

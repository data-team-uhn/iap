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

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.osgi.framework.Constants;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.errortracking.api.ErrorLoggerService;
import io.uhndata.iap.status.spi.StatusReport;
import io.uhndata.iap.status.spi.StatusReporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link StatusReportManagerImpl}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class StatusReportManagerImplTest
{
    /**
     * A reporter that returns a fixed report.
     *
     * @since 0.1.0
     */
    private static final class FakeReporter implements StatusReporter
    {
        private final String name;

        private final Set<String> tags;

        private final StatusReport report;

        FakeReporter(final String name, final Set<String> tags, final StatusReport report)
        {
            this.name = name;
            this.tags = tags;
            this.report = report;
        }

        @Override
        public String getName()
        {
            return this.name;
        }

        @Override
        public Set<String> getTags()
        {
            return this.tags;
        }

        @Override
        public StatusReport report(final boolean unprivileged)
        {
            return this.report;
        }
    }

    private final SlingContext context = new SlingContext();

    private final StatusReporter uptime = new FakeReporter("Uptime", Set.of("status"),
        new StatusReport("Uptime", StatusReport.Status.INFO, "All day long"));

    private final StatusReporter problems = new FakeReporter("Problems", Set.of("problems"),
        new StatusReport("Problems", StatusReport.Status.WARNING, "Something is off"));

    private final StatusReporter chatter = new FakeReporter("Chatter", Set.of("status"),
        new StatusReport("Chatter", StatusReport.Status.DEBUG, "Noise"));

    private final StatusReporter silent = new FakeReporter("Silent", Set.of("status"), null);

    /** What the broken reporter throws, its message naming infrastructure no reader should see. */
    private final IllegalStateException failure = new IllegalStateException("cannot reach db.internal:5432");

    private final StatusReporter broken = new StatusReporter()
    {
        @Override
        public String getName()
        {
            return "Broken";
        }

        @Override
        public Set<String> getTags()
        {
            return Set.of("problems");
        }

        @Override
        public StatusReport report(final boolean unprivileged)
        {
            throw StatusReportManagerImplTest.this.failure;
        }
    };

    @Test
    void collectsReportsAboveTheTargetLevel()
    {
        final StatusReportManagerImpl manager = managerWith(this.uptime, this.problems, this.chatter, this.silent);

        // INFO by default, so the DEBUG report is left out. The silent reporter has no report
        assertEquals(List.of("Uptime", "Problems"),
            manager.getReports(true).stream().map(StatusReport::getName).toList());
        assertEquals(3, manager.getReports(true, StatusReport.Status.DEBUG, null).size());
        assertEquals(List.of("Problems"),
            manager.getReports(true, StatusReport.Status.WARNING, null).stream()
                .map(StatusReport::getName).toList());
    }

    @Test
    void filtersByTags()
    {
        final StatusReportManagerImpl manager = managerWith(this.uptime, this.problems, this.chatter, this.silent);

        assertEquals(List.of("Problems"),
            manager.getReports(true, StatusReport.Status.INFO, Set.of("problems")).stream()
                .map(StatusReport::getName).toList());
        // Null or empty tags include all reporters
        assertEquals(2, manager.getReports(true, StatusReport.Status.INFO, null).size());
        assertEquals(2, manager.getReports(true, StatusReport.Status.INFO, Set.of()).size());
        assertTrue(manager.getReports(true, StatusReport.Status.INFO, Set.of("unknown")).isEmpty());
    }

    @Test
    void isolatesBrokenReporters()
    {
        final List<StatusReport> reports = managerWith(this.broken, this.uptime).getReports(true);

        assertEquals(2, reports.size());
        assertEquals("Broken", reports.get(0).getName());
        assertEquals(StatusReport.Status.ERROR, reports.get(0).getStatus());
        assertEquals("Uptime", reports.get(1).getName());
    }

    @Test
    void aFailedReportDoesNotQuoteTheException()
    {
        final StatusReportManagerImpl manager = managerWith(this.broken);

        // Not even to the administrator: the text ends up in screenshots and tickets
        for (final boolean unprivileged : new boolean[] { true, false }) {
            final String text = manager.getReports(unprivileged).get(0).getText();
            assertEquals("Failed to compute the report, the instance log has the reason", text);
            assertFalse(text.contains("db.internal"));
        }
    }

    @Test
    void recordsAFailedReportAgainstItsReporter()
    {
        final ErrorLoggerService recorder = Mockito.mock(ErrorLoggerService.class);
        ErrorLogger.setService(recorder);
        try {
            managerWith(this.broken, this.uptime).getReports(true);

            final ArgumentCaptor<ErrorContext> recorded = ArgumentCaptor.forClass(ErrorContext.class);
            Mockito.verify(recorder).logError(Mockito.same(this.failure), recorded.capture());
            assertEquals(this.broken.getClass().getName(), recorded.getValue().getComponent());
            assertEquals("report", recorded.getValue().getOperation());
            assertEquals("Broken", recorded.getValue().getDetails().get("reporter"));
            Mockito.verifyNoMoreInteractions(recorder);
        } finally {
            ErrorLogger.unsetService(recorder);
        }
    }

    @Test
    void reportsNothingWithoutReporters()
    {
        assertTrue(managerWith().getReports(true).isEmpty());
    }

    /**
     * Registers the reporters, ranked so that the manager asks them in the order given, then activates a manager
     * that binds them.
     */
    private StatusReportManagerImpl managerWith(final StatusReporter... reporters)
    {
        IntStream.range(0, reporters.length).forEach(rank -> this.context.registerService(StatusReporter.class,
            reporters[rank], Constants.SERVICE_RANKING, rank));
        return this.context.registerInjectActivateService(StatusReportManagerImpl.class);
    }
}

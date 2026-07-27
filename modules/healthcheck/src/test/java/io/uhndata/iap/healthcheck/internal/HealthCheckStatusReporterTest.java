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
package io.uhndata.iap.healthcheck.internal;

import java.lang.reflect.Field;
import java.util.List;

import org.apache.felix.hc.api.Result;
import org.apache.felix.hc.api.execution.HealthCheckExecutionResult;
import org.apache.felix.hc.api.execution.HealthCheckExecutor;
import org.apache.felix.hc.api.execution.HealthCheckMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.status.spi.StatusReport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HealthCheckStatusReporter}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class HealthCheckStatusReporterTest
{
    private final HealthCheckStatusReporter reporter = new HealthCheckStatusReporter();

    private final HealthCheckExecutor executor = Mockito.mock(HealthCheckExecutor.class);

    @BeforeEach
    void setUp() throws Exception
    {
        final Field reference = HealthCheckStatusReporter.class.getDeclaredField("hc");
        reference.setAccessible(true);
        reference.set(this.reporter, this.executor);
    }

    @Test
    void reportsSuccessWhenAllChecksPass()
    {
        configure(check("Fine check", Result.Status.OK));

        final StatusReport report = this.reporter.report(true);

        assertEquals("Health Check", report.getName());
        assertEquals(StatusReport.Status.SUCCESS, report.getStatus());
        assertEquals("All is good!", report.getText());
    }

    @Test
    void warningChecksProduceAWarningReport()
    {
        configure(check("Fine check", Result.Status.OK), check("Grumbling check", Result.Status.WARN));

        final StatusReport report = this.reporter.report(true);

        assertEquals(StatusReport.Status.WARNING, report.getStatus());
        // An unprivileged report only mentions the number of failed checks, not their names
        assertEquals("There are 1 failed checks", report.getText());
    }

    @Test
    void privilegedReportsNameTheFailedChecks()
    {
        configure(check("Grumbling check", Result.Status.WARN));

        final StatusReport report = this.reporter.report(false);

        assertTrue(report.getText().startsWith("There are 1 failed checks"));
        assertTrue(report.getText().contains("Grumbling check"));
        // The successful checks are not listed
        assertFalse(report.getText().contains("Fine check"));
    }

    @Test
    void seriousFailuresProduceAnErrorReport()
    {
        configure(check("Broken check", Result.Status.CRITICAL));
        assertEquals(StatusReport.Status.ERROR, this.reporter.report(true).getStatus());

        configure(check("Paused check", Result.Status.TEMPORARILY_UNAVAILABLE));
        assertEquals(StatusReport.Status.ERROR, this.reporter.report(true).getStatus());

        configure(check("Buggy check", Result.Status.HEALTH_CHECK_ERROR));
        assertEquals(StatusReport.Status.ERROR, this.reporter.report(true).getStatus());

        // The worst failure decides the report status, regardless of the order the checks are reported in
        configure(check("Grumbling check", Result.Status.WARN), check("Broken check", Result.Status.CRITICAL));
        assertEquals(StatusReport.Status.ERROR, this.reporter.report(true).getStatus());
        configure(check("Broken check", Result.Status.CRITICAL), check("Grumbling check", Result.Status.WARN));
        assertEquals(StatusReport.Status.ERROR, this.reporter.report(true).getStatus());
    }

    @Test
    void describesItself()
    {
        assertEquals("Health Check", this.reporter.getName());
        assertTrue(this.reporter.getTags().contains("healthcheck"));
        assertTrue(this.reporter.getTags().contains("problems"));
    }

    private void configure(final HealthCheckExecutionResult... checks)
    {
        Mockito.when(this.executor.execute(Mockito.any(), Mockito.any())).thenReturn(List.of(checks));
    }

    private HealthCheckExecutionResult check(final String name, final Result.Status status)
    {
        final HealthCheckExecutionResult check = Mockito.mock(HealthCheckExecutionResult.class);
        final HealthCheckMetadata metadata = Mockito.mock(HealthCheckMetadata.class);
        final Result result = new Result(status, name + " message");
        Mockito.when(check.getHealthCheckResult()).thenReturn(result);
        Mockito.lenient().when(check.getHealthCheckMetadata()).thenReturn(metadata);
        Mockito.lenient().when(metadata.getName()).thenReturn(name);
        return check;
    }
}

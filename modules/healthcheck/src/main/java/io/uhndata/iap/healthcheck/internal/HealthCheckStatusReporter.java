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

import java.util.List;
import java.util.Set;

import org.apache.felix.hc.api.Result.Status;
import org.apache.felix.hc.api.execution.HealthCheckExecutionOptions;
import org.apache.felix.hc.api.execution.HealthCheckExecutionResult;
import org.apache.felix.hc.api.execution.HealthCheckExecutor;
import org.apache.felix.hc.api.execution.HealthCheckSelector;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.status.spi.StatusReport;
import io.uhndata.iap.status.spi.StatusReporter;

/**
 * Sums up the health checks in a status report. The report runs every health check that has a tag, and is a
 * {@code SUCCESS} when they all pass. Otherwise it gives the number of failed checks, and only a privileged report
 * names them. The worst result decides the level: a {@code WARN} makes a {@code WARNING}, and anything worse makes
 * an {@code ERROR}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class HealthCheckStatusReporter implements StatusReporter
{
    private static final Logger LOGGER = LoggerFactory.getLogger(HealthCheckStatusReporter.class);

    private static final String REPORT_TITLE = "Health Check";

    @Reference
    private HealthCheckExecutor hc;

    @Override
    public String getName()
    {
        return REPORT_TITLE;
    }

    @Override
    public StatusReport report(final boolean unprivileged)
    {
        LOGGER.debug("Gathering health check failures for the status report");
        final List<HealthCheckExecutionResult> failedChecks = this.hc
            .execute(HealthCheckSelector.empty().withTags("*"),
                new HealthCheckExecutionOptions().setOverrideGlobalTimeout(10_000).setForceInstantExecution(true))
            .stream()
            .filter(check -> !check.getHealthCheckResult().isOk())
            .toList();
        if (failedChecks.isEmpty()) {
            return new StatusReport(REPORT_TITLE, StatusReport.Status.SUCCESS, "All is good!");
        }
        LOGGER.warn("There are {} failed checks! {}", failedChecks.size(),
            failedChecks.stream().map(check -> check.getHealthCheckMetadata().getName()).toList());
        final StringBuilder text = new StringBuilder("There are " + failedChecks.size() + " failed checks");
        if (!unprivileged) {
            text.append("\n\n");
            failedChecks.forEach(failed -> text.append(failed.getHealthCheckMetadata().getName()).append("\n"));
        }
        final Status status = failedChecks.stream().map(check -> check.getHealthCheckResult().getStatus())
            .reduce(Status.OK, (worst, next) -> worst.compareTo(next) < 0 ? next : worst);
        return new StatusReport(REPORT_TITLE,
            status.ordinal() >= Status.TEMPORARILY_UNAVAILABLE.ordinal() ? StatusReport.Status.ERROR
                : StatusReport.Status.WARNING,
            text.toString());
    }

    @Override
    public Set<String> getTags()
    {
        return Set.of("problems", "healthcheck");
    }
}

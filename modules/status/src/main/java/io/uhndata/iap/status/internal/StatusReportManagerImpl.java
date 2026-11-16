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

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.FieldOption;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.status.api.StatusReportManager;
import io.uhndata.iap.status.spi.StatusReport;
import io.uhndata.iap.status.spi.StatusReporter;

/**
 * Gathers the reports of the registered {@link StatusReporter} services. If a reporter's {@code report} throws an
 * unchecked exception, an {@link StatusReport.Status#ERROR} report takes its place, and the other reporters still
 * report. That report has the reporter's name, and says only that the report could not be computed: an exception's
 * message can name infrastructure, and the reports reach readers who are not logged in.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component
public class StatusReportManagerImpl implements StatusReportManager
{
    /** The text of the report standing in for a reporter that threw. */
    private static final String FAILED_REPORT_TEXT = "Failed to compute the report, the instance log has the reason";

    private static final Logger LOGGER = LoggerFactory.getLogger(StatusReportManagerImpl.class);

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, fieldOption = FieldOption.REPLACE,
        policy = ReferencePolicy.DYNAMIC)
    private volatile List<StatusReporter> reporters;

    @Override
    public List<StatusReport> getReports(final boolean unprivileged, final StatusReport.Status level,
        final Set<String> tags)
    {
        return this.reporters.stream()
            .filter(reporter -> tags == null || tags.isEmpty() || !Collections.disjoint(reporter.getTags(), tags))
            .map(reporter -> report(reporter, unprivileged))
            .filter(Objects::nonNull)
            .filter(report -> report.getStatus().compareTo(level) >= 0)
            .toList();
    }

    /**
     * Asks one reporter for its report.
     *
     * @param reporter the reporter to ask
     * @param unprivileged {@code true} to ask for a report without confidential details
     * @return the reporter's report; {@code null} if it has nothing to report; an {@code ERROR} report if it threw an
     *         unchecked exception
     */
    private StatusReport report(final StatusReporter reporter, final boolean unprivileged)
    {
        try {
            return reporter.report(unprivileged);
        } catch (final RuntimeException e) {
            LOGGER.warn("Status reporter {} failed", reporter.getName(), e);
            // The ERROR report says something is wrong, not what, so the fault is recorded where an administrator
            // can triage it, against the reporter that needs fixing. A monitor polls this, and a broken reporter
            // fails on every poll, but the error log folds repeats of one stack trace into a single record
            ErrorLogger.logError(e,
                ErrorContext.of(reporter.getClass(), "report").with("reporter", reporter.getName()));
            return new StatusReport(reporter.getName(), StatusReport.Status.ERROR, FAILED_REPORT_TEXT);
        }
    }
}

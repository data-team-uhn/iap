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

import io.uhndata.iap.status.api.StatusReportManager;
import io.uhndata.iap.status.spi.StatusReport;
import io.uhndata.iap.status.spi.StatusReporter;

/**
 * Default implementation of {@link StatusReportManager}, collecting the reports of all the registered
 * {@link StatusReporter} services. A misbehaving reporter doesn't break the whole report: its exception is turned
 * into an {@link StatusReport.Status#ERROR} report.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component
public class StatusReportManagerImpl implements StatusReportManager
{
    private static final Logger LOGGER = LoggerFactory.getLogger(StatusReportManagerImpl.class);

    /** A list of all available reporters. */
    @Reference(cardinality = ReferenceCardinality.MULTIPLE, fieldOption = FieldOption.REPLACE,
        policy = ReferencePolicy.DYNAMIC)
    private volatile List<StatusReporter> reporters;

    @Override
    public List<StatusReport> getReports(final boolean unprivileged, final StatusReport.Status level,
        final Set<String> tags)
    {
        final List<StatusReporter> current = this.reporters;
        if (current == null) {
            return List.of();
        }
        return current.stream()
            .filter(reporter -> tags == null || tags.isEmpty() || !Collections.disjoint(reporter.getTags(), tags))
            .map(reporter -> report(reporter, unprivileged))
            .filter(Objects::nonNull)
            .filter(report -> report.getStatus().compareTo(level) >= 0)
            .toList();
    }

    /**
     * Invokes one reporter, isolating its failures.
     *
     * @param reporter the reporter to invoke
     * @param unprivileged whether the report must not include sensitive information
     * @return the reporter's report, or an {@code ERROR} report if the reporter threw an exception
     */
    private StatusReport report(final StatusReporter reporter, final boolean unprivileged)
    {
        try {
            return reporter.report(unprivileged);
        } catch (final RuntimeException e) {
            LOGGER.warn("Status reporter {} failed: {}", reporter.getName(), e.getMessage(), e);
            return new StatusReport(reporter.getName(), StatusReport.Status.ERROR,
                "Failed to compute report: " + e.getMessage());
        }
    }
}

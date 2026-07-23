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

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.metrics.api.Metric;
import io.uhndata.iap.metrics.api.MetricsManager;
import io.uhndata.iap.status.spi.StatusReport;
import io.uhndata.iap.status.spi.StatusReporter;

/**
 * Reports the current value of every metric, grouped by category, as an {@code INFO} status report. Reading the
 * metrics doesn't change them, so polling the status never influences the reported values; the per-period numbers
 * come from each metric's scheduled or manual {@link Metric#rollOver roll-overs}. Metrics restricted to
 * administrators are left out of unprivileged reports.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class MetricsStatusReporter implements StatusReporter
{
    private static final String TITLE = "Metrics";

    @Reference
    private MetricsManager metricsManager;

    @Override
    public String getName()
    {
        return TITLE;
    }

    @Override
    public StatusReport report(final boolean unprivileged)
    {
        final Map<String, List<Metric>> categories = this.metricsManager.getMetrics().stream()
            .filter(metric -> !unprivileged || metric.getAccessLevel() == Metric.AccessLevel.PUBLIC)
            .collect(Collectors.groupingBy(
                metric -> Objects.requireNonNullElse(metric.getCategory(), ""),
                TreeMap::new, Collectors.toList()));
        if (categories.isEmpty()) {
            // No metrics to display, or none that may be displayed here, better say nothing than show an empty list
            return null;
        }
        final List<String> lines = new ArrayList<>();
        categories.forEach((category, metrics) -> {
            if (categories.size() > 1) {
                if (!lines.isEmpty()) {
                    lines.add("");
                }
                lines.add((category.isEmpty() ? "Uncategorized" : category) + ":");
            }
            metrics.forEach(metric -> lines.add(buildLine(metric)));
        });
        return new StatusReport(TITLE, StatusReport.Status.INFO, String.join("\n", lines));
    }

    @Override
    public Set<String> getTags()
    {
        return Set.of("metrics", "activity");
    }

    /**
     * Display one metric: its label and current value, and when the metric was ever rolled over, also the running
     * count of the current period and the count of the last closed period.
     *
     * @param metric the metric to display
     * @return a display line, e.g.
     *         {@code - Submitted proposals: 12 (+3 since 2026-07-20; previous period: +5)}
     */
    private String buildLine(final Metric metric)
    {
        final StringBuilder line = new StringBuilder("- ")
            .append(metric.getLabel()).append(": ").append(metric.getCurrentValue());
        final ZonedDateTime lastRollover = metric.getLastRollover();
        if (lastRollover != null) {
            line.append(" (").append(String.format("%+d", metric.getCurrentDelta()))
                .append(" since ").append(lastRollover.format(DateTimeFormatter.ISO_LOCAL_DATE))
                .append("; previous period: ").append(String.format("%+d", metric.getLastDelta()))
                .append(')');
        }
        return line.toString();
    }
}

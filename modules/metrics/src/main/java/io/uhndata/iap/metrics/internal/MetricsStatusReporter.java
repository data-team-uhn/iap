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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.metrics.api.Metric;
import io.uhndata.iap.metrics.api.MetricsManager;
import io.uhndata.iap.status.spi.StatusReport;
import io.uhndata.iap.status.spi.StatusReporter;

/**
 * Reports the metrics' counts, grouped by category, as one {@code INFO} status report. An unprivileged report leaves
 * out the metrics with the {@code admin} access level.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class MetricsStatusReporter implements StatusReporter
{
    private static final String REPORT_TITLE = "Metrics";

    @Reference
    private MetricsManager metricsManager;

    @Override
    public String getName()
    {
        return REPORT_TITLE;
    }

    @Override
    public StatusReport report(final boolean unprivileged)
    {
        // A map in insertion order keeps the display order of getMetrics(). Sorted keys would put the metrics with no
        // category first.
        final Map<String, List<Metric>> categories = this.metricsManager.getMetrics().stream()
            .filter(metric -> !unprivileged || metric.getAccessLevel() == Metric.AccessLevel.PUBLIC)
            .collect(Collectors.groupingBy(
                metric -> Objects.requireNonNullElse(metric.getCategory(), ""),
                LinkedHashMap::new, Collectors.toList()));
        if (categories.isEmpty()) {
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
        return new StatusReport(REPORT_TITLE, StatusReport.Status.INFO, String.join("\n", lines));
    }

    @Override
    public Set<String> getTags()
    {
        return Set.of("metrics", "activity");
    }

    /**
     * One metric's line in the report: its label and count. For a metric that was rolled over, the line also has the
     * amounts counted in the current period and in the last one.
     *
     * @param metric the metric
     * @return the line, such as {@code - Submitted proposals: 12 (+3 since 2026-07-20; previous period: +5)}
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

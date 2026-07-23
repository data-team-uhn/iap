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
package io.uhndata.iap.metrics.api;

import java.util.List;
import java.util.Optional;

/**
 * Service for defining and looking up {@link Metric metrics}. This only handles the lifecycle: reading and updating
 * a counter is done through the returned {@link Metric} handles.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface MetricsManager
{
    /**
     * Configures the metadata of a metric being {@link MetricsManager#createMetric defined}. All the metadata is
     * optional: an unlabeled metric displays its name, and unless restricted, a metric is publicly visible.
     *
     * @since 0.1.0
     */
    interface MetricBuilder
    {
        /**
         * Set the human-readable name of the metric.
         *
         * @param label a short display string
         * @return this builder
         */
        MetricBuilder withLabel(String label);

        /**
         * Set a longer explanation of what the metric counts.
         *
         * @param description a description
         * @return this builder
         */
        MetricBuilder withDescription(String description);

        /**
         * Set the category used to group related metrics in reports.
         *
         * @param category a category name
         * @return this builder
         */
        MetricBuilder withCategory(String category);

        /**
         * Restrict who is allowed to see the metric.
         *
         * @param accessLevel one of the {@link Metric.AccessLevel} values
         * @return this builder
         */
        MetricBuilder withAccessLevel(Metric.AccessLevel accessLevel);

        /**
         * Set a schedule for automatic periodic {@link Metric#rollOver roll-overs}. Without a schedule, the metric
         * is only rolled over when {@link Metric#rollOver} is explicitly invoked. The expression is not validated
         * here: an invalid expression is reported in the logs by the scheduler and simply never fires.
         *
         * @param rolloverSchedule a Quartz cron expression, e.g. {@code 0 0 0 * * ?} for "nightly at midnight"
         * @return this builder
         */
        MetricBuilder withRolloverSchedule(String rolloverSchedule);

        /**
         * Persist the metric definition. This is idempotent and can safely be invoked on every component
         * activation: if the metric already exists, its counter is left untouched and its metadata is updated to
         * match this builder, including removing metadata not set on the builder.
         *
         * @return the created or updated metric
         * @throws MetricsException if the definition cannot be persisted
         */
        Metric create();
    }

    /**
     * Start defining a new metric. Nothing is persisted until {@link MetricBuilder#create()} is invoked on the
     * returned builder.
     *
     * @param name the identifier of the metric, a non-empty string of letters, digits, dashes and underscores
     * @return a builder for the optional metadata
     * @throws IllegalArgumentException if the name is missing or contains other characters
     */
    MetricBuilder createMetric(String name);

    /**
     * Look up an existing metric.
     *
     * @param name the identifier of the metric
     * @return the metric, or an empty optional if no metric with this name was defined
     */
    Optional<Metric> getMetric(String name);

    /**
     * List all the defined metrics.
     *
     * @return all metrics, sorted by name, may be empty
     */
    List<Metric> getMetrics();
}

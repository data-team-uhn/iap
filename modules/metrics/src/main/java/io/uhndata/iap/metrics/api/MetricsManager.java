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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Defines metrics and finds them. A metric is read, counted and rolled over through the {@link Metric} returned.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface MetricsManager
{
    /**
     * The definition of a metric, saved by {@link #create()}. Every setting is optional. A metric without a label
     * shows its name, and a metric is public unless restricted.
     *
     * @since 0.1.0
     */
    interface MetricBuilder
    {
        /**
         * Set the name of the metric as people read it.
         *
         * @param label a short phrase, such as {@code Submitted proposals}; {@code null} to show the metric's name
         * @return this builder
         */
        @NotNull
        MetricBuilder withLabel(@Nullable String label);

        /**
         * Set what the metric counts.
         *
         * @param description one or more sentences; {@code null} for none
         * @return this builder
         */
        @NotNull
        MetricBuilder withDescription(@Nullable String description);

        /**
         * Set the category that groups the metric with related ones in reports.
         *
         * @param category a category name, such as {@code Submissions}; {@code null} for none
         * @return this builder
         */
        @NotNull
        MetricBuilder withCategory(@Nullable String category);

        /**
         * Set where the metric is shown among the metrics of its {@link #withCategory category}, lower values first.
         * Leave gaps, such as multiples of ten, so a metric can go between two others later without renumbering.
         *
         * @param defaultOrder the order, which may be negative; {@code 0} if never set
         * @return this builder
         */
        @NotNull
        MetricBuilder withDefaultOrder(long defaultOrder);

        /**
         * Set who is allowed to see the metric.
         *
         * @param accessLevel the access level; {@link Metric.AccessLevel#PUBLIC} if never set
         * @return this builder
         */
        @NotNull
        MetricBuilder withAccessLevel(@NotNull Metric.AccessLevel accessLevel);

        /**
         * Set when the metric is {@link Metric#rollOver() rolled over}. The expression is not checked here. An
         * invalid one is logged as an error and never fires.
         *
         * @param rolloverSchedule a Quartz cron expression, such as {@code 0 0 0 * * ?} for every midnight;
         *            {@code null} to roll over only when {@link Metric#rollOver()} is called
         * @return this builder
         */
        @NotNull
        MetricBuilder withRolloverSchedule(@Nullable String rolloverSchedule);

        /**
         * Save the definition. A metric that already exists takes this definition, and loses any setting not given
         * here. Its count and its periods stay as they were, so a component can define its metrics at every
         * activation.
         *
         * @return the metric, which reads the repository on every call
         * @throws MetricsException if the definition cannot be saved, or if a node that is not a metric has taken
         *             the name
         */
        @NotNull
        Metric create();
    }

    /**
     * Start the definition of a metric, new or existing. Nothing is saved until {@link MetricBuilder#create()}.
     *
     * @param name the metric's name: letters, digits, dashes and underscores, starting with a letter or a digit, such
     *            as {@code submittedProposals}
     * @return the definition, to set the optional settings on
     * @throws IllegalArgumentException if the name is {@code null} or not of that form
     */
    @NotNull
    MetricBuilder createMetric(@Nullable String name);

    /**
     * The metric with the given name.
     *
     * @param name the metric's name, such as {@code submittedProposals}
     * @return the metric, which reads the repository on every call; empty if no metric has this name
     */
    @NotNull
    Optional<Metric> getMetric(@Nullable String name);

    /**
     * Every metric, in the order to show them. They are grouped by {@link Metric#getCategory() category}, sorted by
     * the category's name with uppercase before lowercase. A metric with no category, or a blank one, comes last.
     * Within a category, metrics are sorted by {@link Metric#getDefaultOrder() order}, then by name.
     *
     * <p>The metrics report the values read for the listing, and reading them opens no session. They still count and
     * roll over in the repository. A metric that keeps reporting current values comes from {@link #getMetric}.</p>
     *
     * @return the metrics; empty if there are none
     */
    @NotNull
    List<Metric> getMetrics();
}

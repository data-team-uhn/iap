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

import java.time.ZonedDateTime;
import java.util.Locale;

import jakarta.json.JsonObject;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A named count of something that happens, such as research proposals submitted. It is kept in the repository, with
 * a label and a description to show it by.
 *
 * <p>The count is never reset. A {@link #rollOver() roll-over} closes a reporting period instead: it keeps the amount
 * counted in that period, and the next period counts on from the current value. A report can then show both the
 * current period so far and the last full one.</p>
 *
 * <p>A metric from {@link MetricsManager#getMetric} or {@link MetricsManager.MetricBuilder#create} reads the repository
 * on every call. A metric from {@link MetricsManager#getMetrics} reports the values read for the listing. Both write to
 * the repository.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface Metric
{
    /**
     * Who is allowed to see a metric.
     *
     * @since 0.1.0
     */
    enum AccessLevel
    {
        /** Visible to anyone. */
        PUBLIC,
        /** Visible only to the {@code admin} user. */
        ADMIN;

        /**
         * This access level as the {@code accessLevel} property stores it.
         *
         * @return {@code public} or {@code admin}
         */
        @NotNull
        public String asPropertyValue()
        {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * The access level an {@code accessLevel} property stores.
         *
         * @param value the property's value; {@code null} if it is not set
         * @return {@link #ADMIN} if the value is {@code admin} in any case, {@link #PUBLIC} otherwise
         */
        @NotNull
        public static AccessLevel fromPropertyValue(@Nullable final String value)
        {
            return ADMIN.asPropertyValue().equalsIgnoreCase(value) ? ADMIN : PUBLIC;
        }
    }

    /**
     * The name of this metric, unique among all metrics. It is also the name of the metric's node.
     *
     * @return letters, digits, dashes and underscores, such as {@code submittedProposals}
     */
    @NotNull
    String getName();

    /**
     * The name of this metric as people read it.
     *
     * @return a short phrase, such as {@code Submitted proposals}; the {@link #getName() name} if no label was set
     */
    @NotNull
    String getLabel();

    /**
     * What this metric counts.
     *
     * @return one or more sentences; {@code null} if no description was set
     */
    @Nullable
    String getDescription();

    /**
     * The category that groups this metric with related ones in reports.
     *
     * @return a category name, such as {@code Submissions}; {@code null} if this metric has no category
     */
    @Nullable
    String getCategory();

    /**
     * Where this metric is shown among the metrics of its {@link #getCategory() category}, lower values first.
     *
     * @return the order, which may be negative; {@code 0} if none was set
     */
    long getDefaultOrder();

    /**
     * Who is allowed to see this metric.
     *
     * @return the access level; {@link AccessLevel#PUBLIC} if none was set
     */
    @NotNull
    AccessLevel getAccessLevel();

    /**
     * The count since the metric was created.
     *
     * @return the count; {@code 0} for a metric that was never incremented
     */
    long getCurrentValue();

    /**
     * The count at the last {@link #rollOver() roll-over}, where the current period started.
     *
     * @return the count; {@code 0} if the metric was never rolled over
     */
    long getPreviousValue();

    /**
     * The amount counted so far in the current period: the {@link #getCurrentValue() current value} less the
     * {@link #getPreviousValue() previous value}.
     *
     * @return the amount; negative if the period's negative increments outweigh its positive ones
     */
    long getCurrentDelta();

    /**
     * The amount counted in the last closed period, set by the last {@link #rollOver() roll-over}.
     *
     * @return the amount; {@code 0} if the metric was never rolled over
     */
    long getLastDelta();

    /**
     * When this metric was last incremented.
     *
     * @return the date; {@code null} if the metric was never incremented
     */
    @Nullable
    ZonedDateTime getLastUpdated();

    /**
     * When this metric was last {@link #rollOver() rolled over}.
     *
     * @return the date; {@code null} if the metric was never rolled over
     */
    @Nullable
    ZonedDateTime getLastRollover();

    /**
     * When this metric is {@link #rollOver() rolled over} on a schedule.
     *
     * @return a Quartz cron expression, such as {@code 0 0 0 * * ?} for every midnight; {@code null} if the metric is
     *         rolled over only when {@link #rollOver()} is called
     */
    @Nullable
    String getRolloverSchedule();

    /**
     * Add one to the count, as {@code increment(1)} does.
     */
    void increment();

    /**
     * Add an amount to the count. Oak does the addition as it saves the change, so increments made at the same time,
     * on any node of a cluster, do not overwrite each other.
     *
     * <p>A failure is logged and never thrown, and the amount goes uncounted. Counting cannot break the operation it
     * counts.</p>
     *
     * @param amount the amount to add, negative to correct an over-count; {@code 0} does nothing
     */
    void increment(long amount);

    /**
     * Close the current period and start a new one. The amount counted in the period becomes the
     * {@link #getLastDelta() last delta}. The current value becomes the {@link #getPreviousValue() previous value},
     * and the time becomes the {@link #getLastRollover() last roll-over}. The count itself does not change.
     *
     * <p>A roll-over works from the values in the repository, never from those a listed metric reports.</p>
     *
     * @throws MetricsException if the roll-over cannot be saved
     */
    void rollOver();

    /**
     * This metric as JSON.
     *
     * @return an object with the keys {@code name}, {@code label}, {@code description}, {@code category},
     *         {@code defaultOrder}, {@code accessLevel}, {@code currentValue}, {@code previousValue},
     *         {@code currentDelta} and {@code lastDelta}, plus {@code lastUpdated}, {@code lastRollover} and
     *         {@code rolloverSchedule} when they are set. A missing description or category is an empty string.
     */
    @NotNull
    JsonObject toJson();
}

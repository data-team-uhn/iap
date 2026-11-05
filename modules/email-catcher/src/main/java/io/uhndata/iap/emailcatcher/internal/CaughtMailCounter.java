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
package io.uhndata.iap.emailcatcher.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.metrics.api.Metric;
import io.uhndata.iap.metrics.api.MetricsException;
import io.uhndata.iap.metrics.api.MetricsManager;

/**
 * How many messages the catcher has filed, as the {@value #METRIC_NAME} metric. It rolls over nightly, so the
 * previous day's count stays readable beside the running total.
 *
 * <p>
 * If the metric cannot be defined, mail is still caught. The failure is reported once, and the counter counts
 * nothing.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class CaughtMailCounter
{
    /** The name of the metric. */
    static final String METRIC_NAME = "caughtMail";

    /** Nightly at midnight, as a Quartz cron expression. */
    static final String NIGHTLY_SCHEDULE = "0 0 0 * * ?";

    private static final Logger LOGGER = LoggerFactory.getLogger(CaughtMailCounter.class);

    /** The metric; {@code null} if it could not be defined. */
    private final Metric metric;

    private CaughtMailCounter(@Nullable final Metric metric)
    {
        this.metric = metric;
    }

    /**
     * Defines the metric. A metric that already exists keeps its count, so switching the catcher off and on again
     * loses nothing.
     *
     * @param metrics the metrics service to define the metric in
     * @param homePath the path caught messages are filed under, which the metric's description names
     * @return the counter; one that counts nothing if the metric could not be defined
     */
    @NotNull
    static CaughtMailCounter define(@NotNull final MetricsManager metrics, @NotNull final String homePath)
    {
        try {
            return new CaughtMailCounter(metrics.createMetric(METRIC_NAME)
                .withLabel("Caught emails")
                .withDescription("Messages the email catcher filed under " + homePath + " instead of sending them.")
                .withCategory("Email")
                .withDefaultOrder(10)
                .withAccessLevel(Metric.AccessLevel.ADMIN)
                .withRolloverSchedule(NIGHTLY_SCHEDULE)
                .create());
        } catch (final MetricsException e) {
            LOGGER.error("The caught mail counter could not be defined, so mail will be caught uncounted: {}",
                e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(CaughtMailCounter.class, "define").with("metric", METRIC_NAME));
            return new CaughtMailCounter(null);
        }
    }

    /**
     * Adds one caught message to the count. The metrics service logs a failure to save the count, rather than
     * throwing it.
     */
    void count()
    {
        if (this.metric != null) {
            this.metric.increment();
        }
    }
}

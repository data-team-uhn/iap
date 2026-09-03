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
 * How much mail the catcher has caught, as a metric that rolls over nightly.
 *
 * <p>
 * The count answers a question the listing cannot: whether anything was sent at all during a run. Rolling over
 * nightly is what makes that readable — a demo or a test environment is exercised in bursts, so a running total
 * that only ever grows says nothing about whether today's run sent what it should have, while the period's own
 * figure sits beside it.
 * </p>
 *
 * <p>
 * <strong>A counter that cannot be defined is not a reason to stop catching mail.</strong> Catching is what the
 * catcher is switched on for; counting is a convenience on top. So a metrics store that refuses the definition
 * leaves a counter that quietly counts nothing, reported once when it is defined rather than on every message.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class CaughtMailCounter
{
    /** The metric counting the messages filed instead of sent. */
    static final String METRIC = "caughtMail";

    /** Nightly at midnight, as a Quartz cron expression. */
    static final String NIGHTLY = "0 0 0 * * ?";

    private static final Logger LOGGER = LoggerFactory.getLogger(CaughtMailCounter.class);

    /** The counter, or {@code null} when it could not be defined. */
    private final Metric metric;

    private CaughtMailCounter(@Nullable final Metric metric)
    {
        this.metric = metric;
    }

    /**
     * Defines the counter. This is idempotent: an existing metric keeps its count and only its metadata is brought
     * up to date, so switching the catcher off and on again does not lose what it had counted.
     *
     * @param metrics the metrics service to define the counter in
     * @param homePath where caught messages are filed, named in the description
     * @return a counter, which counts nothing if the metric could not be defined
     */
    @NotNull
    static CaughtMailCounter define(@NotNull final MetricsManager metrics, @NotNull final String homePath)
    {
        try {
            return new CaughtMailCounter(metrics.createMetric(METRIC)
                .withLabel("Caught emails")
                .withDescription("Messages the email catcher filed under " + homePath + " instead of sending them.")
                .withCategory("Email")
                .withDefaultOrder(10)
                // The catcher is a development facility, and how much of it has been exercised is not a fact
                // about the service that the people using the platform have any business reading
                .withAccessLevel(Metric.AccessLevel.ADMIN)
                .withRolloverSchedule(NIGHTLY)
                .create());
        } catch (final MetricsException e) {
            LOGGER.error("The caught mail counter could not be defined, so mail will be caught uncounted: {}",
                e.getMessage(), e);
            // Recorded as well as logged: nothing else reports this, and a counter that silently stopped counting
            // is the kind of fault only noticed once somebody needs the number
            ErrorLogger.logError(e, ErrorContext.of(CaughtMailCounter.class, "define").with("metric", METRIC));
            return new CaughtMailCounter(null);
        }
    }

    /**
     * Records one more caught message. Increments swallow their own failures, so counting can never turn a caught
     * message into a lost one.
     */
    void count()
    {
        if (this.metric != null) {
            this.metric.increment();
        }
    }
}

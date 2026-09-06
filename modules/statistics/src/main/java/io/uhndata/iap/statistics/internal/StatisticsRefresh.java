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
package io.uhndata.iap.statistics.internal;

import org.apache.sling.commons.scheduler.ScheduleOptions;
import org.apache.sling.commons.scheduler.Scheduler;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;

/**
 * Works the metrics out again on a schedule, so that nobody ever waits for them.
 *
 * <p>
 * Nightly by default. The questions these answer are about months — "what share was authorized within
 * 45 days", "the median time from submission to approval" — and a day's work does not move any of them
 * enough to be a different answer, while a full read of the recorded history is not something to put in
 * front of somebody opening the front page.
 * </p>
 *
 * <p>
 * In a cluster the job runs on the leader alone, so one instance reads the history and every instance
 * shows the same figures. An instance that has never worked them out gets a few attempts in its first
 * minutes, so that a fresh installation is not blank until the small hours; a restart, which has figures
 * already, waits for its next scheduled run.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
@Designate(ocd = StatisticsRefreshConfiguration.class)
public class StatisticsRefresh
{
    /** Nightly at 2:30, when an instance is least likely to be busy. */
    static final String DEFAULT_SCHEDULE = "0 30 2 * * ? *";

    /** The recurring job. */
    static final String JOB_NAME = "iap-statistics-refresh";

    /** The job that fills in an instance which has never worked the metrics out. */
    static final String INITIAL_JOB_NAME = "iap-statistics-refresh-initial";

    /**
     * How many times that job tries, a {@link #INITIAL_PERIOD_SECONDS minute} apart. More than once
     * because what it reads is this module's own content, installed on a different thread from the one
     * that starts this component: the first attempt can find nothing defined yet, and a single attempt
     * would then leave a fresh installation blank until the small hours. A repeat that finds the work
     * already done costs a read of a history that a brand new instance does not have.
     */
    static final int INITIAL_TRIES = 3;

    /** How long apart those attempts are. */
    static final long INITIAL_PERIOD_SECONDS = 60;

    private static final String SCHEDULE_DETAIL = "schedule";

    private static final Logger LOGGER = LoggerFactory.getLogger(StatisticsRefresh.class);

    @Reference
    private Scheduler scheduler;

    @Reference
    private MetricStore store;

    @Activate
    protected void activate(final StatisticsRefreshConfiguration config)
    {
        if (!schedule(config.schedule()) && !DEFAULT_SCHEDULE.equals(config.schedule())) {
            // A schedule nobody can parse must not mean "never refresh": the figures would then quietly
            // stay at whatever they were on the day the configuration was edited
            LOGGER.warn("Falling back to the default statistics refresh schedule");
            schedule(DEFAULT_SCHEDULE);
        }
        if (this.store.isEmpty()) {
            scheduleInitial();
        }
    }

    @Deactivate
    protected void deactivate()
    {
        this.scheduler.unschedule(JOB_NAME);
        this.scheduler.unschedule(INITIAL_JOB_NAME);
    }

    /**
     * Schedules the recurring refresh.
     *
     * @param expression the Quartz cron expression to follow
     * @return whether it was accepted
     */
    private boolean schedule(final String expression)
    {
        try {
            final ScheduleOptions options = this.scheduler.EXPR(expression);
            options.name(JOB_NAME);
            options.canRunConcurrently(false);
            options.onLeaderOnly(true);
            if (this.scheduler.schedule((Runnable) this::refresh, options)) {
                LOGGER.debug("The metrics will be worked out again on [{}]", expression);
                return true;
            }
            LOGGER.error("The statistics refresh was not scheduled, is [{}] a valid cron expression?",
                expression);
            ErrorLogger.logProblem("statistics refresh schedule was refused",
                ErrorContext.of(StatisticsRefresh.class, SCHEDULE_DETAIL).with(SCHEDULE_DETAIL, expression));
        } catch (final RuntimeException e) {
            LOGGER.error("The statistics refresh was not scheduled: {}", e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(StatisticsRefresh.class, SCHEDULE_DETAIL)
                .with(SCHEDULE_DETAIL, expression));
        }
        return false;
    }

    /**
     * Schedules the refresh that fills in an instance which has never had one.
     */
    private void scheduleInitial()
    {
        try {
            final ScheduleOptions options = this.scheduler.NOW(INITIAL_TRIES, INITIAL_PERIOD_SECONDS);
            options.name(INITIAL_JOB_NAME);
            options.canRunConcurrently(false);
            options.onLeaderOnly(true);
            this.scheduler.schedule((Runnable) this::refresh, options);
            LOGGER.info("The metrics have not been worked out yet; a first pass will run shortly");
        } catch (final RuntimeException e) {
            // Not fatal: the recurring job will do it, and an administrator can ask for it sooner
            LOGGER.warn("The first statistics refresh was not scheduled: {}", e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(StatisticsRefresh.class, "initial"));
        }
    }

    /**
     * The job itself. Failures are reported rather than thrown, so that one bad night leaves the job
     * scheduled and the previous figures in place.
     */
    void refresh()
    {
        try {
            this.store.refresh();
        } catch (final RuntimeException e) {
            LOGGER.error("The scheduled statistics refresh failed: {}", e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(StatisticsRefresh.class, "refresh"));
        }
    }
}

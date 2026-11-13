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
package io.uhndata.iap.tags.internal;

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
import io.uhndata.iap.tags.api.TagRepairException;
import io.uhndata.iap.tags.api.TagRepairService;
import io.uhndata.iap.tags.api.TagRepairService.RepairReport;

/**
 * Repairs the content marked as stale, on a schedule. The content affected by an edited tag definition is repaired
 * only on request, through {@link TagRepairService#repair(String)}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
@Designate(ocd = TagRepairConfiguration.class)
public class ScheduledTagRepair
{
    /** The name of the scheduled job. */
    static final String JOB_NAME = "iap-tag-repair-sweep";

    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduledTagRepair.class);

    @Reference
    private Scheduler scheduler;

    @Reference
    private TagRepairService repairService;

    @Activate
    void activate(final TagRepairConfiguration config)
    {
        if (!config.enabled()) {
            LOGGER.info("Automatic tag repair is disabled");
            return;
        }
        final ScheduleOptions options = this.scheduler.EXPR(config.schedule());
        options.name(JOB_NAME);
        // Two sweeps at once would find and mark the same nodes
        options.canRunConcurrently(false);
        scheduleSweep(options, config.schedule());
    }

    /**
     * Hands the sweep to the scheduler. A sweep that cannot be scheduled leaves the component started, and repair on
     * request still works; it is recorded, since nothing else shows that the sweep never runs.
     *
     * @param options when the sweep runs
     * @param schedule the configured cron expression, for the log and the record
     */
    private void scheduleSweep(final ScheduleOptions options, final String schedule)
    {
        try {
            if (this.scheduler.schedule((Runnable) this::sweep, options)) {
                LOGGER.info("Scheduled the tag repair sweep: {}", schedule);
            } else {
                // The scheduler answers an invalid cron expression with false, not with an exception
                LOGGER.warn("The scheduler refused the tag repair sweep, so it will never run;"
                    + " its schedule {} is likely not a valid cron expression", schedule);
                ErrorLogger.logProblem("the scheduler refused the tag repair sweep",
                    ErrorContext.of(ScheduledTagRepair.class, "scheduleSweep").with("job", JOB_NAME)
                        .with("schedule", schedule));
            }
        } catch (final IllegalStateException e) {
            // The scheduler throws only when it is going away while the sweep is being scheduled
            LOGGER.error("Could not schedule the tag repair sweep: {}", e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(ScheduledTagRepair.class, "scheduleSweep").with("job", JOB_NAME)
                .with("schedule", schedule));
        }
    }

    @Deactivate
    void deactivate()
    {
        this.scheduler.unschedule(JOB_NAME);
    }

    /**
     * One sweep. It logs a line only when it marked a node, failed to mark one, or could not run.
     */
    void sweep()
    {
        try {
            logIfAnythingHappened(this.repairService.repairFailed());
        } catch (final TagRepairException e) {
            // Not recorded again, the service recorded the cause; the next sweep tries again
            LOGGER.info("Tag repair sweep skipped: {}", e.getMessage());
        }
    }

    private static void logIfAnythingHappened(final RepairReport report)
    {
        if (report.marked() > 0 || !report.isComplete()) {
            LOGGER.info("Tag repair sweep: recomputed {} node(s), {} could not be marked", report.marked(),
                report.failed());
        }
    }
}

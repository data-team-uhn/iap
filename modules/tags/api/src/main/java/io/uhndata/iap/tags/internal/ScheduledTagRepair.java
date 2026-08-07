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

import io.uhndata.iap.tags.api.TagRepairService;
import io.uhndata.iap.tags.api.TagRepairService.RepairReport;

/**
 * Sweeps up the content whose tags could not be computed, on a schedule.
 *
 * <p>
 * Only the failures: they are few, they are indexed, and leaving them alone means a node keeps tags that are quietly
 * out of date. Repairing the content affected by an edited tag <em>definition</em> is deliberately not automatic —
 * it can touch a large part of the repository, and doing that as a side effect of someone saving a definition is a
 * surprise nobody asked for.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
@Designate(ocd = TagRepairConfiguration.class)
public class ScheduledTagRepair
{
    /** Names the job among all the scheduled ones. */
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
        try {
            final ScheduleOptions options = this.scheduler.EXPR(config.schedule());
            options.name(JOB_NAME);
            // One sweep at a time: a second one would find the same nodes and fight the first for them
            options.canRunConcurrently(false);
            this.scheduler.schedule((Runnable) this::sweep, options);
            LOGGER.info("Scheduled the tag repair sweep: {}", config.schedule());
        } catch (final RuntimeException e) {
            // An unusable schedule must not stop the module from starting; repair stays available on demand
            LOGGER.error("Could not schedule the tag repair sweep: {}", e.getMessage(), e);
        }
    }

    @Deactivate
    void deactivate()
    {
        this.scheduler.unschedule(JOB_NAME);
    }

    /**
     * One sweep. Reports only when it did something, so that a healthy repository stays quiet in the logs and the
     * lines that do appear are worth reading.
     */
    void sweep()
    {
        final RepairReport report = this.repairService.repairFailed();
        if (report.marked() > 0 || !report.isComplete()) {
            LOGGER.info("Tag repair sweep: recomputed {} node(s), {} could not be marked", report.marked(),
                report.failed());
        }
    }
}

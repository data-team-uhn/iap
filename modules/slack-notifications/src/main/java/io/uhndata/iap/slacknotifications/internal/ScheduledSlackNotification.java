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
package io.uhndata.iap.slacknotifications.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.commons.scheduler.ScheduleOptions;
import org.apache.sling.commons.scheduler.Scheduler;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.FieldOption;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.httprequests.api.HttpRequests;
import io.uhndata.iap.slacknotifications.spi.SlackNotificationProducer;

/**
 * Schedules one {@link SlackNotificationsTask} per configuration.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
@Designate(ocd = SlackNotificationConfiguration.class, factory = true)
public class ScheduledSlackNotification
{
    /** The prefix identifying this module's jobs among all the scheduled ones. */
    static final String JOB_PREFIX = "ScheduledSlackNotification-";

    /** Used when no schedule is configured, and none can be read from the environment: nightly. */
    static final String DEFAULT_SCHEDULE = "0 0 0 * * ? *";

    /** Marks a configuration value that names an environment variable instead of holding the value itself. */
    private static final String FROM_ENVIRONMENT = "%ENV%";

    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduledSlackNotification.class);

    /**
     * All the registered notification producers. Updated in place as producers come and go, so the scheduled task
     * sees the current set rather than the one that existed when it was scheduled.
     */
    @Reference(cardinality = ReferenceCardinality.MULTIPLE, fieldOption = FieldOption.UPDATE,
        policy = ReferencePolicy.DYNAMIC)
    private volatile List<SlackNotificationProducer> producers = new ArrayList<>();

    @Reference
    private Scheduler scheduler;

    @Reference
    private HttpRequests httpRequests;

    @Activate
    protected void activate(final SlackNotificationConfiguration config)
    {
        final String endpoint = resolve(config.endpoint());
        if (StringUtils.isBlank(endpoint)) {
            // Scheduling a job that can only ever fail would report a failure every night forever
            LOGGER.warn("The {} notification has no endpoint configured, it will not be scheduled", config.name());
            return;
        }
        final SlackNotificationsTask task = new SlackNotificationsTask(this.httpRequests, this.producers, endpoint,
            config.title(), asList(config.include()), asParameters(config.notificationParameters()),
            config.skipEmpty());
        try {
            final ScheduleOptions options = this.scheduler.EXPR(schedule(config.schedule()));
            options.name(JOB_PREFIX + config.name());
            options.canRunConcurrently(true);
            this.scheduler.schedule(task, options);
            LOGGER.info("Scheduled the {} notification", config.name());
        } catch (final RuntimeException e) {
            // An unusable schedule expression must not stop the whole module from starting
            LOGGER.error("Could not schedule the {} notification: {}", config.name(), e.getMessage(), e);
        }
    }

    @Deactivate
    protected void deactivate(final SlackNotificationConfiguration config)
    {
        LOGGER.debug("Removing the {} notification", config.name());
        this.scheduler.unschedule(JOB_PREFIX + config.name());
    }

    /**
     * The schedule to use, falling back to a nightly one when nothing usable was configured.
     *
     * @param configured the configured value, possibly naming an environment variable
     * @return a Quartz-readable schedule expression
     */
    static String schedule(final String configured)
    {
        return StringUtils.defaultIfBlank(resolve(configured), DEFAULT_SCHEDULE);
    }

    /**
     * Reads a configuration value that may name an environment variable rather than holding the value itself, which
     * is how a secret such as a webhook address is kept out of the configuration files.
     *
     * @param configured the configured value
     * @return the value itself, or what the named environment variable holds, {@code null} if there is no such
     *         variable
     */
    static String resolve(final String configured)
    {
        if (configured != null && configured.startsWith(FROM_ENVIRONMENT)) {
            return System.getenv(configured.substring(FROM_ENVIRONMENT.length()));
        }
        return configured;
    }

    /**
     * Parses the {@code key=value} strings of the configuration into a map, ignoring anything that is not in that
     * shape rather than refusing the whole configuration.
     *
     * @param configured the configured parameters, may be {@code null}
     * @return the parsed parameters, an empty map if there are none
     */
    static Map<String, String> asParameters(final String[] configured)
    {
        final Map<String, String> result = new HashMap<>();
        for (final String parameter : asList(configured)) {
            final String[] keyAndValue = parameter.split("=", 2);
            if (keyAndValue.length == 2) {
                result.put(keyAndValue[0].trim(), keyAndValue[1]);
            } else {
                LOGGER.warn("Ignoring the notification parameter {}, it is not in the key=value format", parameter);
            }
        }
        return result;
    }

    private static List<String> asList(final String[] configured)
    {
        return configured == null ? List.of() : List.of(configured);
    }
}

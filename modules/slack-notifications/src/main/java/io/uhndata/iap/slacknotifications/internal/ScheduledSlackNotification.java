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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

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

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.httprequests.api.HttpRequests;
import io.uhndata.iap.slacknotifications.spi.SlackNotificationProducer;

/**
 * Posts each configured notification to its chat webhook on its schedule. Each factory configuration is one
 * scheduled job.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
@Designate(ocd = SlackNotificationConfiguration.class, factory = true)
public class ScheduledSlackNotification
{
    /** The prefix of a notification's job name, followed by the notification's name. */
    static final String JOB_PREFIX = "ScheduledSlackNotification-";

    /** The schedule of a notification whose schedule resolves to nothing: every night at midnight. */
    static final String DEFAULT_SCHEDULE = "0 0 0 * * ? *";

    /** The prefix of a configuration value that names an environment variable to read the value from. */
    private static final String FROM_ENVIRONMENT = "%ENV%";

    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduledSlackNotification.class);

    /**
     * The registered producers. Declarative Services updates this list in place as producers come and go, and every
     * scheduled task reads it. A producer can come or go while a task iterates over the list, so it must be a
     * concurrent one.
     */
    @Reference(cardinality = ReferenceCardinality.MULTIPLE, fieldOption = FieldOption.UPDATE,
        policy = ReferencePolicy.DYNAMIC)
    private volatile List<SlackNotificationProducer> producers = new CopyOnWriteArrayList<>();

    @Reference
    private Scheduler scheduler;

    @Reference
    private HttpRequests httpRequests;

    @Activate
    protected void activate(final SlackNotificationConfiguration config)
    {
        final String endpoint = resolve(config.endpoint());
        if (StringUtils.isBlank(endpoint)) {
            LOGGER.warn("The {} notification has no endpoint configured, it will not be scheduled", config.name());
            ErrorLogger.logProblem("notification has no endpoint configured",
                ErrorContext.of(ScheduledSlackNotification.class, "activate").with("notification", config.name()));
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
            LOGGER.error("Could not schedule the {} notification: {}", config.name(), e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(ScheduledSlackNotification.class, "scheduleNotification")
                .with("notification", config.name()).with("schedule", config.schedule()));
        }
    }

    @Deactivate
    protected void deactivate(final SlackNotificationConfiguration config)
    {
        LOGGER.debug("Removing the {} notification", config.name());
        this.scheduler.unschedule(JOB_PREFIX + config.name());
    }

    /**
     * The schedule of a notification.
     *
     * @param configured the configured schedule, or {@code %ENV%} followed by the name of an environment variable
     *            holding one
     * @return the schedule, or {@link #DEFAULT_SCHEDULE} if it resolves to {@code null} or a blank string
     */
    static String schedule(final String configured)
    {
        return StringUtils.defaultIfBlank(resolve(configured), DEFAULT_SCHEDULE);
    }

    /**
     * Reads a configuration value that may come from an environment variable.
     *
     * @param configured the value itself, or {@code %ENV%} followed by the name of an environment variable, such as
     *            {@code %ENV%SLACK_NOTIFICATIONS_ENDPOINT}
     * @return the value itself, or what the named environment variable holds, or {@code null} if the value is
     *         {@code null} or the variable is not set
     */
    static String resolve(final String configured)
    {
        if (configured != null && configured.startsWith(FROM_ENVIRONMENT)) {
            return System.getenv(configured.substring(FROM_ENVIRONMENT.length()));
        }
        return configured;
    }

    /**
     * Parses the configured extra parameters. A string with no {@code =} is logged, recorded and skipped.
     *
     * @param configured {@code key=value} strings, or {@code null} for none. The key is trimmed, the value is not
     * @return the values by key, empty if there are none
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
                ErrorLogger.logProblem("notification parameter is not in the key=value format",
                    ErrorContext.of(ScheduledSlackNotification.class, "readParameters"));
            }
        }
        return result;
    }

    private static List<String> asList(final String[] configured)
    {
        return configured == null ? List.of() : List.of(configured);
    }
}

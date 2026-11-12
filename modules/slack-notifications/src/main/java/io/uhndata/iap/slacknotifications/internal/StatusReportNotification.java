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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.slacknotifications.spi.SlackNotificationProducer;
import io.uhndata.iap.status.api.StatusReportManager;
import io.uhndata.iap.status.spi.StatusReport;

/**
 * Adds the instance's status reports to a chat message, one attachment per report, colored by its status. It reads
 * these extra parameters:
 * <ul>
 * <li>{@code statusReport.targetStatusLevel}: the lowest {@link StatusReport.Status} to include, such as
 * {@code WARNING} or {@code warning}; {@code INFO} if not set</li>
 * <li>{@code statusReport.includeTags}: the tags of the reporters to include, comma-separated, such as
 * {@code problems}; every reporter if not set</li>
 * <li>{@code statusReport.unprivileged}: {@code true} to ask the reporters to leave sensitive information out;
 * {@code false} if not set</li>
 * </ul>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class StatusReportNotification implements SlackNotificationProducer
{
    /** The extra parameter naming the lowest status level to include. */
    static final String TARGET_LEVEL = "statusReport.targetStatusLevel";

    /** The extra parameter naming the status tags to include. */
    static final String INCLUDE_TAGS = "statusReport.includeTags";

    /** The extra parameter asking the reporters to leave sensitive information out. */
    static final String UNPRIVILEGED_PARAMETER = "statusReport.unprivileged";

    private static final Logger LOGGER = LoggerFactory.getLogger(StatusReportNotification.class);

    @Reference
    private StatusReportManager statusReportManager;

    @Override
    public String getName()
    {
        return "status";
    }

    @Override
    public List<JsonObject> prepareMessages(final Map<String, String> extraParameters)
    {
        final boolean unprivileged = Boolean.parseBoolean(extraParameters.get(UNPRIVILEGED_PARAMETER));
        final List<StatusReport> reports =
            this.statusReportManager.getReports(unprivileged, targetLevel(extraParameters), tags(extraParameters));
        return reports.stream().map(this::asAttachment).toList();
    }

    /**
     * Renders one status report as an attachment.
     *
     * @param report the report to render
     * @return an attachment with the report's name as its title, its text, and the color of its status
     */
    private JsonObject asAttachment(final StatusReport report)
    {
        return Json.createObjectBuilder()
            .add(TITLE_KEY, report.getName())
            .add(TEXT_KEY, report.getText() == null ? "" : report.getText())
            .add(COLOR_KEY, colorOf(report.getStatus()))
            .build();
    }

    private String colorOf(final StatusReport.Status status)
    {
        return switch (status) {
            case SUCCESS -> SlackNotificationProducer.SUCCESS_COLOR;
            case WARNING -> SlackNotificationProducer.WARNING_COLOR;
            case ERROR -> SlackNotificationProducer.ERROR_COLOR;
            default -> SlackNotificationProducer.INFO_COLOR;
        };
    }

    /**
     * The lowest status level to include. A level that does not exist is logged, and {@code INFO} is used instead.
     *
     * @param extraParameters the notification's extra parameters, by name
     * @return the configured level, or {@code INFO} if none is configured or it does not exist
     */
    private StatusReport.Status targetLevel(final Map<String, String> extraParameters)
    {
        final String configured = extraParameters.get(TARGET_LEVEL);
        if (configured == null) {
            return StatusReport.Status.INFO;
        }
        try {
            return StatusReport.Status.valueOf(configured.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            LOGGER.warn("{} is not a known status level, reporting from INFO up instead", configured);
            // A mistyped level quietly widens every report, and only whoever wrote the configuration can fix it
            ErrorLogger.logProblem("not a known status level",
                ErrorContext.of(StatusReportNotification.class, "readTargetLevel").with("level", configured));
            return StatusReport.Status.INFO;
        }
    }

    private Set<String> tags(final Map<String, String> extraParameters)
    {
        final String configured = extraParameters.get(INCLUDE_TAGS);
        if (configured == null) {
            return Set.of();
        }
        return Arrays.stream(configured.split(","))
            .map(String::trim)
            .filter(tag -> !tag.isEmpty())
            .collect(Collectors.toSet());
    }
}

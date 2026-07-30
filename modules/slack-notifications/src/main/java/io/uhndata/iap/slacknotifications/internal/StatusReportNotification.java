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

import io.uhndata.iap.slacknotifications.spi.SlackNotificationProducer;
import io.uhndata.iap.status.api.StatusReportManager;
import io.uhndata.iap.status.spi.StatusReport;

/**
 * Sends the system status report to a chat webhook, one attachment per status message. See the status module for
 * what those reports contain. The following extra parameters are understood:
 * <ul>
 * <li>{@code statusReport.targetStatusLevel}, the lowest {@link StatusReport.Status} to include, one of the known
 * levels; defaults to {@code INFO}</li>
 * <li>{@code statusReport.includeTags}, a comma-separated list of the status tags to include; defaults to all
 * tags</li>
 * <li>{@code statusReport.unprivileged}, whether the report should be generated for an unprivileged audience;
 * defaults to {@code false}</li>
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

    /** The extra parameter asking for a report fit for an unprivileged audience. */
    static final String UNPRIVILEGED = "statusReport.unprivileged";

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
        final boolean unprivileged = Boolean.parseBoolean(extraParameters.get(UNPRIVILEGED));
        final List<StatusReport> reports =
            this.statusReportManager.getReports(unprivileged, targetLevel(extraParameters), tags(extraParameters));
        return reports.stream().map(this::asAttachment).toList();
    }

    /**
     * Renders one status report as a webhook attachment.
     *
     * @param report the report to render
     * @return a JSON object respecting the attachment API
     */
    private JsonObject asAttachment(final StatusReport report)
    {
        return Json.createObjectBuilder()
            .add(TITLE, report.getName())
            // A report with nothing more to say than its status has no body at all
            .add(TEXT, report.getText() == null ? "" : report.getText())
            .add(COLOR, colorOf(report.getStatus()))
            .build();
    }

    private String colorOf(final StatusReport.Status status)
    {
        return switch (status) {
            case SUCCESS -> SlackNotificationProducer.SUCCESS;
            case WARNING -> SlackNotificationProducer.WARNING;
            case ERROR -> SlackNotificationProducer.ERROR;
            default -> SlackNotificationProducer.INFO;
        };
    }

    /**
     * The lowest status level to include. A configuration naming a level that does not exist falls back to the
     * default rather than costing the whole notification.
     *
     * @param extraParameters the configured extra parameters
     * @return a status level, {@code INFO} unless another valid one was configured
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

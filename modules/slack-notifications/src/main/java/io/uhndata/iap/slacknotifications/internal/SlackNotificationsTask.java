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

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.httprequests.api.HttpRequests;
import io.uhndata.iap.httprequests.api.HttpResponse;
import io.uhndata.iap.slacknotifications.spi.SlackNotificationProducer;

/**
 * Posts one notification to its chat webhook. Each run asks the included {@link SlackNotificationProducer}s for
 * their attachments, and posts them all as one message.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class SlackNotificationsTask implements Runnable
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SlackNotificationsTask.class);

    private final HttpRequests httpRequests;

    /** The registered producers. */
    private final List<SlackNotificationProducer> producers;

    private final String endpoint;

    private final String name;

    private final String title;

    private final List<String> include;

    private final Map<String, String> extraParameters;

    private final boolean skipEmpty;

    /**
     * Sets up the task for one notification.
     *
     * @param httpRequests the client that posts the message
     * @param producers the registered producers, a list that changes as producers come and go
     * @param endpoint the webhook URL to post to, resolved from the configuration
     * @param config the notification's configuration: its name, title, included producers, extra parameters, and
     *            whether to stay quiet when there is nothing to report
     */
    public SlackNotificationsTask(final HttpRequests httpRequests, final List<SlackNotificationProducer> producers,
        final String endpoint, final SlackNotificationConfiguration config)
    {
        this.httpRequests = httpRequests;
        // Wrapped, not copied: every run must see the producers registered by then
        this.producers = Collections.unmodifiableList(producers);
        this.endpoint = endpoint;
        this.name = config.name();
        this.title = config.title();
        this.include = asList(config.include());
        this.extraParameters = Map.copyOf(asParameters(config.notificationParameters()));
        this.skipEmpty = config.skipEmpty();
    }

    @Override
    public void run()
    {
        LOGGER.debug("Running the {} notification", this.name);
        final List<JsonObject> attachments = this.producers.stream()
            .filter(producer -> this.include.isEmpty() || this.include.contains(producer.getName()))
            .map(producer -> producer.prepareMessages(this.extraParameters))
            .filter(Objects::nonNull)
            .flatMap(List::stream)
            .toList();
        post(attachments);
    }

    /**
     * Posts the message, unless it has no attachments and {@code skipEmpty} is set. A failure is logged and
     * recorded.
     *
     * @param attachments the attachments the producers prepared, empty if they had nothing to report
     */
    private void post(final List<JsonObject> attachments)
    {
        if (attachments.isEmpty() && this.skipEmpty) {
            LOGGER.debug("Nothing to report, and this notification is configured to stay quiet");
            return;
        }
        final JsonObjectBuilder message = Json.createObjectBuilder();
        if (StringUtils.isNotBlank(this.title)) {
            message.add(SlackNotificationProducer.TEXT_KEY, this.title);
        }
        message.add("attachments", attachments.isEmpty() ? nothingToReport() : toArray(attachments));
        try {
            final HttpResponse response =
                this.httpRequests.post(this.endpoint, message.build().toString(), "application/json");
            if (!response.isSuccessful()) {
                LOGGER.warn("The notification was refused by the webhook with status {}: {}",
                    response.getStatusCode(), response.getBody());
                ErrorLogger.logProblem("the webhook refused the notification",
                    ErrorContext.of(SlackNotificationsTask.class, "post")
                        .with("status", response.getStatusCode()));
            }
        } catch (final IOException e) {
            LOGGER.warn("Failed to post the notification: {}", e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(SlackNotificationsTask.class, "post"));
        }
    }

    private JsonArrayBuilder toArray(final List<JsonObject> attachments)
    {
        final JsonArrayBuilder result = Json.createArrayBuilder();
        attachments.forEach(result::add);
        return result;
    }

    private JsonArrayBuilder nothingToReport()
    {
        return Json.createArrayBuilder().add(Json.createObjectBuilder()
            .add(SlackNotificationProducer.TEXT_KEY, "Nothing to report")
            .add(SlackNotificationProducer.TITLE_KEY, "All is good")
            .add(SlackNotificationProducer.COLOR_KEY, SlackNotificationProducer.INFO_COLOR));
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
                    ErrorContext.of(SlackNotificationsTask.class, "readParameters"));
            }
        }
        return result;
    }

    private static List<String> asList(final String[] configured)
    {
        return configured == null ? List.of() : List.of(configured);
    }
}

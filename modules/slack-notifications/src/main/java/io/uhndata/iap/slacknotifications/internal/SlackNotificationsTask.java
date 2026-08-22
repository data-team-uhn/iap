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
 * Gathers messages from the enabled {@link SlackNotificationProducer}s and posts them to a chat webhook as one
 * message. Scheduled by {@link ScheduledSlackNotification}, one task per configuration.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class SlackNotificationsTask implements Runnable
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SlackNotificationsTask.class);

    private final HttpRequests httpRequests;

    /** All the available notification producers; the ones to use are picked out of it at every run. */
    private final List<SlackNotificationProducer> producers;

    private final String endpoint;

    private final String title;

    private final List<String> include;

    private final Map<String, String> extraParameters;

    private final boolean skipEmpty;

    /**
     * Basic constructor.
     *
     * @param httpRequests used to post the message
     * @param producers all the available notification producers
     * @param endpoint the webhook address to post to
     * @param title an optional title to include in the message
     * @param include the names of the producers to use, empty to use all of them
     * @param extraParameters extra parameters to pass to the producers
     * @param skipEmpty whether to post nothing at all when there is nothing to report
     */
    public SlackNotificationsTask(final HttpRequests httpRequests, final List<SlackNotificationProducer> producers,
        final String endpoint, final String title, final List<String> include,
        final Map<String, String> extraParameters, final boolean skipEmpty)
    {
        this.httpRequests = httpRequests;
        // Wrapped, not copied: the caller keeps updating this list as producers come and go, and every run must see
        // the current set. Wrapping only takes away this task's ability to alter someone else's list
        this.producers = Collections.unmodifiableList(producers);
        this.endpoint = endpoint;
        this.title = title;
        // Copied, since these come from one configuration and are not meant to change under the task's feet
        this.include = List.copyOf(include);
        this.extraParameters = Map.copyOf(extraParameters);
        this.skipEmpty = skipEmpty;
    }

    @Override
    public void run()
    {
        LOGGER.debug("Running the {} notification", this.title);
        // Flattened, because a producer with nothing to say gives back an empty list rather than nothing at all:
        // counting those as content would post an empty message on every run
        final List<JsonObject> attachments = this.producers.stream()
            .filter(producer -> this.include.isEmpty() || this.include.contains(producer.getName()))
            .map(producer -> producer.prepareMessages(this.extraParameters))
            .filter(Objects::nonNull)
            .flatMap(List::stream)
            .toList();
        post(attachments);
    }

    /**
     * Posts the gathered messages, unless there is nothing to say and the configuration asked to stay quiet.
     *
     * @param attachments the messages the producers prepared
     */
    private void post(final List<JsonObject> attachments)
    {
        if (attachments.isEmpty() && this.skipEmpty) {
            LOGGER.debug("Nothing to report, and this notification is configured to stay quiet");
            return;
        }
        final JsonObjectBuilder message = Json.createObjectBuilder();
        if (StringUtils.isNotBlank(this.title)) {
            message.add(SlackNotificationProducer.TEXT, this.title);
        }
        message.add("attachments", attachments.isEmpty() ? nothingToReport() : toArray(attachments));
        try {
            final HttpResponse response =
                this.httpRequests.post(this.endpoint, message.build().toString(), "application/json");
            if (!response.isSuccessful()) {
                // The webhook was reached and refused the message, which no exception would have told us about
                LOGGER.warn("The notification was refused by the webhook with status {}: {}",
                    response.getStatusCode(), response.getBody());
                // A problem rather than a failure, since nothing was thrown and the endpoint is configuration. The
                // status goes in the context rather than the phrase: one refusing webhook is one thing to fix,
                // however many status codes it answers with
                ErrorLogger.logProblem("the webhook refused the notification",
                    ErrorContext.of(SlackNotificationsTask.class, "post")
                        .with("status", response.getStatusCode()));
            }
        } catch (final IOException e) {
            LOGGER.warn("Failed to post the notification: {}", e.getMessage(), e);
            // The whole point of a notification is to tell somebody something, so a notification that never
            // arrives is the one failure guaranteed to have nobody watching for it
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
            .add(SlackNotificationProducer.TEXT, "Nothing to report")
            .add(SlackNotificationProducer.TITLE, "All is good")
            .add(SlackNotificationProducer.COLOR, SlackNotificationProducer.INFO));
    }
}

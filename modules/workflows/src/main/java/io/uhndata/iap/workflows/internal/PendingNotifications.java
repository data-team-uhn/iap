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
package io.uhndata.iap.workflows.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.notifications.api.NotificationContext;
import io.uhndata.iap.notifications.api.NotificationService;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.models.Event;
import io.uhndata.iap.workflows.spi.Payloads;

/**
 * The notifications one delivery's message events announced, held until the engine sends them after the commit. A
 * notification that cannot be sent is recorded, and the delivery still succeeds.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class PendingNotifications
{
    /** The event property naming the template folder. */
    public static final String TEMPLATE_PROPERTY = "template";

    /** The event property listing who to tell. */
    public static final String RECIPIENTS_PROPERTY = "recipients";

    /** The event property saying how soon they should hear. */
    public static final String URGENCY_PROPERTY = "urgency";

    /**
     * The payload entries passed on to the template when the delivered event carries them: the decision a person
     * just made, and what they said about it.
     */
    static final List<String> CARRIED_ENTRIES =
        List.of(TaskCompletion.OUTCOME_PARAMETER, TaskCompletion.OUTCOME_NOTE_PARAMETER);

    private static final Logger LOGGER = LoggerFactory.getLogger(PendingNotifications.class);

    private final NotificationService notificationsService;

    private final List<Pending> pending = new ArrayList<>();

    /**
     * Constructor.
     *
     * @param notificationsService who tells people, once the delivery has committed
     */
    PendingNotifications(final NotificationService notificationsService)
    {
        this.notificationsService = notificationsService;
    }

    /**
     * Holds the notification a throwing event announces. An event that names no message announces nothing: it only
     * marks that the process got there.
     *
     * @param source the throwing event
     * @param subject what the notification is about
     * @param cause the event being delivered
     * @param actor the user the run acts for
     */
    void hold(final Event source, final Resource subject, final WorkflowEvent cause, final String actor)
    {
        final String name = source.getMessageName();
        if (name == null) {
            return;
        }
        final NotificationContext.Builder notification = NotificationContext.about(subject)
            .becauseOf(name)
            .by(actor)
            .urgency(source.get(URGENCY_PROPERTY, String.class))
            .using(source.get(TEMPLATE_PROPERTY, String.class));
        CARRIED_ENTRIES.forEach(entry -> notification.with(entry, Payloads.text(cause, entry)));
        final String[] recipients = source.get(RECIPIENTS_PROPERTY, String[].class);
        this.pending.add(new Pending(source.getPath(), notification.build(),
            recipients == null ? List.of() : Arrays.asList(recipients)));
    }

    /**
     * Sends every notification held, in the order they were announced. Called once the delivery has committed.
     */
    void send()
    {
        this.pending.forEach(this::send);
    }

    private void send(final Pending notification)
    {
        if (notification.recipients().isEmpty()) {
            // A message event telling nobody is a definition somebody meant to finish
            LOGGER.warn("The message event {} names no recipients", notification.source());
            ErrorLogger.logProblem("A message event names no recipients",
                ErrorContext.of(PendingNotifications.class, "send").about(notification.source()));
            return;
        }
        try {
            this.notificationsService.notify(notification.context(), notification.recipients());
        } catch (final RuntimeException e) {
            LOGGER.error("The {} notification from {} could not be sent: {}", notification.context().getEvent(),
                notification.source(), e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(PendingNotifications.class, "send")
                .about(notification.source())
                .with("event", notification.context().getEvent()));
        }
    }

    /**
     * One notification waiting for the commit.
     *
     * @param source the path of the message event that announced it
     * @param context what happened, and what it is about
     * @param recipients the roles to tell
     * @version $Id$
     * @since 0.1.0
     */
    private record Pending(String source, NotificationContext context, List<String> recipients)
    {
    }
}

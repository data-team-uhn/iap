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
package io.uhndata.iap.notifications.internal;

import java.util.Arrays;
import java.util.List;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.notifications.api.NotificationContext;
import io.uhndata.iap.notifications.api.NotificationService;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The workflow action that tells people something happened.
 *
 * <p>
 * <strong>One handler, however many notifications a process sends.</strong> What differs between "your request
 * was approved" and "your request was refused" is the template and the audience. Both are written in the workflow
 * definition rather than in Java. A service task naming this handler carries the template folder, the roles
 * to tell, and how urgent it is.
 * </p>
 *
 * {@snippet lang="json" :
 * "notifyApproved": {
 *   "jcr:primaryType": "wf:ServiceTask",
 *   "handler": "notify",
 *   "template": "/libs/iap/notificationTemplates/submissionApproved",
 *   "recipients": [ "@creator" ],
 *   "urgency": "immediate",
 *   "event": "approved"
 * }
 * }
 *
 * <p>
 * <strong>It never fails the workflow.</strong> A decision that has been made has been made. Rolling a
 * process back because a mail server was unreachable is worse than leaving its author untold. Anything that
 * goes wrong is recorded and the run carries on.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class NotifyHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "notify";

    /** The activity property naming the template folder. */
    static final String TEMPLATE_PARAMETER = "template";

    /** The activity property listing who to tell. */
    static final String RECIPIENTS_PARAMETER = "recipients";

    /** The activity property saying how soon they should hear. */
    static final String URGENCY_PARAMETER = "urgency";

    /**
     * The activity property naming what happened. Separate from the node's own id, so that two nodes can
     * report the same event with different templates. It also means a user setting keys on something a
     * definition chose, not on whatever the node happened to be called.
     */
    static final String EVENT_NAME_PARAMETER = "event";

    /** The payload entry carrying the decision a person just made, when this follows a completed task. */
    static final String OUTCOME_ENTRY = "outcome";

    /** The payload entry carrying what they said about it. */
    static final String OUTCOME_NOTE_ENTRY = "outcomeNote";

    private static final Logger LOGGER = LoggerFactory.getLogger(NotifyHandler.class);

    @Reference
    private NotificationService notifications;

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException
    {
        final Activity activity = context.getActivity();
        final String[] named = activity.get(RECIPIENTS_PARAMETER, String[].class);
        final List<String> roles = named == null ? List.of() : Arrays.asList(named);
        if (roles.isEmpty()) {
            // Said out loud: a notify task that tells nobody is a definition somebody meant to finish
            LOGGER.warn("The notification task {} names no recipients", activity.getPath());
            ErrorLogger.logProblem("A notify task names no recipients",
                ErrorContext.of(NotifyHandler.class, "execute").about(activity.getPath()));
            return;
        }
        final String event = activity.get(EVENT_NAME_PARAMETER, String.class);
        final NotificationContext.Builder builder = NotificationContext.about(context.getTarget())
            .becauseOf(event == null ? activity.getElementId() : event)
            .by(context.getActor())
            .urgency(activity.get(URGENCY_PARAMETER, String.class))
            .using(activity.get(TEMPLATE_PARAMETER, String.class));
        // What the person deciding chose and what they said about it, so that a template can quote the reason a
        // request was refused. Only when they are actually there: a template asks `#if($outcomeNote)`, and a
        // variable that is always present but sometimes empty would answer that question wrongly
        carry(context, builder, OUTCOME_ENTRY);
        carry(context, builder, OUTCOME_NOTE_ENTRY);
        final NotificationContext notification = builder.build();
        try {
            this.notifications.notify(notification, roles);
        } catch (final RuntimeException e) {
            // Never the workflow's problem: the decision this reports has already been taken, and undoing it
            // because nobody could be told would be the worse of the two outcomes
            LOGGER.error("The {} notification from {} could not be sent: {}", notification.getEvent(),
                activity.getPath(), e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(NotifyHandler.class, "execute")
                .about(activity.getPath())
                .with("event", notification.getEvent()));
        }
    }

    /**
     * Passes one entry of the triggering event on to the notification, if it carries anything to pass.
     *
     * @param context the executing task's context
     * @param builder the notification being described
     * @param name the payload entry to carry over, under the same name
     */
    private static void carry(final WorkflowTaskContext context, final NotificationContext.Builder builder,
        final String name)
    {
        final Object value = context.getEvent().get(name);
        if (value instanceof String && !((String) value).isBlank()) {
            builder.with(name, value);
        }
    }
}

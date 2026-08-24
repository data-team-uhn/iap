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
 * was approved" and "your request was refused" is wording and audience, and both are written in the workflow
 * definition rather than in Java: a service task naming this handler carries the template folder, the roles to
 * tell and how urgent it is. Adding a notification is adding a node and a template, not a component — which is
 * the only way a workflow author who cannot write Java can add one at all.
 * </p>
 *
 * <pre>
 * "notifyApproved": {
 *   "jcr:primaryType": "wf:ServiceTask",
 *   "handler": "notify",
 *   "template": "/libs/iap/mailTemplates/submissionApproved",
 *   "notify": [ "&#64;creator" ],
 *   "urgency": "immediate",
 *   "event": "approved"
 * }
 * </pre>
 *
 * <p>
 * <strong>It never fails the workflow.</strong> A decision that has been made has been made, and a process that
 * rolled back because a mail server was unreachable would be a worse outcome than one whose author was not told.
 * Anything that goes wrong is recorded and the run carries on.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class NotifyHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String NAME = "notify";

    /** The activity property naming the template folder. */
    static final String TEMPLATE = "template";

    /** The activity property listing the roles to tell. */
    static final String NOTIFY = "notify";

    /** The activity property saying how soon they should hear. */
    static final String URGENCY = "urgency";

    /**
     * The activity property naming what happened. Separate from the node's own id so that two nodes can report
     * the same event with different wording, and so that a user setting keys on something a definition chose
     * deliberately rather than on whatever the node happened to be called.
     */
    static final String EVENT = "event";

    private static final Logger LOGGER = LoggerFactory.getLogger(NotifyHandler.class);

    @Reference
    private NotificationService notifications;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException
    {
        final Activity activity = context.getActivity();
        final String[] named = activity.get(NOTIFY, String[].class);
        final List<String> roles = named == null ? List.of() : Arrays.asList(named);
        if (roles.isEmpty()) {
            // Said out loud: a notify task that tells nobody is a definition somebody meant to finish
            LOGGER.warn("The notification task {} names nobody to tell", activity.getPath());
            return;
        }
        final String event = activity.get(EVENT, String.class);
        final NotificationContext notification = NotificationContext.about(context.getTarget())
            .becauseOf(event == null ? activity.getElementId() : event)
            .by(context.getActor())
            .urgency(activity.get(URGENCY, String.class))
            .using(activity.get(TEMPLATE, String.class))
            .build();
        try {
            this.notifications.notify(notification, roles);
        } catch (final RuntimeException e) {
            // Never the workflow's problem: the decision this reports has already been taken, and undoing it
            // because nobody could be told would be the worse of the two outcomes
            LOGGER.error("The {} notification from {} could not be sent: {}", notification.getEvent(),
                activity.getPath(), e.getMessage(), e);
        }
    }
}

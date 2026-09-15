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

import java.util.List;

import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.notifications.api.NotificationContext;
import io.uhndata.iap.notifications.api.NotificationService;
import io.uhndata.iap.notifications.api.Recipient;
import io.uhndata.iap.notifications.spi.NotificationDelivery;
import io.uhndata.iap.principals.api.PrincipalContext;
import io.uhndata.iap.principals.api.PrincipalService;

/**
 * Resolves who a notification concerns and offers it to every registered way of telling them.
 *
 * <p>
 * The only judgement here is <em>who</em>. <em>How</em> is each delivery's own answer. A notification no
 * delivery accepts is one nobody was told. That is a normal outcome, not a failure: somebody with no address
 * and no other channel is simply not reachable.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = NotificationService.class)
public class NotificationServiceImpl implements NotificationService
{
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationServiceImpl.class);

    /** Every registered way of telling somebody something. A dynamic whiteboard, so a bundle may add one. */
    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC,
        policyOption = ReferencePolicyOption.GREEDY)
    private volatile List<NotificationDelivery> deliveries;

    /** The vocabulary the roles are read in: the same one the workflow engine reads performers in. */
    @Reference
    private PrincipalService principals;

    @Override
    public void notify(final NotificationContext notification, final List<String> roles)
    {
        final List<NotificationDelivery> channels = currentDeliveries();
        if (channels.isEmpty()) {
            LOGGER.warn("Nothing is registered to deliver notifications, so nobody was told about {} on {}",
                notification.getEvent(), notification.getSubject().getPath());
            ErrorLogger.logProblem("No notification delivery is registered, so nobody can be told anything",
                ErrorContext.of(NotificationServiceImpl.class, "notify")
                    .about(notification.getSubject().getPath())
                    .with("event", notification.getEvent()));
            return;
        }
        final ResourceResolver resolver = notification.getSubject().getResourceResolver();
        // Resolved and expanded by the shared vocabulary, so "notify the approvers" and "the approvers may
        // act" name the same people. Enumerated here because telling, unlike checking, names each person.
        final List<String> userIds = this.principals.expandToUsers(
            this.principals.resolve(roles,
                new PrincipalContext(notification.getSubject(), notification.getActor())),
            resolver);
        for (final Recipient recipient : Recipients.of(resolver, userIds)) {
            deliver(channels, notification, recipient);
        }
    }

    /**
     * Offers one notification to every delivery, in turn.
     *
     * <p>Every one of them, not the first that accepts. A person may want both an email now and a marker in
     * the interface.</p>
     *
     * @param channels the registered deliveries
     * @param notification what happened
     * @param recipient who to tell
     */
    private static void deliver(final List<NotificationDelivery> channels,
        final NotificationContext notification, final Recipient recipient)
    {
        for (final NotificationDelivery channel : channels) {
            try {
                channel.deliver(notification, recipient);
            } catch (final RuntimeException e) {
                // One channel failing is not the others' problem, nor the workflow's: the process it
                // reports on has already happened.
                LOGGER.error("A notification about {} could not be delivered: {}",
                    notification.getEvent(), e.getMessage(), e);
                ErrorLogger.logError(e, ErrorContext.of(NotificationServiceImpl.class, "deliver")
                    .about(notification.getSubject().getPath())
                    .with("event", notification.getEvent())
                    .with("channel", channel.getClass().getName())
                    .with("recipient", recipient.userId()));
            }
        }
    }

    /**
     * The deliveries registered right now. A dynamic whiteboard field is null until something registers, and the
     * list itself can change under a call, so it is copied before being walked.
     *
     * @return the current deliveries
     */
    private List<NotificationDelivery> currentDeliveries()
    {
        final List<NotificationDelivery> current = this.deliveries;
        return current == null ? List.of() : List.copyOf(current);
    }
}

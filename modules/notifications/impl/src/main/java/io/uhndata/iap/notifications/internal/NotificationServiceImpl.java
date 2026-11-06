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
 * Finds the people a notification concerns, and offers it to every registered delivery for each of them.
 *
 * <p>
 * It decides only <em>who</em>. Each delivery decides <em>how</em>. A person every delivery declines is not told,
 * and that is not a failure. Having no delivery registered at all is logged and recorded.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = NotificationService.class)
public class NotificationServiceImpl implements NotificationService
{
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationServiceImpl.class);

    /** Every registered delivery. A bundle can register or withdraw one at any time. */
    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC,
        policyOption = ReferencePolicyOption.GREEDY)
    private volatile List<NotificationDelivery> deliveries;

    /** Turns the roles into user ids. */
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
        final List<String> userIds = this.principals.expandToUsers(
            this.principals.resolve(roles,
                new PrincipalContext(notification.getSubject(), notification.getActor())),
            resolver);
        for (final Recipient recipient : Recipients.of(resolver, userIds)) {
            deliver(channels, notification, recipient);
        }
    }

    /**
     * Offers one notification to every delivery in turn, for one person, whatever the others answered.
     *
     * @param channels the registered deliveries
     * @param notification what happened
     * @param recipient the person to tell
     */
    private static void deliver(final List<NotificationDelivery> channels,
        final NotificationContext notification, final Recipient recipient)
    {
        for (final NotificationDelivery channel : channels) {
            try {
                channel.deliver(notification, recipient);
            } catch (final RuntimeException e) {
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
     * The deliveries registered right now.
     *
     * @return a copy of the registered deliveries, empty if there are none
     */
    private List<NotificationDelivery> currentDeliveries()
    {
        final List<NotificationDelivery> current = this.deliveries;
        return current == null ? List.of() : List.copyOf(current);
    }
}

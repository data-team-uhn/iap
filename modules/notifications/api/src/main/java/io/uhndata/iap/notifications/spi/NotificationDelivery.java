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
package io.uhndata.iap.notifications.spi;

import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.notifications.api.NotificationContext;
import io.uhndata.iap.notifications.api.Recipient;

/**
 * One way of telling a person about a notification. Any bundle may register one as an OSGi service.
 *
 * <p>
 * <strong>Deliveries are not alternatives.</strong> Every one is offered every notification, for each person it
 * concerns, and decides for itself whether to carry it and how.
 * </p>
 *
 * <p>
 * <strong>Declining is a normal answer, not a failure.</strong> A delivery declines what it cannot carry, such as a
 * recipient with no address for its channel.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface NotificationDelivery
{
    /**
     * Offers a notification to this delivery, for one person.
     *
     * @param notification what happened, and the name of its template
     * @param recipient the person to tell; a role has already been resolved into people
     * @return {@code true} if this delivery carried the notification, {@code false} if it declined it; the other
     *         deliveries are offered it either way
     */
    boolean deliver(@NotNull NotificationContext notification, @NotNull Recipient recipient);
}

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
 * One way of telling somebody something. Any bundle may register one; each is offered every notification and
 * decides for itself whether it is the one to carry it.
 *
 * <p>
 * The immediate email delivery is the only one today. The shape anticipates the others without building them: a
 * digest collector would accept only {@link NotificationContext#BATCHED} and write the notification down instead
 * of sending anything, and an in-app delivery would accept everything and store an unread marker. None of that
 * changes a workflow definition, which is the point of the split.
 * </p>
 *
 * <p>
 * <strong>Deciding not to deliver is a normal answer, not a failure.</strong> A delivery declines by returning
 * {@code false}, and the caller carries on: a notification the recipient has opted out of is still a notification
 * that was correctly handled.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface NotificationDelivery
{
    /**
     * Tells one person about one thing, if this is the way to tell them.
     *
     * @param notification what happened, including where its wording lives
     * @param recipient who to tell
     * @return {@code true} if this delivery carried it, {@code false} if it is not the one to
     */
    boolean deliver(@NotNull NotificationContext notification, @NotNull Recipient recipient);
}

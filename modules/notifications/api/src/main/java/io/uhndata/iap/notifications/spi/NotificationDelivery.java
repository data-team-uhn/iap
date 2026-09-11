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
 * One way of delivering a notification. Any bundle may register one; each is offered every notification and
 * decides for itself whether to carry it.
 *
 * <p>
 * <strong>They are not alternatives.</strong> Every delivery is offered every notification.
 * </p>
 *
 * <p>
 * <strong>Deciding not to deliver is a normal answer, not a failure.</strong> A delivery can decline, and the caller
 * carries on: a notification the recipient has opted out of is still a notification that was correctly handled.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface NotificationDelivery
{
    /**
     * Tells one person about one thing, if this is an accepted way to tell them.
     *
     * @param notification what happened, including where its template lives
     * @param recipient who to tell
     * @return {@code true} if this delivery acted on this request, {@code false} if it declined to act;
     *         other deliveries are offered it either way
     */
    boolean deliver(@NotNull NotificationContext notification, @NotNull Recipient recipient);
}

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
 * decides for itself whether to carry it.
 *
 * <p>
 * <strong>They are not alternatives.</strong> Every delivery is offered every notification, so the same one
 * routinely goes out more than once — emailed straight away <em>and</em> kept as a record somebody can come back
 * to, which is what the two shipped deliveries do. That is the intended behaviour, not double-telling: each
 * channel answers a different question, "did I hear about this" and "what happened while I was away". A digest
 * collector added later would accept only {@link NotificationContext#BATCHED} and write the notification down
 * instead of sending anything, again alongside the rest. None of it changes a workflow definition, which is the
 * point of the split.
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
     * Tells one person about one thing, if this is a way to tell them.
     *
     * @param notification what happened, including where its wording lives
     * @param recipient who to tell
     * @return {@code true} if this delivery carried it, {@code false} if it declined; other deliveries are
     *         offered it either way
     */
    boolean deliver(@NotNull NotificationContext notification, @NotNull Recipient recipient);
}

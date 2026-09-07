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
package io.uhndata.iap.notifications.api;

import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One person a notification is for: who they are, and their account to read the rest from.
 *
 * <p>
 * Deliberately nothing about any channel. How to reach somebody — an email address, a phone number, a
 * notification setting — is a fact about their account, read by the one delivery that needs it: the email
 * delivery reads {@code profile/email} and declines the accounts without one, and a channel added later reads its
 * own facts without this record learning what they are. Carrying the account rather than pre-reading any of it is
 * also what keeps the reading privileged-but-contained: the account resource is backed by the notification
 * service's own session, so a delivery reads accounts without needing rights of its own.
 * </p>
 *
 * <p>
 * The account is only alive for the duration of {@link io.uhndata.iap.notifications.spi.NotificationDelivery
 * NotificationDelivery.deliver}: it is closed with the session that resolved it, so a delivery that queues work
 * for later — a digest collector, say — must read what it needs and let go, never store the resource.
 * </p>
 *
 * @param userId the repository user id, which is what a per-user setting is keyed on
 * @param account the user's account, home of everything else about them
 * @version $Id$
 * @since 0.1.0
 */
public record Recipient(@NotNull String userId, @NotNull Resource account)
{
    /**
     * What to call this person.
     *
     * @return their recorded full name, or {@code null} when the account does not say
     */
    @Nullable
    public String name()
    {
        return this.account.getValueMap().get("rep:fullname", String.class);
    }
}

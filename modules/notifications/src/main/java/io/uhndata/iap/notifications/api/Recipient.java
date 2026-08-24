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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One person a notification is for.
 *
 * <p>
 * The user id is the durable half and is always there; an address may not be, because whether the platform knows
 * how to email somebody is a fact about that person's account rather than about the notification. A delivery that
 * needs an address says so by declining the ones without — which is not an error, and is why this carries the
 * account rather than only the address.
 * </p>
 *
 * @param userId the repository user id, which is what a per-user setting is keyed on
 * @param name what to call them, or {@code null} when the account does not say
 * @param address their email address, or {@code null} when the account does not have one
 * @version $Id$
 * @since 0.1.0
 */
public record Recipient(@NotNull String userId, @Nullable String name, @Nullable String address)
{
    /**
     * Whether this person can be reached by email at all.
     *
     * @return {@code true} if an address is known
     */
    public boolean isEmailable()
    {
        return this.address != null && !this.address.isBlank();
    }
}

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
 * One person a notification is for: who they are, and their account.
 *
 * <p>
 * It says nothing about any channel. How to reach somebody is on their account, and each delivery reads what it
 * needs from there, such as an email address at {@code profile/email}.
 * </p>
 *
 * <p>
 * A delivery reads the account without rights of its own. The resource is valid only during
 * {@link io.uhndata.iap.notifications.spi.NotificationDelivery#deliver deliver}. A delivery that queues work for
 * later copies what it needs, and does not keep the resource.
 * </p>
 *
 * @param userId the user id of the account
 * @param account the user's home node in the user store, such as {@code /home/users/j/jdoe}
 * @version $Id$
 * @since 0.1.0
 */
public record Recipient(@NotNull String userId, @NotNull Resource account)
{
    /**
     * What to call this person.
     *
     * @return the account's {@code rep:fullname}, or {@code null} if it has none
     */
    @Nullable
    public String name()
    {
        return this.account.getValueMap().get("rep:fullname", String.class);
    }
}

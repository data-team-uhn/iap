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

import java.util.List;

import org.jetbrains.annotations.NotNull;

/**
 * The notification service, used to tell people something happened, by whatever means reaches each of them.
 *
 * <p>
 * <strong>This is not "send an email".</strong> A caller states that something happened, who it concerns, and
 * how soon they should know. What that turns into depends on the context of the notification, which delivery channels
 * are enabled, and the recipients' own settings.
 * </p>
 *
 * <p>
 * Recipients are named by role rather than by address, in the same vocabulary workflows already use to say who
 * may act. {@code PrincipalService} turns those roles into people.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface NotificationService
{
    /**
     * Send a notification to the given roles. Delivery is not guaranteed, this is a quick "fire-and-forget" service
     * that will pass on the notification to each delivery channel and each user the target roles resolve to, but will
     * not wait for confirmation of delivery or receipt.
     *
     * <p>Nothing is guaranteed to reach anybody. A role may resolve to nobody, a person may have no configured channel
     * that accepts, and a person may have opted out of notifications. None of those is an error: a notification is an
     * attempt to inform, and the workflow that raised it carries on either way.</p>
     *
     * @param notification what happened
     * @param roles who it concerns, as a list of roles
     */
    void notify(@NotNull NotificationContext notification, @NotNull List<String> roles);
}

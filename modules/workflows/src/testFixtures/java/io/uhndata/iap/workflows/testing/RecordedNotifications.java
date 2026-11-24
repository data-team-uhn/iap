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
package io.uhndata.iap.workflows.testing;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.notifications.api.NotificationContext;
import io.uhndata.iap.notifications.api.NotificationService;

/**
 * A notification service that records what it is asked to send instead of sending it.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class RecordedNotifications implements NotificationService
{
    private final List<Sent> sent = new ArrayList<>();

    @Override
    public void notify(@NotNull final NotificationContext notification, @NotNull final List<String> roles)
    {
        this.sent.add(new Sent(notification, List.copyOf(roles)));
    }

    /**
     * The notifications it was asked to send.
     *
     * @return every notification, in the order it was sent; empty if none was
     */
    @NotNull
    public List<Sent> sent()
    {
        return List.copyOf(this.sent);
    }

    /**
     * The event each notification was about.
     *
     * @return the event names, such as {@code approved}, in the order the notifications were sent
     */
    @NotNull
    public List<String> events()
    {
        return this.sent.stream().map(one -> one.notification().getEvent()).toList();
    }

    /**
     * One notification sent.
     *
     * @param notification what happened, and to which subject
     * @param roles who it concerns: user ids, group names, and special names such as {@code @creator}
     * @version $Id$
     * @since 0.1.0
     */
    public record Sent(@NotNull NotificationContext notification, @NotNull List<String> roles)
    {
    }
}

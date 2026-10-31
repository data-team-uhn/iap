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
package io.uhndata.iap.workflows.internal;

import java.util.ArrayList;
import java.util.List;

import io.uhndata.iap.notifications.api.NotificationContext;
import io.uhndata.iap.notifications.api.NotificationService;

/**
 * A notification service that records what it is asked to send, so that a test can see who an engine told what.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class RecordedNotifications implements NotificationService
{
    private final List<Sent> sent = new ArrayList<>();

    @Override
    public void notify(final NotificationContext notification, final List<String> roles)
    {
        this.sent.add(new Sent(notification, List.copyOf(roles)));
    }

    /**
     * What was sent.
     *
     * @return every notification, in the order it was sent
     */
    List<Sent> sent()
    {
        return this.sent;
    }

    /**
     * What happened, by each notification sent.
     *
     * @return the event names, in the order they were sent
     */
    List<String> events()
    {
        return this.sent.stream().map(one -> one.notification().getEvent()).toList();
    }

    /**
     * One notification sent.
     *
     * @param notification what happened, and what it is about
     * @param roles who to tell
     * @version $Id$
     * @since 0.1.0
     */
    record Sent(NotificationContext notification, List<String> roles)
    {
    }
}

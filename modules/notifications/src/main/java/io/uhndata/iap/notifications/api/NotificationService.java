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
 * Tells the people a notification concerns, by whatever means each of them is reached by.
 *
 * <p>
 * <strong>This is not "send an email".</strong> What a caller states is that something happened, who it concerns
 * and how soon they should know; what that turns into — an email now, a line in tonight's digest, an unread
 * marker for somebody who has turned email off — is decided here and below, from the recipients' own settings.
 * A caller that decided the channel itself would have to be revisited every time somebody changed their mind
 * about how they want to be told.
 * </p>
 *
 * <p>
 * Recipients are named by <em>role</em> rather than by address, in the same vocabulary a workflow uses to say who
 * may act: {@code @creator} is whoever raised the subject, and anything else is a group or a user id. A workflow
 * definition therefore never carries an address, which is what keeps it true when people change.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface NotificationService
{
    /** The role meaning whoever raised the subject the notification is about. */
    String CREATOR_ROLE = "@creator";

    /**
     * Notifies everyone the given roles resolve to.
     *
     * <p>Nothing is guaranteed to reach anybody: a role may resolve to nobody, a person may have no address, and
     * a person may have asked not to be told. None of those is an error — a notification is an attempt to inform,
     * and the workflow that raised it carries on either way.</p>
     *
     * @param notification what happened
     * @param roles who it concerns
     * @return the people it was delivered to, empty when it reached nobody
     */
    @NotNull
    List<Recipient> notify(@NotNull NotificationContext notification, @NotNull List<String> roles);
}

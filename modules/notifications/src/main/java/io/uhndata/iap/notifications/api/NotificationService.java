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
 * Tells people something happened, by whatever means reaches each of them.
 *
 * <p>
 * <strong>This is not "send an email".</strong> A caller states that something happened, who it concerns and
 * how soon they should know. What that turns into is decided here and below, from the recipients' own settings:
 * an email now, a line in tonight's digest, an unread marker for somebody who has turned email off. A caller
 * that chose the channel itself would have to be revisited whenever somebody changed their mind about how they
 * want to be told.
 * </p>
 *
 * <p>
 * Recipients are named by <em>role</em> rather than by address, and who a role names is not this module's
 * judgement to make: the names are resolved by the principals service, the same one the workflow engine asks who
 * may act, so {@code @creator} means the same person whether it is being asked to approve something or being told
 * the outcome. A workflow definition therefore never carries an address, which is what keeps it true when people
 * change.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface NotificationService
{
    /**
     * Notifies everyone the given roles resolve to.
     *
     * <p>Nothing is guaranteed to reach anybody. A role may resolve to nobody, a person may have no channel
     * that accepts, and a person may have asked not to be told. None of those is an error: a notification is an
     * attempt to inform, and the workflow that raised it carries on either way.</p>
     *
     * <p>Nothing comes back. A delivery accepting says nothing about a message arriving, so a list of "the
     * people told" would claim more than anything here can know. And the one thing a caller could do with such a
     * list is decide how people are told, which is the decision this service exists to take away from
     * callers.</p>
     *
     * @param notification what happened
     * @param roles who it concerns, in the same vocabulary a workflow names performers in
     */
    void notify(@NotNull NotificationContext notification, @NotNull List<String> roles);
}

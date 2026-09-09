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
 * how soon they should know. What that turns into is decided here and below, from the recipients' own
 * settings.
 * </p>
 *
 * <p>
 * Recipients are named by role rather than by address, in the vocabulary a workflow already uses to say who
 * may act. {@code PrincipalService} turns those roles into people, so a definition never carries an address
 * to go stale.
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
     * people told" would claim more than anything here can know. And the one thing a caller could do with such
     * a list is decide how people are told. That is the decision this service exists to take away.</p>
     *
     * @param notification what happened
     * @param roles who it concerns, in the same vocabulary a workflow names performers in
     */
    void notify(@NotNull NotificationContext notification, @NotNull List<String> roles);
}

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
 * Tells people that something happened, through whichever deliveries can reach each of them.
 *
 * <p>
 * A caller says what happened, who it concerns, and how soon they should know. It never chooses a channel. The
 * deliveries that are registered decide that, each from the notification and the recipient's account.
 * </p>
 *
 * <p>
 * Recipients are named by role, not by address. The roles use the vocabulary of workflow performers, and
 * {@code PrincipalService} turns them into people, so a name means the same people in both places.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface NotificationService
{
    /**
     * Offers a notification to every delivery, for each person the roles name. It returns once every delivery has
     * answered, and does not say who was told.
     *
     * <p>Telling nobody is not an error. A role may name nobody, and every delivery may decline a person. A delivery
     * that throws is logged and recorded, and the others are still offered the notification.</p>
     *
     * <p>The people are found, and their accounts read, through the session of the notification's subject. That
     * session must be able to read the user store.</p>
     *
     * @param notification what happened
     * @param roles who it concerns: user ids, group names, and special names such as {@code @creator}, which are
     *            answered for the notification's subject and actor
     */
    void notify(@NotNull NotificationContext notification, @NotNull List<String> roles);
}

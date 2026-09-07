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
package io.uhndata.iap.notifications.internal;

import java.util.List;
import java.util.Objects;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.notifications.api.Recipient;

/**
 * Turns user ids into the people a notification is for, each carrying their account.
 *
 * <p>
 * The judgement of <em>who a role names</em> is not made here — the principals service resolves special names and
 * expands groups, the same way the workflow engine reads performers, so a definition means the same person in
 * both places. What is left for this class is the notification-specific half: finding each person's account and
 * handing it over, so that a delivery can read whatever channel facts it needs without rights of its own.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class Recipients
{
    private static final Logger LOGGER = LoggerFactory.getLogger(Recipients.class);

    private Recipients()
    {
        // Utility class
    }

    /**
     * The people behind a list of user ids, each with their account, in the given order.
     *
     * @param resolver a session that may read the user home
     * @param userIds whose accounts to find
     * @return the recipients, skipping anybody whose account cannot be read
     */
    static List<Recipient> of(final ResourceResolver resolver, final List<String> userIds)
    {
        final UserManager users = userManager(resolver);
        if (users == null) {
            return List.of();
        }
        return userIds.stream()
            .map(userId -> describe(resolver, users, userId))
            .filter(Objects::nonNull)
            .toList();
    }

    /**
     * One person, as their account tells it.
     *
     * @param resolver the session the account resource is served through
     * @param users the repository's user manager
     * @param userId whose account to read
     * @return the recipient, or {@code null} when the account cannot be read at all
     */
    private static Recipient describe(final ResourceResolver resolver, final UserManager users,
        final String userId)
    {
        try {
            final Authorizable account = users.getAuthorizable(userId);
            if (account == null) {
                LOGGER.warn("{} has no account in this repository, so they cannot be told anything", userId);
                return null;
            }
            final Resource home = resolver.getResource(account.getPath());
            if (home == null) {
                LOGGER.warn("The account of {} is not readable, so they cannot be told anything", userId);
                return null;
            }
            return new Recipient(userId, home);
        } catch (final RepositoryException e) {
            LOGGER.warn("The account of {} could not be read: {}", userId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * The repository's user manager, when this session can reach it.
     *
     * @param resolver the session to ask
     * @return the user manager, or {@code null} when the session is not a Jackrabbit one
     */
    private static UserManager userManager(final ResourceResolver resolver)
    {
        final Session session = resolver.adaptTo(Session.class);
        if (!(session instanceof JackrabbitSession)) {
            LOGGER.warn("Notifications need a Jackrabbit session to find out who to tell");
            return null;
        }
        try {
            return ((JackrabbitSession) session).getUserManager();
        } catch (final RepositoryException e) {
            LOGGER.warn("The user manager is not available: {}", e.getMessage(), e);
            return null;
        }
    }
}

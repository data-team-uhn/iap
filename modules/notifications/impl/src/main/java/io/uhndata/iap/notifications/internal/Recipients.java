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

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.notifications.api.Recipient;

/**
 * Finds the account of each person a notification is for.
 *
 * <p>
 * The user ids come already resolved, with groups expanded into their members. Each recipient carries their home
 * node, read through the session given.
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
     * @param resolver the session to find the accounts with; it must be able to read the user store
     * @param userIds the user ids of the people to find
     * @return the recipients, without anybody whose account cannot be found or read; empty if the session cannot
     *         reach the user store
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
     * One person and their account.
     *
     * @param resolver the session to read the account's home node with
     * @param users the repository's user manager
     * @param userId the user id of the person
     * @return the recipient, or {@code null} if there is no such account, its home node cannot be read, or the
     *         repository fails
     */
    private static Recipient describe(final ResourceResolver resolver, final UserManager users,
        final String userId)
    {
        try {
            // A missing or unreadable account concerns one person, so it is logged and not recorded
            final Authorizable account = users.getAuthorizable(userId);
            if (account == null) {
                LOGGER.warn("A recipient has no account in this repository, so they cannot be told anything");
                return null;
            }
            final Resource home = resolver.getResource(account.getPath());
            if (home == null) {
                LOGGER.warn("A recipient's account is not readable, so they cannot be told anything");
                return null;
            }
            return new Recipient(userId, home);
        } catch (final RepositoryException e) {
            LOGGER.warn("A recipient's account could not be read: {}", e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(Recipients.class, "describe")
                .with("recipient", userId));
            return null;
        }
    }

    /**
     * The repository's user manager.
     *
     * @param resolver the session to ask
     * @return the user manager, or {@code null} if the session is not a Jackrabbit one or cannot reach it
     */
    private static UserManager userManager(final ResourceResolver resolver)
    {
        final Session session = resolver.adaptTo(Session.class);
        if (!(session instanceof JackrabbitSession)) {
            LOGGER.warn("Notifications need a Jackrabbit session to find out who to tell");
            ErrorLogger.logProblem("Notifications cannot resolve anybody: the session is not a Jackrabbit one",
                ErrorContext.of(Recipients.class, "userManager"));
            return null;
        }
        try {
            return ((JackrabbitSession) session).getUserManager();
        } catch (final RepositoryException e) {
            LOGGER.warn("The user manager is not available: {}", e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(Recipients.class, "userManager"));
            return null;
        }
    }
}

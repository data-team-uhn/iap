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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.notifications.api.NotificationService;
import io.uhndata.iap.notifications.api.Recipient;

/**
 * Turns the roles a workflow names into the people they mean.
 *
 * <p>
 * The vocabulary is the one a workflow already uses to say who may act, so that a definition says
 * {@code @creator} in both places and means the same person: {@code @creator} is whoever raised the subject, and
 * anything else is a group — expanded to its members — or a user id.
 * </p>
 *
 * <p>
 * <strong>The address comes from {@code profile/email} on the account</strong>, which is where Keycloak's sync
 * handler already puts it for anybody signing in through OIDC, and where the user profiles work reads it. That
 * is the whole of the lookup: a workflow definition never carries an address, so people can change and the
 * definition stays true.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class Recipients
{
    /** Where an account's email address lives, as Keycloak's sync handler writes it. */
    static final String EMAIL_PROPERTY = "profile/email";

    /** What an account calls its holder, when it says. */
    private static final String NAME_PROPERTY = "rep:fullname";

    private static final Logger LOGGER = LoggerFactory.getLogger(Recipients.class);

    private Recipients()
    {
        // Utility class
    }

    /**
     * Everyone the given roles resolve to, each once, in the order the roles were given.
     *
     * @param resolver a session that may read the user home
     * @param subject what the notification is about, which is what {@code @creator} is asked about
     * @param roles the roles to resolve
     * @return the people to tell, empty when the roles name nobody reachable
     */
    static List<Recipient> of(final ResourceResolver resolver, final Resource subject, final List<String> roles)
    {
        final UserManager users = userManager(resolver);
        if (users == null) {
            return List.of();
        }
        // Keyed by user id so that somebody named twice — once directly and once through a group — is told once,
        // and ordered so that a definition's own order is what a reader sees
        final Map<String, Recipient> found = new LinkedHashMap<>();
        for (final String role : roles) {
            for (final String userId : principals(role, subject, users)) {
                // computeIfAbsent records nothing when the function returns null, so an account that cannot be
                // read drops out here rather than having to be filtered away afterwards
                found.computeIfAbsent(userId, id -> describe(users, id));
            }
        }
        return List.copyOf(found.values());
    }

    /**
     * The user ids one role names.
     *
     * @param role the role to resolve
     * @param subject what the notification is about
     * @param users the repository's user manager
     * @return the user ids, empty when the role names nobody
     */
    private static List<String> principals(final String role, final Resource subject, final UserManager users)
    {
        if (NotificationService.CREATOR_ROLE.equals(role)) {
            final String creator = creatorOf(subject);
            return creator == null ? List.of() : List.of(creator);
        }
        try {
            final Authorizable authorizable = users.getAuthorizable(role);
            if (authorizable == null) {
                LOGGER.warn("The notification role {} names nobody in this repository", role);
                return List.of();
            }
            return authorizable.isGroup() ? members((Group) authorizable) : List.of(authorizable.getID());
        } catch (final RepositoryException e) {
            LOGGER.warn("The notification role {} could not be resolved: {}", role, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Everybody in a group, including through nested groups: somebody told by virtue of belonging to a group
     * should be told whether they belong to it directly or through another.
     *
     * @param group the group to expand
     * @return its members' user ids
     * @throws RepositoryException if the members cannot be read
     */
    private static List<String> members(final Group group) throws RepositoryException
    {
        final List<String> ids = new ArrayList<>();
        final Iterator<Authorizable> all = group.getMembers();
        while (all.hasNext()) {
            final Authorizable member = all.next();
            if (!member.isGroup()) {
                ids.add(member.getID());
            }
        }
        return ids;
    }

    /**
     * Who raised the subject. Read from the {@code createdBy} the engine records rather than from
     * {@code jcr:createdBy}, which names the engine's own service user for everything it writes.
     *
     * @param subject what the notification is about
     * @return the creator's user id, or {@code null} when the subject does not say
     */
    private static String creatorOf(final Resource subject)
    {
        return subject.getValueMap().get("createdBy", String.class);
    }

    /**
     * What is known about one person.
     *
     * @param users the repository's user manager
     * @param userId whose account to read
     * @return the recipient, or {@code null} when the account cannot be read at all
     */
    private static Recipient describe(final UserManager users, final String userId)
    {
        try {
            final Authorizable account = users.getAuthorizable(userId);
            if (account == null) {
                return null;
            }
            return new Recipient(userId, value(account, NAME_PROPERTY), value(account, EMAIL_PROPERTY));
        } catch (final RepositoryException e) {
            LOGGER.warn("The account of {} could not be read: {}", userId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * One property of an account, when it has one.
     *
     * @param account whose property to read
     * @param name the property, relative to the account's home node
     * @return its first value, or {@code null} when the account does not carry it
     * @throws RepositoryException if the account cannot be read
     */
    private static String value(final Authorizable account, final String name) throws RepositoryException
    {
        if (!account.hasProperty(name)) {
            return null;
        }
        final javax.jcr.Value[] values = account.getProperty(name);
        return values == null || values.length == 0 ? null : values[0].getString();
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

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
package io.uhndata.iap.profiles.internal;

import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.ValueFactory;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reaches accounts with the platform's own credentials. All of Jackrabbit's user management is behind this, so that the
 * service above it reads as the policy it is, and so that a test can stand in for a repository the mocks do not have.
 *
 * @version $Id$
 * @since 0.1.0
 */
class Accounts
{
    /** The subservice name these resolvers are opened with. */
    static final String SUBSERVICE = "userprofile";

    private final ResourceResolverFactory resolverFactory;

    /**
     * Basic constructor.
     *
     * @param resolverFactory opens the resolvers everything here reads and writes with
     */
    Accounts(@NotNull final ResourceResolverFactory resolverFactory)
    {
        this.resolverFactory = resolverFactory;
    }

    /**
     * Opens a resolver with the platform's own credentials.
     *
     * @return a resolver the caller must close
     * @throws LoginException if the service user is not set up
     */
    @NotNull
    ResourceResolver open() throws LoginException
    {
        return this.resolverFactory
            .getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, SUBSERVICE));
    }

    /**
     * Finds one person's account.
     *
     * @param resolver a resolver holding the platform's credentials
     * @param accountId the account to look for
     * @return the account, or {@code null} if there is no such person
     * @throws RepositoryException if the repository cannot be asked
     */
    @Nullable
    Authorizable find(@NotNull final ResourceResolver resolver, @NotNull final String accountId)
        throws RepositoryException
    {
        // Answered here rather than left to the user management, which reports an empty id by throwing an
        // IllegalArgumentException -- an unchecked one, which would escape the service and be served as a 500 where
        // "there is no such person" is the honest answer
        if (accountId.isBlank()) {
            return null;
        }
        final UserManager users = userManager(resolver);
        final Authorizable found = users == null ? null : users.getAuthorizable(accountId);
        // A group has no profile, and answering for one would invite a caller to treat the two as interchangeable
        return found == null || found.isGroup() ? null : found;
    }

    /**
     * The user management of the session behind a resolver.
     *
     * @param resolver a resolver holding the platform's credentials
     * @return Jackrabbit's user management, or {@code null} when the session is not Jackrabbit's
     * @throws RepositoryException if the repository cannot be asked
     */
    @Nullable
    UserManager userManager(@NotNull final ResourceResolver resolver) throws RepositoryException
    {
        final Session session = resolver.adaptTo(Session.class);
        return session instanceof JackrabbitSession ? ((JackrabbitSession) session).getUserManager() : null;
    }

    /**
     * The factory turning strings into values that can be stored.
     *
     * @param resolver a resolver holding the platform's credentials
     * @return a value factory
     * @throws RepositoryException if the repository cannot be asked
     */
    @NotNull
    ValueFactory values(@NotNull final ResourceResolver resolver) throws RepositoryException
    {
        return session(resolver).getValueFactory();
    }

    /**
     * Persists everything written through the given resolver, in one commit.
     *
     * @param resolver a resolver holding the platform's credentials
     * @throws RepositoryException if the changes are refused
     */
    void save(@NotNull final ResourceResolver resolver) throws RepositoryException
    {
        session(resolver).save();
    }

    /**
     * The JCR session behind a resolver.
     *
     * @param resolver a resolver holding the platform's credentials
     * @return the session
     * @throws RepositoryException if the resolver has no session, which means the repository is not there
     */
    @NotNull
    private static Session session(@NotNull final ResourceResolver resolver) throws RepositoryException
    {
        final Session session = resolver.adaptTo(Session.class);
        if (session == null) {
            throw new RepositoryException("The profile service has no session to work with");
        }
        return session;
    }
}

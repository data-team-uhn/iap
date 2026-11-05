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
package io.uhndata.iap.submissions.internal.transfer;

import java.util.Iterator;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;

/**
 * Finds people by the one thing that names them on every instance: their email, kept in their {@code profile}.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class UserDirectory
{
    private static final String EMAIL_PROPERTY = "profile/email";

    private final UserManager users;

    /**
     * A directory reading the users a session can see.
     *
     * @param session the session to look users up through
     * @throws RepositoryException when the repository keeps no users
     */
    UserDirectory(final Session session) throws RepositoryException
    {
        if (!(session instanceof JackrabbitSession jackrabbit)) {
            throw new RepositoryException("The repository keeps no users");
        }
        this.users = jackrabbit.getUserManager();
    }

    /**
     * A person's email.
     *
     * @param id their user id
     * @return their email, or {@code null} for a user without one, a group, or nobody
     * @throws RepositoryException when the account cannot be read
     */
    String emailOf(final String id) throws RepositoryException
    {
        final Authorizable user = this.users.getAuthorizable(id);
        if (!(user instanceof User)) {
            return null;
        }
        final Value[] email = user.getProperty(EMAIL_PROPERTY);
        return email == null || email.length == 0 ? null : email[0].getString();
    }

    /**
     * Whether an id is a service user's, which stands for machinery rather than a person.
     *
     * @param id a user id
     * @return {@code true} for a service user
     * @throws RepositoryException when the account cannot be read
     */
    boolean isServiceUser(final String id) throws RepositoryException
    {
        return this.users.getAuthorizable(id) instanceof User user && user.isSystemUser();
    }

    /**
     * The user with an email.
     *
     * @param email an email
     * @return their user id, or {@code null} when nobody, or more than one user, has that email
     * @throws RepositoryException when the users cannot be searched
     */
    String idOf(final String email) throws RepositoryException
    {
        final Iterator<Authorizable> found =
            this.users.findAuthorizables(EMAIL_PROPERTY, email, UserManager.SEARCH_TYPE_USER);
        if (!found.hasNext()) {
            return null;
        }
        final Authorizable user = found.next();
        // Two accounts sharing an email cannot be told apart, so neither is assumed
        return found.hasNext() ? null : user.getID();
    }
}

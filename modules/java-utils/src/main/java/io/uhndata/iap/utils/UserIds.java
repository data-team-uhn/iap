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
package io.uhndata.iap.utils;

import java.security.Principal;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Who a session belongs to, as the canonical user id. Record and compare user ids through here.
 *
 * <p>Oak resolves a login case-insensitively, so {@code admin}, {@code Admin} and {@code ADMIN} sign in as one
 * user. {@link ResourceResolver#getUserID()} answers with the name as typed. The JCR session answers with the
 * canonical id of the user Oak resolved the login to, which is also what Oak writes in {@code jcr:createdBy}.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class UserIds
{
    private UserIds()
    {
    }

    /**
     * The canonical user id behind a resolver, from its JCR session. A resolver without one gives its own answer.
     *
     * @param resolver the resolver to identify
     * @return the user id to record and to compare against; {@code null} if the resolver has no JCR session and
     *     no user id of its own
     */
    @Nullable
    public static String canonical(@NotNull final ResourceResolver resolver)
    {
        final Session session = resolver.adaptTo(Session.class);
        return session == null ? resolver.getUserID() : session.getUserID();
    }

    /**
     * Everything a session acts as: its own user id, then the name of every principal it is bound to.
     *
     * <p>Read from the bound principals, not from group memberships in a {@code UserManager}. Under dynamic
     * membership, an identity provider's roles are principals with no Oak group behind them.</p>
     *
     * @param session the session to describe
     * @return the user id and the principal names, each once; empty only for a session that has no user id and is
     *     not a {@code JackrabbitSession}
     * @throws RepositoryException if the session cannot say what it is bound to
     */
    @NotNull
    public static List<String> principalsOf(@NotNull final Session session) throws RepositoryException
    {
        final Stream<String> bound = session instanceof JackrabbitSession
            ? ((JackrabbitSession) session).getBoundPrincipals().stream().map(Principal::getName)
            : Stream.empty();
        return Stream.concat(Stream.ofNullable(session.getUserID()), bound)
            .distinct()
            .collect(Collectors.toList());
    }
}

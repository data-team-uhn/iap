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
 * Who a session belongs to, said the one way that is stable.
 *
 * <p><strong>Why this exists.</strong> A login is resolved case-insensitively — signing in as {@code admin},
 * {@code Admin} or {@code ADMIN} all authenticate the one user — but
 * {@link ResourceResolver#getUserID() the resolver's} answer is the name as typed, while the repository's own
 * answer is the canonical authorizable id.</p>
 *
 * <p>The repository's answer is the one to use, and a JCR session gives it directly: Oak sets it from the
 * authorizable it resolved the login to, which is why everything Oak writes already carries the canonical form.
 * There is nothing to look up and no extra right needed.</p>
 *
 * <p>The proper fix belongs upstream — Sling's {@code ResourceResolver.getUserID()} is specified as "an
 * implementation detail defined by the underlying repository", so answering with the login name rather than the
 * repository's id contradicts its own contract. Until a fix lands somewhere IAP can depend on, every place that
 * records or compares a user id goes through here.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class UserIds
{
    private UserIds()
    {
        // Utility class, not to be instantiated
    }

    /**
     * The canonical user id behind a resolver: the repository's own, when it can be asked.
     *
     * <p>Falls back on the resolver's answer where there is no JCR session — a resolver over another provider
     * has no repository to be canonical about, and answering {@code null} would be worse than answering the
     * only name available.</p>
     *
     * @param resolver the session to identify
     * @return the user id to record and to compare against, or {@code null} if nothing knows one
     */
    @Nullable
    public static String canonical(@NotNull final ResourceResolver resolver)
    {
        final Session session = resolver.adaptTo(Session.class);
        return session == null ? resolver.getUserID() : session.getUserID();
    }

    /**
     * Everything a session acts as: its own user id, then every principal it is bound to.
     *
     * <p>What it is for: a property naming who may act — a workflow task's performers — holds principals rather
     * than user ids, so "is this mine" is not one comparison but "any of the things I act as".</p>
     *
     * <p>Read from the bound principals rather than from group memberships looked up through a
     * {@code UserManager}, because with dynamic membership an identity provider's roles arrive as principals with
     * no local group node behind them, so a membership lookup would report that a session belongs to nothing. The
     * user's own id comes first and is always present, so a session that is bound to nothing still answers with
     * itself rather than with nothing — a caller filtering on an empty list would widen its question to
     * everybody, which is the opposite of asking what is theirs.</p>
     *
     * @param session the session to describe
     * @return the principal names, the user's own id first; never empty
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

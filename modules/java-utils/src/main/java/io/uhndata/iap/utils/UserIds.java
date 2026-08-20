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

import javax.jcr.Session;

import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Who a session belongs to, said the one way that is stable.
 *
 * <p><strong>Why this exists.</strong> A login is resolved case-insensitively — signing in as {@code admin},
 * {@code Admin} or {@code ADMIN} all authenticate the one user — but
 * {@link ResourceResolver#getUserID() the resolver's} answer is the name as typed, while the repository's own
 * answer is the canonical authorizable id. Measured on a running instance: signed in as {@code Admin}, the
 * resolver reports {@code Admin} and the very same request writes {@code jcr:createdBy: admin}. So a user id
 * taken from the resolver is not an identity: store it and compare it later, and it stops matching as soon as
 * somebody types their name differently. That is not a listing that comes back empty — it is also
 * {@code @creator} refusing the person who raised the request, and an editor telling them it is read-only.</p>
 *
 * <p>The repository's answer is the one to use, and a JCR session gives it directly: Oak sets it from the
 * authorizable it resolved the login to, which is why everything Oak writes already carries the canonical form.
 * There is nothing to look up and no extra right needed.</p>
 *
 * <p>The proper fix belongs upstream — Sling's {@code ResourceResolver.getUserID()} is specified as "an
 * implementation detail defined by the underlying repository", so answering with the login name rather than the
 * repository's id contradicts its own contract. See {@code docs/upstream/sling-canonical-user-id.md} for the
 * proposed patch. Until that lands somewhere IAP can depend on, every place that records or compares a user id
 * goes through here.</p>
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
}

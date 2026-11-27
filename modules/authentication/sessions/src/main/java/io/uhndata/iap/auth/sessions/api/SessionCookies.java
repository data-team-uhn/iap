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
package io.uhndata.iap.auth.sessions.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Issues and checks the {@value #COOKIE_NAME} cookie, which bounds how long a browser stays signed in. A session ends
 * after a stretch without requests, and at the latest a fixed time after signing in, however often it is renewed.
 * Both lengths are configured. The cookie is signed with a key every instance sharing the repository uses.
 *
 * <p>The methods take and give strings, not servlet objects: authentication handlers hold requests of either servlet
 * API. Each method that changes the cookie answers with the value of the {@code Set-Cookie} header to send.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface SessionCookies
{
    /** The name of the session cookie. */
    String COOKIE_NAME = "iap.session";

    /** The kind of a session begun by signing in with a local account. */
    String FORM_KIND = "form";

    /**
     * The request header that marks a request as made in the background, such as a poll for notifications. Such a
     * request does not renew the session, so a page left open does not keep it alive.
     */
    String BACKGROUND_HEADER = "X-IAP-Background";

    /**
     * The key under which an authentication handler that verified a session puts it in its authentication info, so
     * that the request renews it.
     */
    String SESSION_ATTRIBUTE = "iap.session.verified";

    /**
     * Begins a session for a user who has just signed in.
     *
     * @param userId the user
     * @param kind how they signed in, such as {@link #FORM_KIND}
     * @param secure whether the request came over HTTPS, so that the cookie travels only over HTTPS too
     * @return the {@code Set-Cookie} header that stores the new session
     */
    @NotNull
    String begin(@NotNull String userId, @NotNull String kind, boolean secure);

    /**
     * The session a cookie holds, if it is one this platform signed and it has not ended.
     *
     * @param cookieValue the value of the {@value #COOKIE_NAME} cookie; {@code null} when the request carried none
     * @return the session; {@code null} if there is none, it is forged or malformed, or it has ended
     */
    @Nullable
    Session read(@Nullable String cookieValue);

    /**
     * Gives a session another stretch without requests, up to the latest it may last.
     *
     * @param session a session that has not ended
     * @param secure whether the request came over HTTPS
     * @return the {@code Set-Cookie} header that stores the renewed session
     */
    @NotNull
    String renew(@NotNull Session session, boolean secure);

    /**
     * Ends the session the browser holds.
     *
     * @param secure whether the request came over HTTPS
     * @return the {@code Set-Cookie} header that removes the cookie
     */
    @NotNull
    String end(boolean secure);
}

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
package io.uhndata.iap.auth.sessions.internal;

import java.util.Arrays;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.auth.core.spi.AuthenticationInfo;
import org.apache.sling.auth.core.spi.JakartaAuthenticationInfoPostProcessor;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.auth.sessions.api.Session;
import io.uhndata.iap.auth.sessions.api.SessionCookies;

/**
 * Holds every browser session to the configured limits, after the authentication handler has identified the user.
 *
 * <ul>
 * <li>Signing in with a local account's password begins a session.</li>
 * <li>A request carrying the local-account cookie needs a session too, for the same user. Without one, or once it has
 * ended, the request fails as a sign-in that failed, so the user is asked to sign in again.</li>
 * <li>A session an authentication handler verified itself, as the identity provider's does, comes with its
 * authentication info.</li>
 * </ul>
 *
 * <p>Each request renews its session, except a request made in the background. Other kinds of authentication, such as
 * HTTP Basic and the tokens instances use with each other, have no session and pass unchanged.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = JakartaAuthenticationInfoPostProcessor.class)
public class SessionLimitsPostProcessor implements JakartaAuthenticationInfoPostProcessor
{
    private static final String SET_COOKIE = "Set-Cookie";

    private final SessionCookies sessions;

    /**
     * Binds the sessions service.
     *
     * @param sessions issues and checks session cookies
     */
    @Activate
    public SessionLimitsPostProcessor(@Reference final SessionCookies sessions)
    {
        this.sessions = sessions;
    }

    @Override
    public void postProcess(final AuthenticationInfo info, final HttpServletRequest request,
        final HttpServletResponse response) throws LoginException
    {
        if (info.get(SessionCookies.SESSION_ATTRIBUTE) instanceof final Session verified) {
            this.renew(verified, request, response);
        } else if (HttpServletRequest.FORM_AUTH.equals(info.getAuthType())) {
            this.limitLocalAccount(info, request, response);
        }
    }

    private void limitLocalAccount(final AuthenticationInfo info, final HttpServletRequest request,
        final HttpServletResponse response) throws LoginException
    {
        if (info.getPassword() != null) {
            response.addHeader(SET_COOKIE,
                this.sessions.begin(info.getUser(), SessionCookies.FORM_KIND, request.isSecure()));
            return;
        }
        final Session session = this.sessions.read(sessionCookie(request));
        if (session == null || !SessionCookies.FORM_KIND.equals(session.kind())
            || !session.userId().equals(info.getUser())) {
            response.addHeader(SET_COOKIE, this.sessions.end(request.isSecure()));
            throw new LoginException("The session has ended");
        }
        this.renew(session, request, response);
    }

    private void renew(final Session session, final HttpServletRequest request, final HttpServletResponse response)
    {
        if (request.getHeader(SessionCookies.BACKGROUND_HEADER) == null) {
            response.addHeader(SET_COOKIE, this.sessions.renew(session, request.isSecure()));
        }
    }

    private static String sessionCookie(final HttpServletRequest request)
    {
        final Cookie[] cookies = request.getCookies();
        return cookies == null ? null : Arrays.stream(cookies)
            .filter(cookie -> SessionCookies.COOKIE_NAME.equals(cookie.getName()))
            .map(Cookie::getValue)
            .findFirst()
            .orElse(null);
    }
}

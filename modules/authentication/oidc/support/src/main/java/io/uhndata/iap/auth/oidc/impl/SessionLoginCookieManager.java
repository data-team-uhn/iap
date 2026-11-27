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
package io.uhndata.iap.auth.oidc.impl;

import java.util.Arrays;

import javax.jcr.Credentials;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.auth.core.spi.AuthenticationInfo;
import org.apache.sling.auth.oauth_client.spi.LoginCookieManager;
import org.apache.sling.auth.oauth_client.spi.OidcAuthCredentials;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import io.uhndata.iap.auth.sessions.api.Session;
import io.uhndata.iap.auth.sessions.api.SessionCookies;

/**
 * Keeps a sign-in through the identity provider in IAP's session cookie, in place of the OAuth client's own cookie,
 * which lasts a fixed time and is never renewed. The session limits then end it after a stretch without requests,
 * and at the latest a fixed time after signing in.
 *
 * <p>The cookie is the one local-account sessions use, so a session records the provider it came from, and only such
 * a session signs a user in here. Each request presents the same credentials the OAuth client's cookie manager would,
 * so the provider's users are provisioned and signed in as before.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = LoginCookieManager.class, property = "service.ranking:Integer=100")
@Designate(ocd = SessionLoginCookieManagerConfiguration.class)
public class SessionLoginCookieManager implements LoginCookieManager
{
    /** What the kind of a session begun through an identity provider starts with; the provider's name follows. */
    static final String KIND_PREFIX = "oidc:";

    /** The authentication info key Sling reads the repository credentials from. */
    private static final String CREDENTIALS_KEY = "user.jcr.credentials";

    /** The credentials attribute the OAuth client expects, empty since no token is kept in the cookie. */
    private static final String TOKEN_ATTRIBUTE = ".token";

    private final SessionCookies sessions;

    private final String idp;

    private final String kind;

    /**
     * Binds the sessions service and reads which provider this speaks for.
     *
     * @param sessions issues and checks session cookies
     * @param config the identity provider
     */
    @Activate
    public SessionLoginCookieManager(@Reference final SessionCookies sessions,
        final SessionLoginCookieManagerConfiguration config)
    {
        this.sessions = sessions;
        this.idp = config.idpName();
        this.kind = KIND_PREFIX + this.idp;
    }

    @Override
    public void setLoginCookie(final HttpServletRequest request, final HttpServletResponse response,
        final Credentials credentials)
    {
        final String userId = ((OidcAuthCredentials) credentials).getUserId();
        response.addHeader("Set-Cookie", this.sessions.begin(userId, this.kind, request.isSecure()));
    }

    @Override
    public AuthenticationInfo verifyLoginCookie(final HttpServletRequest request)
    {
        final Cookie cookie = this.getLoginCookie(request);
        final Session session = cookie == null ? null : this.sessions.read(cookie.getValue());
        if (session == null || !this.kind.equals(session.kind())) {
            return null;
        }
        final OidcAuthCredentials credentials = new OidcAuthCredentials(session.userId(), this.idp);
        credentials.setAttribute(TOKEN_ATTRIBUTE, "");
        final AuthenticationInfo info = new AuthenticationInfo(this.idp, session.userId());
        info.put(CREDENTIALS_KEY, credentials);
        info.put(SessionCookies.SESSION_ATTRIBUTE, session);
        return info;
    }

    @Override
    public Cookie getLoginCookie(final HttpServletRequest request)
    {
        final Cookie[] cookies = request.getCookies();
        return cookies == null ? null : Arrays.stream(cookies)
            .filter(cookie -> SessionCookies.COOKIE_NAME.equals(cookie.getName()))
            .findFirst()
            .orElse(null);
    }
}

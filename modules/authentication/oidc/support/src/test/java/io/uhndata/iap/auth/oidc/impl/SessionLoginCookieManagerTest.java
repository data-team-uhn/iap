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

import java.time.Instant;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.auth.core.spi.AuthenticationInfo;
import org.apache.sling.auth.oauth_client.spi.OidcAuthCredentials;
import org.junit.jupiter.api.Test;

import io.uhndata.iap.auth.sessions.api.Session;
import io.uhndata.iap.auth.sessions.api.SessionCookies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SessionLoginCookieManager}.
 *
 * @version $Id$
 */
class SessionLoginCookieManagerTest
{
    private static final Instant NOW = Instant.parse("2026-11-26T09:00:00Z");

    private static final Session OIDC_SESSION = new Session("carol", "oidc:keycloak", NOW, NOW.plusSeconds(1800));

    private final SessionCookies sessions = mock(SessionCookies.class);

    private final SessionLoginCookieManager manager = new SessionLoginCookieManager(this.sessions, keycloak());

    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @Test
    void beginsASessionOfTheProviderOnSignIn()
    {
        final HttpServletResponse response = mock(HttpServletResponse.class);
        when(this.request.isSecure()).thenReturn(true);
        when(this.sessions.begin("carol", "oidc:keycloak", true)).thenReturn("iap.session=begun");

        this.manager.setLoginCookie(this.request, response, new OidcAuthCredentials("carol", "keycloak"));

        verify(response).addHeader("Set-Cookie", "iap.session=begun");
    }

    @Test
    void signsInAsTheOAuthClientWouldAndHandsOverTheSession()
    {
        this.carrying("signed value");
        when(this.sessions.read("signed value")).thenReturn(OIDC_SESSION);

        final AuthenticationInfo info = this.manager.verifyLoginCookie(this.request);

        assertEquals("keycloak", info.getAuthType());
        assertEquals("carol", info.getUser());
        final OidcAuthCredentials credentials = (OidcAuthCredentials) info.get("user.jcr.credentials");
        assertEquals("carol", credentials.getUserId());
        assertEquals("keycloak", credentials.getIdp());
        assertEquals("", credentials.getAttribute(".token"));
        assertSame(OIDC_SESSION, info.get(SessionCookies.SESSION_ATTRIBUTE));
    }

    @Test
    void signsNobodyInWithoutASessionOfTheProvider()
    {
        assertNull(this.manager.verifyLoginCookie(this.request));

        this.carrying("ended value");
        assertNull(this.manager.verifyLoginCookie(this.request));

        // A local account's session is not a sign-in through the provider
        this.carrying("local value");
        when(this.sessions.read("local value")).thenReturn(new Session("alice", "form", NOW, NOW.plusSeconds(60)));
        assertNull(this.manager.verifyLoginCookie(this.request));
    }

    @Test
    void findsTheSessionCookieAmongOthers()
    {
        this.carrying("signed value");

        assertEquals("signed value", this.manager.getLoginCookie(this.request).getValue());
    }

    private void carrying(final String value)
    {
        when(this.request.getCookies())
            .thenReturn(new Cookie[] { new Cookie("other", "x"), new Cookie("iap.session", value) });
    }

    private static SessionLoginCookieManagerConfiguration keycloak()
    {
        final SessionLoginCookieManagerConfiguration config = mock(SessionLoginCookieManagerConfiguration.class);
        when(config.idpName()).thenReturn("keycloak");
        return config;
    }
}

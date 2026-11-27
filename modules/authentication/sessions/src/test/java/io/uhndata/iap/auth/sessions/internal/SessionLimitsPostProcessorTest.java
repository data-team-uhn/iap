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

import java.time.Instant;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.auth.core.spi.AuthenticationInfo;
import org.junit.jupiter.api.Test;

import io.uhndata.iap.auth.sessions.api.Session;
import io.uhndata.iap.auth.sessions.api.SessionCookies;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SessionLimitsPostProcessor}.
 *
 * @version $Id$
 */
class SessionLimitsPostProcessorTest
{
    private static final Instant NOW = Instant.parse("2026-11-26T09:00:00Z");

    private static final Session FORM_SESSION = new Session("alice", "form", NOW, NOW.plusSeconds(1800));

    private final SessionCookies sessions = mock(SessionCookies.class);

    private final SessionLimitsPostProcessor processor = new SessionLimitsPostProcessor(this.sessions);

    private final HttpServletRequest request = mock(HttpServletRequest.class);

    private final HttpServletResponse response = mock(HttpServletResponse.class);

    @Test
    void beginsASessionWhenALocalAccountSignsIn() throws LoginException
    {
        when(this.request.isSecure()).thenReturn(true);
        when(this.sessions.begin("alice", "form", true)).thenReturn("iap.session=begun");

        this.processor.postProcess(new AuthenticationInfo("FORM", "alice", "secret".toCharArray()), this.request,
            this.response);

        verify(this.response).addHeader("Set-Cookie", "iap.session=begun");
    }

    @Test
    void renewsTheSessionOfALocalAccount() throws LoginException
    {
        this.carrying("signed value");
        when(this.sessions.read("signed value")).thenReturn(FORM_SESSION);
        when(this.sessions.renew(FORM_SESSION, false)).thenReturn("iap.session=renewed");

        this.processor.postProcess(new AuthenticationInfo("FORM", "alice"), this.request, this.response);

        verify(this.response).addHeader("Set-Cookie", "iap.session=renewed");
    }

    @Test
    void leavesTheSessionAsItIsOnABackgroundRequest() throws LoginException
    {
        this.carrying("signed value");
        when(this.sessions.read("signed value")).thenReturn(FORM_SESSION);
        when(this.request.getHeader("X-IAP-Background")).thenReturn("1");

        this.processor.postProcess(new AuthenticationInfo("FORM", "alice"), this.request, this.response);

        verify(this.sessions, never()).renew(FORM_SESSION, false);
    }

    @Test
    void refusesALocalAccountWhoseSessionHasEnded()
    {
        this.carrying("ended value");
        when(this.sessions.end(false)).thenReturn("iap.session=");

        assertThrows(LoginException.class,
            () -> this.processor.postProcess(new AuthenticationInfo("FORM", "alice"), this.request, this.response));
        verify(this.response).addHeader("Set-Cookie", "iap.session=");
    }

    @Test
    void refusesALocalAccountWithNoSessionCookie()
    {
        when(this.request.getCookies()).thenReturn(new Cookie[] { new Cookie("other", "value") });

        assertThrows(LoginException.class,
            () -> this.processor.postProcess(new AuthenticationInfo("FORM", "alice"), this.request, this.response));
    }

    @Test
    void refusesALocalAccountWithNoCookiesAtAll()
    {
        assertThrows(LoginException.class,
            () -> this.processor.postProcess(new AuthenticationInfo("FORM", "alice"), this.request, this.response));
    }

    @Test
    void refusesAnotherUsersSession()
    {
        this.carrying("signed value");
        when(this.sessions.read("signed value")).thenReturn(FORM_SESSION);

        assertThrows(LoginException.class,
            () -> this.processor.postProcess(new AuthenticationInfo("FORM", "bob"), this.request, this.response));
    }

    @Test
    void refusesASessionBegunAnotherWay()
    {
        this.carrying("signed value");
        when(this.sessions.read("signed value"))
            .thenReturn(new Session("alice", "oidc:keycloak", NOW, NOW.plusSeconds(1800)));

        assertThrows(LoginException.class,
            () -> this.processor.postProcess(new AuthenticationInfo("FORM", "alice"), this.request, this.response));
    }

    @Test
    void renewsASessionAHandlerVerified() throws LoginException
    {
        final Session verified = new Session("carol", "oidc:keycloak", NOW, NOW.plusSeconds(1800));
        final AuthenticationInfo info = new AuthenticationInfo("keycloak", "carol");
        info.put(SessionCookies.SESSION_ATTRIBUTE, verified);
        when(this.sessions.renew(verified, false)).thenReturn("iap.session=renewed");

        this.processor.postProcess(info, this.request, this.response);

        verify(this.response).addHeader("Set-Cookie", "iap.session=renewed");
    }

    @Test
    void leavesOtherKindsOfAuthenticationAlone() throws LoginException
    {
        this.processor.postProcess(new AuthenticationInfo("BASIC", "admin", "admin".toCharArray()), this.request,
            this.response);

        verifyNoInteractions(this.sessions, this.response);
    }

    private void carrying(final String value)
    {
        when(this.request.getCookies())
            .thenReturn(new Cookie[] { new Cookie("other", "x"), new Cookie("iap.session", value) });
    }
}

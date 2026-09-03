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

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * Tests for {@link OidcLogoutAuthenticationHandler}: on an OIDC session it expires the configured
 * cookie and steers the post-logout redirect; otherwise it stays out of the way, and it never takes
 * part in authentication.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class OidcLogoutAuthenticationHandlerTest
{
    private static final String COOKIE_NAME = "iap.oidc.session";

    private static final String LOGOUT_PATH = "/oidc/logout";

    private static final String RESOURCE_ATTR = "resource";

    private OidcLogoutAuthenticationHandler handler;

    @BeforeEach
    void setUp()
    {
        this.handler = handlerWith(LOGOUT_PATH);
    }

    @Test
    void dropCredentialsOnOidcSessionExpiresCookieAndSteersRedirect()
    {
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        Mockito.when(request.getCookies()).thenReturn(oidcCookies());

        this.handler.dropCredentials(request, response);

        final ArgumentCaptor<Cookie> captor = ArgumentCaptor.forClass(Cookie.class);
        Mockito.verify(response).addCookie(captor.capture());
        final Cookie expired = captor.getValue();
        Assertions.assertEquals(COOKIE_NAME, expired.getName());
        Assertions.assertEquals("", expired.getValue());
        Assertions.assertEquals("/", expired.getPath());
        Assertions.assertEquals(0, expired.getMaxAge());
        Assertions.assertEquals(true, expired.isHttpOnly());
        // Http requests emit non-secure cookies
        Assertions.assertEquals(false, expired.getSecure());
        Mockito.verify(request).setAttribute(RESOURCE_ATTR, LOGOUT_PATH);
    }

    @Test
    void dropCredentialsIgnoresRequestWithoutTheSessionCookie()
    {
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        Mockito.when(request.getCookies()).thenReturn(new Cookie[] { new Cookie("other", "x") });

        this.handler.dropCredentials(request, response);

        Mockito.verifyNoInteractions(response);
        Mockito.verify(request, Mockito.never()).setAttribute(ArgumentMatchers.anyString(), ArgumentMatchers.any());
    }

    @Test
    void dropCredentialsIgnoresRequestWithNoCookies()
    {
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        Mockito.when(request.getCookies()).thenReturn(null);

        this.handler.dropCredentials(request, response);

        Mockito.verifyNoInteractions(response);
        Mockito.verify(request, Mockito.never()).setAttribute(ArgumentMatchers.anyString(), ArgumentMatchers.any());
    }

    @Test
    void dropCredentialsWithBlankPathExpiresCookieButDoesNotSteer()
    {
        final OidcLogoutAuthenticationHandler blankPath = handlerWith("");
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        Mockito.when(request.getCookies()).thenReturn(oidcCookies());

        blankPath.dropCredentials(request, response);

        Mockito.verify(response).addCookie(ArgumentMatchers.any());
        Mockito.verify(request, Mockito.never()).setAttribute(ArgumentMatchers.anyString(), ArgumentMatchers.any());
    }

    @Test
    void abstainsFromAuthentication()
    {
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        Assertions.assertNull(this.handler.extractCredentials(request, response));
        Assertions.assertFalse(this.handler.requestCredentials(request, response));
        Mockito.verifyNoInteractions(request, response);
    }

    private static OidcLogoutAuthenticationHandler handlerWith(final String postLogoutPath)
    {
        final OidcLogoutConfiguration config = Mockito.mock(OidcLogoutConfiguration.class);
        Mockito.when(config.cookieName()).thenReturn(COOKIE_NAME);
        Mockito.when(config.postLogoutPath()).thenReturn(postLogoutPath);
        final OidcLogoutAuthenticationHandler built = new OidcLogoutAuthenticationHandler();
        built.activate(config);
        return built;
    }

    private static Cookie[] oidcCookies()
    {
        return new Cookie[] { new Cookie(COOKIE_NAME, "token") };
    }
}

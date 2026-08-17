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
import org.mockito.Mockito;

/**
 * Tests for {@link OidcLogoutAuthenticationHandler}: it must expire the configured session cookie
 * when Sling drops credentials on logout, and otherwise stay out of authentication.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class OidcLogoutAuthenticationHandlerTest
{
    private static final String COOKIE_NAME = "iap.oidc.session";

    private OidcLogoutAuthenticationHandler handler;

    @BeforeEach
    void setUp()
    {
        final OidcLogoutConfiguration config = Mockito.mock(OidcLogoutConfiguration.class);
        Mockito.when(config.cookieName()).thenReturn(COOKIE_NAME);
        this.handler = new OidcLogoutAuthenticationHandler();
        this.handler.activate(config);
    }

    @Test
    void dropCredentialsExpiresTheConfiguredCookie()
    {
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        this.handler.dropCredentials(Mockito.mock(HttpServletRequest.class), response);

        final ArgumentCaptor<Cookie> captor = ArgumentCaptor.forClass(Cookie.class);
        Mockito.verify(response).addCookie(captor.capture());
        final Cookie cookie = captor.getValue();
        Assertions.assertEquals(COOKIE_NAME, cookie.getName());
        Assertions.assertEquals("", cookie.getValue());
        Assertions.assertEquals("/", cookie.getPath());
        Assertions.assertEquals(0, cookie.getMaxAge());
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
}

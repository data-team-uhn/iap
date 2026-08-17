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

import java.io.IOException;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Tests for {@link OidcEndSessionServlet}: it redirects to the provider's end-session endpoint,
 * carrying the client id and a URL-encoded post-logout redirect URI.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class OidcEndSessionServletTest
{
    private static final String END_SESSION = "https://kc.example/realms/iap/protocol/openid-connect/logout";

    private static final String CLIENT_ID = "iap-sling";

    private static final String POST_LOGOUT = "http://localhost:8080/login";

    private OidcEndSessionServlet servlet;

    @BeforeEach
    void setUp()
    {
        final OidcEndSessionConfiguration config = Mockito.mock(OidcEndSessionConfiguration.class);
        Mockito.when(config.endSessionEndpoint()).thenReturn(END_SESSION);
        Mockito.when(config.clientId()).thenReturn(CLIENT_ID);
        Mockito.when(config.postLogoutRedirectUri()).thenReturn(POST_LOGOUT);
        this.servlet = new OidcEndSessionServlet();
        this.servlet.activate(config);
    }

    @Test
    void redirectsToTheProviderEndSessionEndpointWithEncodedParameters() throws IOException
    {
        final SlingJakartaHttpServletRequest request = Mockito.mock(SlingJakartaHttpServletRequest.class);
        final SlingJakartaHttpServletResponse response = Mockito.mock(SlingJakartaHttpServletResponse.class);

        this.servlet.doGet(request, response);

        Mockito.verify(response).sendRedirect(END_SESSION
            + "?client_id=iap-sling"
            + "&post_logout_redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Flogin");
        Mockito.verifyNoInteractions(request);
    }
}

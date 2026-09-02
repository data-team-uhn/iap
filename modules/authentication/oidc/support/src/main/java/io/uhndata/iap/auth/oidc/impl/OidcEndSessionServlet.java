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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.Servlet;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.servlets.SlingJakartaSafeMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletPaths;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.metatype.annotations.Designate;

/**
 * Completes an OIDC logout by redirecting the browser to the provider's end-session endpoint.
 *
 * <p>
 * The fallback path. {@link OidcLogoutAuthenticationHandler} normally ends the provider's session
 * itself, server to server, and only steers Sling's post-logout redirect here when that could not be
 * done -- because it cannot issue the cross-host redirect itself (see that class). This servlet then
 * redirects the now-anonymous browser to the provider's {@code end_session_endpoint}, passing a
 * {@code client_id} and a registered {@code post_logout_redirect_uri}, so the provider ends its own
 * SSO session and returns the browser to the landing page. It is reachable without authentication
 * because the caller has already been logged out by the time it runs.
 * </p>
 *
 * <p>
 * Carrying the logout in the browser is weaker than the back-channel call: without an
 * {@code id_token_hint} the provider asks the user to confirm, and closing the tab at that point
 * leaves the SSO session alive. The ID token is never persisted by the OAuth client, so it cannot be
 * supplied here.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = Servlet.class,
    property = { "sling.auth.requirements=-/system/sling/oauth/logout" })
@SlingServletPaths("/system/sling/oauth/logout")
@Designate(ocd = OidcEndSessionConfiguration.class)
public class OidcEndSessionServlet extends SlingJakartaSafeMethodsServlet
{
    private static final long serialVersionUID = 1L;

    private String endSessionEndpoint;

    private String clientId;

    private String postLogoutRedirectUri;

    @Activate
    void activate(final OidcEndSessionConfiguration config)
    {
        this.endSessionEndpoint = config.endSessionEndpoint();
        this.clientId = config.clientId();
        this.postLogoutRedirectUri = config.postLogoutRedirectUri();
    }

    @Override
    protected void doGet(final SlingJakartaHttpServletRequest request, final SlingJakartaHttpServletResponse response)
        throws IOException
    {
        final String target = this.endSessionEndpoint
            + "?client_id=" + URLEncoder.encode(this.clientId, StandardCharsets.UTF_8)
            + "&post_logout_redirect_uri=" + URLEncoder.encode(this.postLogoutRedirectUri, StandardCharsets.UTF_8);
        response.sendRedirect(target);
    }
}

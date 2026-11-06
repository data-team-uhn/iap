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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Completes an OIDC logout by redirecting the browser to the provider's end-session endpoint.
 *
 * <p>
 * {@link OidcLogoutAuthenticationHandler} sends the browser here when it could not end the
 * provider's session itself. The redirect carries the {@code client_id} and the
 * {@code post_logout_redirect_uri}. The provider ends its SSO session and returns the browser to
 * that URI. The caller has already been logged out, so the servlet requires no authentication.
 * </p>
 *
 * <p>
 * Without an {@code id_token_hint} the provider asks the user to confirm the logout. A user who
 * closes the tab at that point keeps the SSO session. The OAuth client does not store the ID token,
 * so the hint cannot be sent.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = Servlet.class,
    property = { "sling.auth.requirements=-" + OidcEndSessionConfiguration.POST_LOGOUT_URI })
@SlingServletPaths(OidcEndSessionConfiguration.POST_LOGOUT_URI)
@Designate(ocd = OidcEndSessionConfiguration.class)
public class OidcEndSessionServlet extends SlingJakartaSafeMethodsServlet
{
    private static final Logger LOGGER = LoggerFactory.getLogger(OidcEndSessionServlet.class);

    private static final long serialVersionUID = 1L;

    private String endSessionEndpoint;

    private String clientId;

    private String postLogoutRedirectUri;

    private String cookieName;

    @Activate
    void activate(final OidcEndSessionConfiguration config)
    {
        this.endSessionEndpoint = config.endSessionEndpoint();
        this.clientId = config.clientId();
        this.postLogoutRedirectUri = config.postLogoutRedirectUri();
        this.cookieName = config.cookieName();
    }

    @Override
    protected void doGet(final SlingJakartaHttpServletRequest request, final SlingJakartaHttpServletResponse response)
        throws IOException
    {
        // Expired here too, for a request that did not come through Sling's logout
        response.addCookie(OidcLogoutAuthenticationHandler.expiredSessionCookie(this.cookieName, request.isSecure()));

        if (this.endSessionEndpoint == null || !(this.endSessionEndpoint.startsWith("http://")
            || this.endSessionEndpoint.startsWith("https://")))
        {
            LOGGER.error("The end session endpoint is not configured or is missing a protocol (http:// or https://)");
            response.sendError(500, "The end session endpoint is not configured properly or is missing a protocol "
                + "(http:// or https://); please let an administrator know");
            return;
        }

        final String target = this.endSessionEndpoint
            + "?client_id=" + URLEncoder.encode(this.clientId, StandardCharsets.UTF_8)
            + "&post_logout_redirect_uri=" + URLEncoder.encode(this.postLogoutRedirectUri, StandardCharsets.UTF_8);
        response.sendRedirect(target);
    }
}

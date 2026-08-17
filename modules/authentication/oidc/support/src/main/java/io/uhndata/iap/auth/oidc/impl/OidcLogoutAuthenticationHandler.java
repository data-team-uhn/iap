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

import org.apache.sling.auth.core.spi.AuthenticationInfo;
import org.apache.sling.auth.core.spi.JakartaAuthenticationHandler;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.metatype.annotations.Designate;

/**
 * Expires the OIDC session cookie on logout.
 *
 * <p>
 * The Apache Sling OAuth client's {@code OidcAuthenticationHandler} has an empty
 * {@code dropCredentials}, so navigating to {@code /system/sling/logout} tears down the Sling
 * session but leaves its {@code sling.oidcauth} cookie in place; the next request presents that
 * still-valid cookie and is silently signed back in. Sling invokes {@code dropCredentials} on every
 * authentication handler registered for the logout path, so this handler sits at {@code /} purely to
 * expire that cookie. It never takes part in authentication: {@code extractCredentials} abstains and
 * {@code requestCredentials} declines, leaving the login gate to the form and OIDC handlers.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = JakartaAuthenticationHandler.class,
    property = {
        JakartaAuthenticationHandler.PATH_PROPERTY + "=/",
        // Never wins credential requests; the low ranking keeps it out of the login handlers' way.
        "service.ranking:Integer=-10000"
    })
@Designate(ocd = OidcLogoutConfiguration.class)
public class OidcLogoutAuthenticationHandler implements JakartaAuthenticationHandler
{
    private String cookieName;

    @Activate
    void activate(final OidcLogoutConfiguration config)
    {
        this.cookieName = config.cookieName();
    }

    @Override
    public AuthenticationInfo extractCredentials(final HttpServletRequest request, final HttpServletResponse response)
    {
        return null;
    }

    @Override
    public boolean requestCredentials(final HttpServletRequest request, final HttpServletResponse response)
    {
        return false;
    }

    @Override
    public void dropCredentials(final HttpServletRequest request, final HttpServletResponse response)
    {
        // The cookie is HttpOnly, so only the server can clear it. Match the name and path it was set with
        // (Path=/) and expire it immediately.
        final Cookie expired = new Cookie(this.cookieName, "");
        expired.setPath("/");
        expired.setMaxAge(0);
        expired.setHttpOnly(true);
        response.addCookie(expired);
    }
}

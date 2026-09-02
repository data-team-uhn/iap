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

import javax.jcr.RepositoryException;
import javax.jcr.Value;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.jackrabbit.api.security.user.User;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.auth.core.AuthenticationSupport;
import org.apache.sling.auth.core.spi.AuthenticationInfo;
import org.apache.sling.auth.core.spi.JakartaAuthenticationHandler;
import org.apache.sling.commons.crypto.CryptoService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.httprequests.api.HttpRequests;
import io.uhndata.iap.httprequests.api.HttpResponse;

/**
 * Expires the OIDC session cookie on logout, and ends the provider's SSO session with it.
 *
 * <p>
 * The Apache Sling OAuth client's {@code OidcAuthenticationHandler} has an empty
 * {@code dropCredentials}, so navigating to {@code /system/sling/logout} tears down the Sling
 * session but leaves its {@code sling.oidcauth} cookie in place; the next request presents that
 * still-valid cookie and is silently signed back in. Sling invokes {@code dropCredentials} on every
 * authentication handler registered for the logout path, so this handler sits at {@code /} to expire
 * that cookie when the session was an OIDC one. It never takes part in authentication:
 * {@code extractCredentials} abstains and {@code requestCredentials} declines, leaving the login
 * gate to the form and OIDC handlers.
 * </p>
 *
 * <p>
 * Clearing the local session is only half of it: the provider's own SSO session outlives it, and
 * would sign the user straight back in on the next click. This ends that session back-channel,
 * presenting the user's stored refresh token to the provider directly. Only if that cannot be done
 * does it fall back to steering Sling's post-logout redirect to the {@link OidcEndSessionServlet},
 * which asks the browser to carry the logout instead -- a route the user can abandon at the
 * provider's confirmation screen, which is why it is the fallback rather than the default.
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
    private static final Logger LOGGER = LoggerFactory.getLogger(OidcLogoutAuthenticationHandler.class);

    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";

    @Reference
    private HttpRequests httpRequests;

    /**
     * Targeted deliberately: iap-email-notifications registers a second CryptoService, and binding that one would
     * decrypt with the wrong key. The token was encrypted by the OAuth client's own processor, which uses this one.
     */
    @Reference(target = "(names=iap-oauth)")
    private CryptoService cryptoService;

    private String cookieName;

    private String postLogoutPath;

    private String refreshTokenPath;

    private String backchannelLogoutEndpoint;

    private String clientId;

    private String clientSecret;

    @Activate
    void activate(final OidcLogoutConfiguration config)
    {
        this.cookieName = config.cookieName();
        this.postLogoutPath = config.postLogoutPath();
        this.refreshTokenPath = config.refreshTokenPath();
        this.backchannelLogoutEndpoint = config.backchannelLogoutEndpoint();
        this.clientId = config.clientId();
        this.clientSecret = config.clientSecret();
    }

    /**
     * Reads the user's refresh token from their home node and decrypts it.
     *
     * <p>
     * Must be called while the request still carries the user's identity, which is why this runs from
     * {@code dropCredentials} rather than from the end-session servlet: by the time that servlet is reached the
     * session is gone and the token is no longer readable.
     * </p>
     *
     * <p>
     * Never throws. Every reason the token might be unavailable -- a local login, an unsynced property, a
     * ciphertext this key cannot read -- only means the provider session cannot be ended this way, and logout must
     * still proceed.
     * </p>
     *
     * @param request the logout request, still carrying the authenticated resolver
     * @return the decrypted refresh token, or {@code null} if there is none to be had
     */
    private String readRefreshToken(final HttpServletRequest request)
    {
        final Object resolverAttribute = request.getAttribute(AuthenticationSupport.REQUEST_ATTRIBUTE_RESOLVER);
        if (!(resolverAttribute instanceof ResourceResolver))
        {
            return null;
        }
        // Resolves against the request's own identity, so the user's randomly-named home node never has to be
        // located: getProperty takes a path relative to it.
        final User user = ((ResourceResolver) resolverAttribute).adaptTo(User.class);
        if (user == null)
        {
            return null;
        }
        try
        {
            final Value[] values = user.getProperty(this.refreshTokenPath);
            // Absent entirely for a local account, or for an OIDC one synced before the property was mapped
            if (values == null || values.length == 0)
            {
                return null;
            }
            return this.cryptoService.decrypt(values[0].getString());
        } catch (final RepositoryException e) {
            LOGGER.warn("Could not read the stored refresh token", e);
        } catch (final RuntimeException e) {
            // decrypt() declares nothing, but throws when the ciphertext does not match this key -- which is what a
            // token encrypted under a previous IAP_OAUTH_ENCRYPTION_PASSWORD looks like
            LOGGER.warn("Could not decrypt the stored refresh token", e);
        }
        return null;
    }

    /**
     * Ends the provider's SSO session server-to-server, by presenting the user's refresh token to the provider's
     * logout endpoint. Unlike the browser redirect performed by {@link OidcEndSessionServlet}, this needs no
     * confirmation from the user and cannot be abandoned by closing the tab, so the provider session is reliably
     * gone by the time this returns.
     *
     * <p>
     * Never throws: a logout that cannot reach the provider must still expire the local session, so a failure is
     * reported by the return value and the caller decides whether to fall back to the redirect.
     * </p>
     *
     * @param refreshToken the user's decrypted refresh token
     * @return {@code true} if the provider accepted the request and the SSO session is ended
     */
    boolean endProviderSession(final String refreshToken)
    {
        if (this.backchannelLogoutEndpoint == null || this.backchannelLogoutEndpoint.isBlank()
            || refreshToken == null || refreshToken.isBlank())
        {
            return false;
        }
        final String body = "client_id=" + encode(this.clientId)
            + "&client_secret=" + encode(this.clientSecret)
            + "&refresh_token=" + encode(refreshToken);
        try
        {
            final HttpResponse response = this.httpRequests.post(this.backchannelLogoutEndpoint, body,
                FORM_CONTENT_TYPE);
            if (response.isSuccessful())
            {
                return true;
            }
            // The body carries the provider's reason (e.g. invalid_grant for an already-revoked or rotated token),
            // which is what makes this diagnosable; it never echoes the token back.
            LOGGER.warn("The provider refused the back-channel logout with status {}: {}",
                response.getStatusCode(), response.getBody());
        } catch (final IOException e) {
            // Deliberately not logging the address: it is configuration, and the exception does not quote it either
            LOGGER.warn("Could not reach the provider to end its session", e);
        }
        return false;
    }

    /** Form fields must be percent-encoded: a client secret in particular can carry {@code +} or {@code &}. */
    private static String encode(final String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
        // Act only on OIDC sessions: the presence of the session cookie is the sole signal that this
        // logout is for a user who signed in through the external provider. getCookies() is null when
        // the request carries no cookies at all.
        final Cookie[] cookies = request.getCookies();
        if (cookies == null)
        {
            return;
        }
        boolean oidcSession = false;
        for (final Cookie cookie : cookies)
        {
            if (this.cookieName.equals(cookie.getName()))
            {
                oidcSession = true;
                break;
            }
        }
        if (!oidcSession)
        {
            return;
        }

        // The cookie is HttpOnly, so only the server can clear it. Match the name and path it was set
        // with (Path=/) and expire it immediately.
        final Cookie expired = new Cookie(this.cookieName, "");
        expired.setPath("/");
        expired.setMaxAge(0);
        expired.setHttpOnly(true);
        expired.setAttribute("SameSite", "Lax");
        expired.setSecure(request.isSecure());
        response.addCookie(expired);

        // End the provider's session first: reading the token needs the identity this request still carries, which
        // is gone once the session is torn down. Best-effort -- a failure is reported, not thrown, and the redirect
        // below is what covers it.
        boolean endedSession = endProviderSession(readRefreshToken(request));

        // In cases where we were unable to do the backchannel logout, dropCredentials cannot issue the cross-host
        // redirect to the provider itself, so we make use of another Servlet located at postLogoutPath
        if (!endedSession && !this.postLogoutPath.isBlank())
        {
            request.setAttribute("resource", this.postLogoutPath);
        }
    }
}

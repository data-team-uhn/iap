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
import javax.jcr.Session;
import javax.jcr.Value;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.auth.core.AuthenticationSupport;
import org.apache.sling.auth.core.spi.AuthenticationInfo;
import org.apache.sling.auth.core.spi.JakartaAuthenticationHandler;
import org.apache.sling.commons.crypto.CryptoService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.httprequests.api.HttpRequests;
import io.uhndata.iap.httprequests.api.HttpResponse;

/**
 * Completes the logout of a user who signed in through the OIDC provider: expires the session cookie, and ends the
 * provider's SSO session.
 *
 * <p>
 * The Apache Sling OAuth client's {@code OidcAuthenticationHandler} has an empty
 * {@code dropCredentials}. A logout therefore leaves its {@code sling.oidcauth} cookie in place,
 * and the next request presenting that cookie is signed back in. Sling calls
 * {@code dropCredentials} on every authentication handler whose path covers the logout request.
 * This handler sits at {@code /} and expires the cookie. It takes no part in signing in:
 * {@code extractCredentials} returns nothing and {@code requestCredentials} declines.
 * </p>
 *
 * <p>
 * The provider's SSO session outlives the local one, and would sign the user straight back in. The
 * handler ends it back-channel, by posting the user's stored refresh token to the provider. When
 * that fails, it sends the browser to the {@link OidcEndSessionServlet} instead.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = JakartaAuthenticationHandler.class,
    property = {
        JakartaAuthenticationHandler.PATH_PROPERTY + "=/",
        // Ranked below the sign-in handlers at /, so Sling asks them first
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
     * The {@code iap-oauth} crypto service, the one the OAuth client encrypted the refresh token with. Another
     * {@code CryptoService} in the same instance would decrypt with the wrong key.
     *
     * <p>
     * Optional and dynamic: the cookie is still expired without it, and it binds when the service appears.
     * </p>
     */
    @Reference(cardinality = ReferenceCardinality.OPTIONAL,
        policy = ReferencePolicy.DYNAMIC,
        policyOption = ReferencePolicyOption.GREEDY,
        target = "(names=iap-oauth)")
    private volatile CryptoService cryptoService;

    private String cookieName;

    private String refreshTokenPath;

    private String backchannelLogoutEndpoint;

    private String clientId;

    private String clientSecret;

    @Activate
    void activate(final OidcLogoutConfiguration config)
    {
        this.cookieName = config.cookieName();
        this.refreshTokenPath = config.refreshTokenPath();
        this.backchannelLogoutEndpoint = config.backchannelLogoutEndpoint();
        this.clientId = config.clientId();
        this.clientSecret = config.clientSecret();
    }

    /**
     * Reads the user's refresh token from their home node and decrypts it. The request must still carry the user's
     * identity. The token is the copy synced at sign-in, and is stale if anything has refreshed it since.
     *
     * <p>
     * Never throws. A missing token only means the provider session cannot be ended this way.
     * </p>
     *
     * @param request the logout request, still carrying the authenticated resolver
     * @return the decrypted refresh token, or {@code null} if there is no crypto service, no user, no stored token,
     *     or the token cannot be read or decrypted
     */
    private String readRefreshToken(final HttpServletRequest request)
    {
        final CryptoService crypto = this.cryptoService;
        if (crypto == null)
        {
            LOGGER.warn("No iap-oauth CryptoService is available, so the stored refresh token cannot be decrypted;"
                + " ending the provider session by redirect instead");
            return null;
        }
        final User user = currentUser(request);
        if (user == null)
        {
            return null;
        }
        try
        {
            final Value[] values = user.getProperty(this.refreshTokenPath);
            // Absent for a local account, and for an OIDC one synced before the property was mapped
            if (values == null || values.length == 0)
            {
                return null;
            }
            return crypto.decrypt(values[0].getString());
        } catch (final RepositoryException e) {
            LOGGER.warn("Could not read the stored refresh token", e);
        } catch (final RuntimeException e) {
            // decrypt() declares nothing, but throws when the token was encrypted under another key, such as a
            // previous IAP_OAUTH_ENCRYPTION_PASSWORD
            LOGGER.warn("Could not decrypt the stored refresh token", e);
        }
        return null;
    }

    /**
     * Removes the stored refresh token, once the provider has accepted it and revoked it. Never throws: the logout
     * has already succeeded.
     *
     * @param request the logout request, still carrying the authenticated resolver
     */
    private void clearRefreshToken(final HttpServletRequest request)
    {
        final Object resolverAttribute = request.getAttribute(AuthenticationSupport.REQUEST_ATTRIBUTE_RESOLVER);
        if (!(resolverAttribute instanceof ResourceResolver))
        {
            return;
        }
        final ResourceResolver resolver = (ResourceResolver) resolverAttribute;
        final User user = resolver.adaptTo(User.class);
        final Session session = resolver.adaptTo(Session.class);
        if (user == null || session == null)
        {
            return;
        }
        try
        {
            if (user.removeProperty(this.refreshTokenPath))
            {
                session.save();
            }
        } catch (final RepositoryException e) {
            LOGGER.warn("Could not clear the stored refresh token; it has already been revoked at the provider", e);
        }
    }

    /**
     * The user this request is authenticated as.
     *
     * @param request the request to take the identity from
     * @return the user, or {@code null} when the request carries no resolver, or the resolver does not adapt to one
     */
    private static User currentUser(final HttpServletRequest request)
    {
        final Object resolverAttribute = request.getAttribute(AuthenticationSupport.REQUEST_ATTRIBUTE_RESOLVER);
        if (!(resolverAttribute instanceof ResourceResolver))
        {
            return null;
        }
        return ((ResourceResolver) resolverAttribute).adaptTo(User.class);
    }

    /**
     * Ends the provider's SSO session server to server, by posting the user's refresh token to the provider's logout
     * endpoint. The user is not asked to confirm. Never throws: a logout that cannot reach the provider must still
     * end the local session.
     *
     * @param refreshToken the user's decrypted refresh token, or {@code null} if there is none
     * @return {@code true} if the provider accepted the request, {@code false} if the endpoint, the client
     *     credentials or the token is blank, the provider refused, or it could not be reached
     */
    boolean endProviderSession(final String refreshToken)
    {
        if (StringUtils.isAnyBlank(this.backchannelLogoutEndpoint, refreshToken, this.clientId, this.clientSecret))
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
            LOGGER.warn("The provider refused the back-channel logout with status {}: {}",
                response.getStatusCode(), response.getBody());
        } catch (final IOException e) {
            LOGGER.warn("Could not reach the provider to end its session", e);
        }
        return false;
    }

    /** Percent-encodes a form field. A client secret can contain {@code +} or {@code &}. */
    private static String encode(final String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * A cookie that replaces the session cookie with an expired one.
     *
     * @param name the session cookie's name
     * @param secure whether the request arrived over HTTPS, since a Secure cookie cannot be replaced by one without
     * @return a cookie that expires immediately
     */
    static Cookie expiredSessionCookie(final String name, final boolean secure)
    {
        // The OAuth client sets the cookie with these attributes. Under another path the browser would keep the
        // original.
        final Cookie expired = new Cookie(name, "");
        expired.setPath("/");
        expired.setMaxAge(0);
        expired.setHttpOnly(true);
        expired.setAttribute("SameSite", "Lax");
        expired.setSecure(secure);
        return expired;
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
        // Only the session cookie says the user signed in through the provider. getCookies() is null when the
        // request carries no cookies.
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

        response.addCookie(expiredSessionCookie(this.cookieName, request.isSecure()));

        boolean endedSession = endProviderSession(readRefreshToken(request));
        if (endedSession)
        {
            clearRefreshToken(request);
        } else {
            // Sling redirects after logout to the path in this attribute, and replaces a full URL with /. The
            // servlet redirects the browser on to the provider.
            request.setAttribute("resource", OidcEndSessionConfiguration.POST_LOGOUT_URI);
        }
    }
}

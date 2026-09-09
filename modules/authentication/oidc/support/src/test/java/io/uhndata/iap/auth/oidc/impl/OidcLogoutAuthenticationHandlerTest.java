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
import java.lang.reflect.Field;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.jackrabbit.api.security.user.User;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.auth.core.AuthenticationSupport;
import org.apache.sling.commons.crypto.CryptoService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import io.uhndata.iap.httprequests.api.HttpRequests;
import io.uhndata.iap.httprequests.api.HttpResponse;

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

    private static final String TOKEN_PATH = "oauth/refresh_token";

    private static final String ENDPOINT = "http://keycloak:8080/realms/iap/protocol/openid-connect/logout";

    private static final String CIPHERTEXT = "encrypted-blob";

    private static final String TOKEN = "refresh-token";

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

    // --- Back-channel logout -------------------------------------------------------------------
    // The provider is mocked and never contacted for real, so what is asserted is the
    // request the handler would have made and what it does with each answer.

    @Test
    void backchannelLogoutPostsTheDecryptedTokenAndClearsItOnSuccess() throws Exception
    {
        final Fixture f = new Fixture().withStoredToken(CIPHERTEXT, TOKEN).answering(200);
        Mockito.when(f.user.removeProperty(TOKEN_PATH)).thenReturn(true);

        f.logout();

        final ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        Mockito.verify(f.httpRequests).post(ArgumentMatchers.eq(ENDPOINT), body.capture(),
            ArgumentMatchers.eq("application/x-www-form-urlencoded"));
        Assertions.assertEquals("client_id=iap-sling&client_secret=s3cr%2Bt&refresh_token=refresh-token",
            body.getValue());
        // Revoked at the provider, so the stored copy is removed and the removal persisted
        Mockito.verify(f.user).removeProperty(TOKEN_PATH);
        Mockito.verify(f.session).save();
        // Succeeded, so the abandonable redirect is not used
        Mockito.verify(f.request, Mockito.never()).setAttribute(RESOURCE_ATTR, LOGOUT_PATH);
    }

    @Test
    void secretAndTokenArePercentEncoded() throws Exception
    {
        // A client secret is generated, so it can contain characters that would otherwise split the
        // form body or be read as a space
        final Fixture f = new Fixture().withSecret("a+b&c d").withStoredToken(CIPHERTEXT, TOKEN).answering(200);

        f.logout();

        final ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        Mockito.verify(f.httpRequests).post(ArgumentMatchers.any(), body.capture(), ArgumentMatchers.any());
        Assertions.assertTrue(body.getValue().contains("client_secret=a%2Bb%26c+d"), body.getValue());
    }

    @Test
    void providerRefusalFallsBackToTheRedirectAndKeepsTheToken() throws Exception
    {
        // What an already-revoked or rotated token looks like coming back from Keycloak
        final Fixture f = new Fixture().withStoredToken(CIPHERTEXT, TOKEN)
            .answering(400, "{\"error\":\"invalid_grant\"}");

        f.logout();

        Mockito.verify(f.request).setAttribute(RESOURCE_ATTR, LOGOUT_PATH);
        Mockito.verify(f.user, Mockito.never()).removeProperty(ArgumentMatchers.any());
        Mockito.verify(f.session, Mockito.never()).save();
    }

    @Test
    void unreachableProviderFallsBackToTheRedirect() throws Exception
    {
        final Fixture f = new Fixture().withStoredToken(CIPHERTEXT, TOKEN);
        Mockito.when(f.httpRequests.post(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
            .thenThrow(new IOException("connection refused"));

        f.logout();

        Mockito.verify(f.request).setAttribute(RESOURCE_ATTR, LOGOUT_PATH);
    }

    @Test
    void blankEndpointSkipsTheCallEntirely() throws Exception
    {
        final Fixture f = new Fixture().withEndpoint("").withStoredToken(CIPHERTEXT, TOKEN);

        f.logout();

        Mockito.verifyNoInteractions(f.httpRequests);
        Mockito.verify(f.request).setAttribute(RESOURCE_ATTR, LOGOUT_PATH);
    }

    // --- Reading the stored token --------------------------------------------------------------
    // Each of these leaves nothing to present to the provider, so all of them must fall back rather
    // than fail the logout.

    @Test
    void missingTokenPropertyFallsBackWithoutCallingTheProvider() throws Exception
    {
        final Fixture f = new Fixture();
        Mockito.when(f.user.getProperty(TOKEN_PATH)).thenReturn(null);

        f.logout();

        Mockito.verifyNoInteractions(f.httpRequests);
        Mockito.verify(f.request).setAttribute(RESOURCE_ATTR, LOGOUT_PATH);
    }

    @Test
    void emptyTokenPropertyFallsBack() throws Exception
    {
        final Fixture f = new Fixture();
        Mockito.when(f.user.getProperty(TOKEN_PATH)).thenReturn(new Value[0]);

        f.logout();

        Mockito.verifyNoInteractions(f.httpRequests);
        Mockito.verify(f.request).setAttribute(RESOURCE_ATTR, LOGOUT_PATH);
    }

    @Test
    void unreadablePropertyFallsBack() throws Exception
    {
        final Fixture f = new Fixture();
        Mockito.when(f.user.getProperty(TOKEN_PATH)).thenThrow(new RepositoryException("no access"));

        f.logout();

        Mockito.verify(f.request).setAttribute(RESOURCE_ATTR, LOGOUT_PATH);
    }

    @Test
    void undecryptableTokenFallsBack() throws Exception
    {
        // What a token encrypted under a previous IAP_OAUTH_ENCRYPTION_PASSWORD looks like: decrypt
        // declares nothing, but throws
        final Fixture f = new Fixture().withStoredToken(CIPHERTEXT, null);
        Mockito.when(f.cryptoService.decrypt(CIPHERTEXT)).thenThrow(new IllegalStateException("wrong key"));

        f.logout();

        Mockito.verify(f.request).setAttribute(RESOURCE_ATTR, LOGOUT_PATH);
    }

    @Test
    void requestWithoutAResolverFallsBack()
    {
        // A local (non-OIDC) account reaches here whenever it carries the cookie name by coincidence
        final Fixture f = new Fixture().withoutResolver();

        f.logout();

        Mockito.verifyNoInteractions(f.httpRequests);
        Mockito.verify(f.request).setAttribute(RESOURCE_ATTR, LOGOUT_PATH);
    }

    // --- Clearing the stored token -------------------------------------------------------------

    @Test
    void nothingIsSavedWhenThereWasNoPropertyToRemove() throws Exception
    {
        final Fixture f = new Fixture().withStoredToken(CIPHERTEXT, TOKEN).answering(200);
        Mockito.when(f.user.removeProperty(TOKEN_PATH)).thenReturn(false);

        f.logout();

        Mockito.verify(f.session, Mockito.never()).save();
    }

    @Test
    void failureToClearDoesNotUndoASuccessfulLogout() throws Exception
    {
        final Fixture f = new Fixture().withStoredToken(CIPHERTEXT, TOKEN).answering(200);
        Mockito.when(f.user.removeProperty(TOKEN_PATH)).thenThrow(new RepositoryException("read-only"));

        f.logout();

        // The provider already accepted the logout, so it is not retried through the redirect
        Mockito.verify(f.request, Mockito.never()).setAttribute(RESOURCE_ATTR, LOGOUT_PATH);
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

    /**
     * A logout request for a signed-in OIDC user, with every collaborator mocked. Built in the state the
     * common case needs -- resolver, user and session present, back-channel configured -- so each test only
     * states the one thing it is about.
     */
    private static final class Fixture
    {
        private final OidcLogoutConfiguration config = Mockito.mock(OidcLogoutConfiguration.class);

        private final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);

        private final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

        private final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);

        private final HttpRequests httpRequests = Mockito.mock(HttpRequests.class);

        private final CryptoService cryptoService = Mockito.mock(CryptoService.class);

        private final User user = Mockito.mock(User.class);

        private final Session session = Mockito.mock(Session.class);

        private final OidcLogoutAuthenticationHandler handler = new OidcLogoutAuthenticationHandler();

        private String secret = "s3cr+t";

        private String endpoint = ENDPOINT;

        private boolean built;

        Fixture()
        {
            Mockito.when(this.request.getCookies()).thenReturn(oidcCookies());
            Mockito.when(this.request.getAttribute(AuthenticationSupport.REQUEST_ATTRIBUTE_RESOLVER))
                .thenReturn(this.resolver);
            Mockito.when(this.resolver.adaptTo(User.class)).thenReturn(this.user);
            Mockito.when(this.resolver.adaptTo(Session.class)).thenReturn(this.session);
        }

        Fixture withEndpoint(final String value)
        {
            this.endpoint = value;
            return this;
        }

        Fixture withSecret(final String value)
        {
            this.secret = value;
            return this;
        }

        Fixture withoutResolver()
        {
            Mockito.when(this.request.getAttribute(AuthenticationSupport.REQUEST_ATTRIBUTE_RESOLVER))
                .thenReturn(null);
            return this;
        }

        /** Stores {@code ciphertext} on the user node, decrypting to {@code plaintext} when one is given. */
        Fixture withStoredToken(final String ciphertext, final String plaintext) throws Exception
        {
            final Value value = Mockito.mock(Value.class);
            Mockito.when(value.getString()).thenReturn(ciphertext);
            Mockito.when(this.user.getProperty(TOKEN_PATH)).thenReturn(new Value[] { value });
            if (plaintext != null)
            {
                Mockito.when(this.cryptoService.decrypt(ciphertext)).thenReturn(plaintext);
            }
            return this;
        }

        /** Activates the handler if needed, then runs the logout under test. */
        void logout()
        {
            build();
            this.handler.dropCredentials(this.request, this.response);
        }

        Fixture answering(final int status) throws IOException
        {
            return answering(status, "");
        }

        Fixture answering(final int status, final String body) throws IOException
        {
            Mockito.when(this.httpRequests.post(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any())).thenReturn(new HttpResponse(status, body));
            return this;
        }

        /**
         * Activates the handler and injects its references. The bundle plugin only generates the DS metadata at
         * packaging time, so the service is instantiated directly and its references are injected by hand.
         */
        private void build()
        {
            if (this.built)
            {
                return;
            }
            Mockito.when(this.config.cookieName()).thenReturn(COOKIE_NAME);
            Mockito.when(this.config.postLogoutPath()).thenReturn(LOGOUT_PATH);
            Mockito.when(this.config.refreshTokenPath()).thenReturn(TOKEN_PATH);
            Mockito.when(this.config.backchannelLogoutEndpoint()).thenReturn(this.endpoint);
            Mockito.when(this.config.clientId()).thenReturn("iap-sling");
            Mockito.when(this.config.clientSecret()).thenReturn(this.secret);
            this.handler.activate(this.config);
            inject(this.handler, "httpRequests", this.httpRequests);
            inject(this.handler, "cryptoService", this.cryptoService);
            this.built = true;
        }

        private static void inject(final Object target, final String fieldName, final Object value)
        {
            try
            {
                final Field field = OidcLogoutAuthenticationHandler.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
            } catch (final ReflectiveOperationException e) {
                throw new IllegalStateException("Could not inject " + fieldName, e);
            }
        }
    }
}

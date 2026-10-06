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
package io.uhndata.iap.auth.token.jwt.impl;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.servlets.SlingJakartaAllMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletPaths;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.utils.UserIds;

/**
 * Trusting another IAP instance, which is what every token arriving from one depends on: {@code POST} a peer's
 * public key and the issuer it will claim, and this instance will verify that peer's tokens from then on.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletPaths(value = JwtPeerRegistrationServlet.ENDPOINT)
public class JwtPeerRegistrationServlet extends SlingJakartaAllMethodsServlet
{
    /** Where an administrator registers a peer. */
    public static final String ENDPOINT = "/system/jwt/peers";

    private static final long serialVersionUID = -4495178994673175377L;

    private static final Logger LOGGER = LoggerFactory.getLogger(JwtPeerRegistrationServlet.class);

    /** The subservice whose user may add a peer, but not read or rewrite our own signing key. */
    private static final String SUBSERVICE = "peers";

    private static final String ERROR = "error";

    /** The request field naming the {@code iss} claim the peer's tokens will carry. */
    private static final String ISSUER = "issuer";

    private final transient ResourceResolverFactory resolverFactory;

    /**
     * Activate the component.
     *
     * @param resolverFactory supplies the service user that writes the peer's key node
     */
    @Activate
    public JwtPeerRegistrationServlet(@Reference final ResourceResolverFactory resolverFactory)
    {
        this.resolverFactory = resolverFactory;
    }

    @Override
    protected void doPost(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        if (!"admin".equals(UserIds.canonical(request.getResourceResolver()))) {
            reply(response, HttpServletResponse.SC_FORBIDDEN, ERROR, "Only admin can register a peer");
            return;
        }

        final JsonObject body;
        try (JsonReader reader = Json.createReader(request.getReader())) {
            body = reader.readObject();
        } catch (final JsonException e) {
            reply(response, HttpServletResponse.SC_BAD_REQUEST, ERROR, "The request body must be a JSON object");
            return;
        }

        final String issuer = body.getString(ISSUER, null);
        final String submitted = body.getString("key", null);
        if (submitted == null || !JwtIssuers.isUsable(issuer)) {
            reply(response, HttpServletResponse.SC_BAD_REQUEST, ERROR,
                "`key` and a valid `issuer` are both required");
            return;
        }

        final JwtPeerKey key;
        try {
            key = JwtPeerKey.parse(submitted);
        } catch (final GeneralSecurityException e) {
            // The administrator is watching, and the key they pasted is theirs to see, so say what was wrong
            // with it rather than making them read the log
            reply(response, HttpServletResponse.SC_BAD_REQUEST, ERROR,
                "`key` is not an X.509-encoded RSA public key: " + e.getMessage());
            return;
        }

        register(response, key, issuer);
    }

    /**
     * Store the peer and answer with what it is now known as.
     *
     * @param response the response to write
     * @param key the validated key, which names its own node
     * @param issuer the validated {@code iss} claim the peer's tokens must carry
     * @throws IOException when the response cannot be written
     */
    private void register(final SlingJakartaHttpServletResponse response, final JwtPeerKey key,
        final String issuer) throws IOException
    {
        final String path = IapJwtTokenManagerImpl.KEY_ROOT + "/" + key.fingerprint();
        try (ResourceResolver resolver = this.resolverFactory
            .getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, SUBSERVICE))) {
            final Resource parent = resolver.getResource(IapJwtTokenManagerImpl.KEY_ROOT);
            if (parent == null) {
                throw new PersistenceException(IapJwtTokenManagerImpl.KEY_ROOT + " does not exist");
            }
            if (resolver.getResource(path) != null) {
                reply(response, HttpServletResponse.SC_CONFLICT, ERROR,
                    "This key is already registered, as " + path);
                return;
            }
            resolver.create(parent, key.fingerprint(), Map.of(
                "jcr:primaryType", "rep:Unstructured",
                IapJwtTokenManagerImpl.VERIFY_PROP, key.encoded(),
                IapJwtTokenManagerImpl.ISSUER_PROP, issuer));
            resolver.commit();
            LOGGER.info("Registered JWT peer {} as {}", issuer, key.fingerprint());
            reply(response, HttpServletResponse.SC_CREATED, "kid", key.fingerprint());
        } catch (final LoginException | PersistenceException e) {
            // Not recorded through error tracking: an administrator triggered this by hand and is reading the
            // answer, so there is nobody who would only find out hours later
            LOGGER.error("Could not register JWT peer {}: {}", issuer, e.getMessage(), e);
            reply(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ERROR, "The peer could not be stored");
        }
    }

    /**
     * Write a one-entry JSON body with the given status.
     *
     * @param response the response to write
     * @param status the HTTP status code
     * @param key the single JSON key
     * @param value its value
     * @throws IOException when the response cannot be written
     */
    private void reply(final SlingJakartaHttpServletResponse response, final int status, final String key,
        final String value) throws IOException
    {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(Json.createObjectBuilder().add(key, value).build().toString());
    }
}

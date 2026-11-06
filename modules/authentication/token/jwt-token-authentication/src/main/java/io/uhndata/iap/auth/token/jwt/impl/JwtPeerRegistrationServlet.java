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

import io.uhndata.iap.utils.JsonResponses;
import io.uhndata.iap.utils.UserIds;

/**
 * Registers another IAP instance as a trusted peer. The {@code admin} user posts the peer's public key and the
 * issuer its tokens carry. From then on, this instance accepts the tokens that peer signs.
 *
 * <p>Registration only adds. A key that is already registered is refused with {@code 409}.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletPaths(value = JwtPeerRegistrationServlet.ENDPOINT_PATH)
public class JwtPeerRegistrationServlet extends SlingJakartaAllMethodsServlet
{
    /** The path the {@code admin} user posts a peer to. */
    public static final String ENDPOINT_PATH = "/system/jwt/peers";

    private static final long serialVersionUID = -4495178994673175377L;

    private static final Logger LOGGER = LoggerFactory.getLogger(JwtPeerRegistrationServlet.class);

    /** The subservice mapped to the service user that may add a peer, but cannot touch this instance's keys. */
    private static final String SUBSERVICE_NAME = "peers";


    /** The request field naming the {@code iss} claim the peer's tokens will carry. */
    private static final String ISSUER_PARAMETER = "issuer";

    private final transient ResourceResolverFactory resolverFactory;

    /**
     * Activate the component.
     *
     * @param resolverFactory supplies the service user that writes the peers' key nodes
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
            JsonResponses.writeError(response, HttpServletResponse.SC_FORBIDDEN, "Only admin can register a peer");
            return;
        }

        final JsonObject body;
        try (JsonReader reader = Json.createReader(request.getReader())) {
            body = reader.readObject();
        } catch (final JsonException e) {
            JsonResponses.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                "The request body must be a JSON object");
            return;
        }

        final String issuer = body.getString(ISSUER_PARAMETER, null);
        final String submitted = body.getString("key", null);
        if (submitted == null || !JwtIssuers.isUsable(issuer)) {
            JsonResponses.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                "`key` and a valid `issuer` are both required");
            return;
        }

        final JwtPeerKey key;
        try {
            key = JwtPeerKey.parse(submitted);
        } catch (final GeneralSecurityException e) {
            // Only the admin user gets here, and the message describes the key they sent
            JsonResponses.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                "`key` is not an X.509-encoded RSA public key: " + e.getMessage());
            return;
        }

        register(response, key, issuer);
    }

    /**
     * Store the peer, and answer with its key ID or with why it could not be stored.
     *
     * @param response the HTTP response to write into
     * @param key the parsed key, whose fingerprint names the peer's node
     * @param issuer the validated {@code iss} claim the peer's tokens must carry
     * @throws IOException when the response cannot be written
     */
    private void register(final SlingJakartaHttpServletResponse response, final JwtPeerKey key,
        final String issuer) throws IOException
    {
        final String path = IapJwtTokenManagerImpl.KEY_ROOT + "/" + key.fingerprint();
        try (ResourceResolver resolver = this.resolverFactory
            .getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, SUBSERVICE_NAME))) {
            final Resource parent = resolver.getResource(IapJwtTokenManagerImpl.KEY_ROOT);
            if (parent == null) {
                throw new PersistenceException(IapJwtTokenManagerImpl.KEY_ROOT + " does not exist");
            }
            if (resolver.getResource(path) != null) {
                JsonResponses.writeError(response, HttpServletResponse.SC_CONFLICT,
                    "This key is already registered, as " + path);
                return;
            }

            resolver.create(parent, key.fingerprint(), Map.of(
                "jcr:primaryType", "rep:Unstructured",
                IapJwtTokenManagerImpl.VERIFY_PROP, key.encoded(),
                IapJwtTokenManagerImpl.ISSUER_PROP, issuer));
            resolver.commit();
            LOGGER.info("Registered JWT peer {} as {}", issuer, key.fingerprint());
            JsonResponses.write(response, HttpServletResponse.SC_CREATED, "registered",
                Json.createObjectBuilder().add("kid", key.fingerprint()));
        } catch (final LoginException | PersistenceException e) {
            // Not recorded through error tracking: the admin user who asked reads the answer
            LOGGER.error("Could not register JWT peer {}: {}", issuer, e.getMessage(), e);
            JsonResponses.writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "The peer could not be stored");
        }
    }
}

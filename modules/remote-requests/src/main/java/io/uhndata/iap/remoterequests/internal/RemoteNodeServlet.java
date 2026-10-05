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
package io.uhndata.iap.remoterequests.internal;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.json.Json;
import jakarta.json.JsonException;
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

import io.uhndata.iap.auth.token.IapToken;
import io.uhndata.iap.auth.token.TokenManager;
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;

/**
 * The first thing a trusted remote instance can actually ask this one to do, and for now the only way to exercise
 * the token authentication: {@code POST /system/remote} with a JWT in the {@code Authorization} header and a body
 * of {@code {"path": "<name>"}} creates an empty node at {@code /remote/<name>}.
 *
 * <p>What it creates is a placeholder; what matters is the shape of the gate in front of it. The peer gets no
 * session, so the token is the whole of the authentication: {@link TokenManager#parse} verifies the signature
 * against a key this instance knows, and the issuer and audience claims against who the key says the peer is.
 * The write that follows is done by a service user whose rights end at {@code /remote}, and the requested name
 * is confined to a single alphanumeric segment, so neither a slash nor a {@code ..} can carry the peer out of
 * that subtree. Authentication alone is not containment: a trusted peer with a valid token is still only
 * entitled to the one thing this endpoint does.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class }, property = { "sling.auth.requirements=-" + RemoteNodeServlet.ENDPOINT })
@SlingServletPaths(value = RemoteNodeServlet.ENDPOINT)
public class RemoteNodeServlet extends SlingJakartaAllMethodsServlet
{
    /** Where a remote peer posts. Unauthenticated as far as Sling is concerned; the token is the credential. */
    public static final String ENDPOINT = "/system/remote";

    /** The only subtree a remote peer may create anything in. */
    public static final String ROOT = "/remote";

    private static final long serialVersionUID = 7446831744251462695L;

    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteNodeServlet.class);

    /** The subservice whose user holds the write rights on {@link #ROOT}. */
    private static final String SUBSERVICE = "remote";

    /** The JSON key a refusal is reported under. */
    private static final String ERROR = "error";

    /**
     * What the body's {@code path} may be: one segment, ASCII letters and digits only. That excludes the slash
     * that would address another subtree, the period that {@code ..} is built from, and the colon a JCR namespace
     * prefix needs, so the name can be appended to {@link #ROOT} without further escaping. The length cap is
     * arbitrary, and only there so that a name cannot be used to bloat the repository.
     */
    private static final Pattern SAFE_NAME = Pattern.compile("\\p{Alnum}{1,64}");

    /** The scheme the token is expected under, tolerated rather than required. */
    private static final String BEARER = "Bearer ";

    private final transient TokenManager tokenManager;

    private final transient ResourceResolverFactory resolverFactory;

    /**
     * Activate the component.
     *
     * @param tokenManager verifies the token a peer presents
     * @param resolverFactory supplies the service user that does the write
     */
    @Activate
    public RemoteNodeServlet(@Reference final TokenManager tokenManager,
        @Reference final ResourceResolverFactory resolverFactory)
    {
        this.tokenManager = tokenManager;
        this.resolverFactory = resolverFactory;
    }

    @Override
    protected void doPost(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        final IapToken token = authenticate(request);
        if (token == null) {
            reply(response, HttpServletResponse.SC_UNAUTHORIZED, ERROR,
                "A valid token is required in the Authorization header");
            return;
        }

        final String name;
        try {
            name = requestedName(request);
        } catch (final JsonException e) {
            reply(response, HttpServletResponse.SC_BAD_REQUEST, ERROR,
                "The request body must be a JSON object");
            return;
        }
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            reply(response, HttpServletResponse.SC_BAD_REQUEST, ERROR,
                "`path` must be a single alphanumeric node name");
            return;
        }

        create(response, name, token);
    }

    /**
     * Identify the peer behind the request from the token it presented.
     *
     * @param request the incoming request
     * @return the verified token, or {@code null} if there was none, it did not verify, or it has expired
     */
    private IapToken authenticate(final SlingJakartaHttpServletRequest request)
    {
        final String header = request.getHeader("Authorization");
        if (header == null) {
            return null;
        }
        final String presented = header.regionMatches(true, 0, BEARER, 0, BEARER.length())
            ? header.substring(BEARER.length()) : header;
        final IapToken token = this.tokenManager.parse(presented.trim());
        // The parser rejects an expired token itself; checked again because this is the only gate before a write
        return token == null || token.isExpired(System.currentTimeMillis()) ? null : token;
    }

    /**
     * Read the requested node name out of the request body.
     *
     * @param request the incoming request
     * @return the value of the body's {@code path} entry, or {@code null} if it has none or it is not a string
     * @throws IOException when the body cannot be read
     * @throws JsonException when the body is not a JSON object
     */
    private String requestedName(final SlingJakartaHttpServletRequest request) throws IOException
    {
        try (JsonReader reader = Json.createReader(request.getReader())) {
            return reader.readObject().getString("path", null);
        }
    }

    /**
     * Create the node and answer with what became of it.
     *
     * @param response the response to write
     * @param name the validated node name
     * @param token the verified token of the peer asking, used to attribute a failure
     * @throws IOException when the response cannot be written
     */
    private void create(final SlingJakartaHttpServletResponse response, final String name, final IapToken token)
        throws IOException
    {
        final String path = ROOT + "/" + name;
        try (ResourceResolver resolver = this.resolverFactory
            .getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, SUBSERVICE))) {
            final Resource parent = resolver.getResource(ROOT);
            if (parent == null) {
                throw new PersistenceException(ROOT + " does not exist");
            }
            if (resolver.getResource(path) != null) {
                reply(response, HttpServletResponse.SC_CONFLICT, ERROR, path + " already exists");
                return;
            }
            resolver.create(parent, name, Map.of("jcr:primaryType", "nt:unstructured"));
            resolver.commit();
            response.setHeader("Location", path);
            reply(response, HttpServletResponse.SC_CREATED, "path", path);
        } catch (final LoginException | PersistenceException e) {
            // A missing service user or an unwritable /remote: the deployer's to fix, and nobody would otherwise
            // find out, since all the peer sees is a 500 it will retry
            LOGGER.error("Could not create {} for {}: {}", path, token.getUserId(), e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(RemoteNodeServlet.class, "create")
                .about(path)
                .actingFor(token.getUserId()));
            reply(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ERROR, "The node could not be created");
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

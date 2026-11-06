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
import io.uhndata.iap.utils.JsonResponses;

/**
 * Lets a trusted remote instance create an empty node in this one. The peer sends {@code POST /system/remote} with a
 * JWT in the {@code Authorization} header and the body {@code {"path": "<name>"}}, and the node appears at
 * {@code /remote/<name>}.
 *
 * <p>The name must be one segment of ASCII letters and digits, so it cannot address anything outside
 * {@code /remote}. A service user whose rights end at {@code /remote} does the write.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class }, property = { "sling.auth.requirements=-" + RemoteNodeServlet.ENDPOINT_PATH })
@SlingServletPaths(value = RemoteNodeServlet.ENDPOINT_PATH)
public class RemoteNodeServlet extends SlingJakartaAllMethodsServlet
{
    /** The path a remote peer posts to. Sling requires no session here: the token is the credential. */
    public static final String ENDPOINT_PATH = "/system/remote";

    /** The only subtree a remote peer may create anything in. */
    public static final String ROOT_PATH = "/remote";

    private static final long serialVersionUID = 7446831744251462695L;

    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteNodeServlet.class);

    /** The subservice mapped to the service user that may write under {@link #ROOT_PATH}. */
    private static final String SUBSERVICE_NAME = "remote";


    /** A valid {@code path} in the body: one segment of 1 to 64 ASCII letters and digits. */
    private static final Pattern SAFE_NAME = Pattern.compile("\\p{Alnum}{1,64}");

    /** The optional scheme before the token in the {@code Authorization} header, matched in any letter case. */
    private static final String BEARER_PREFIX = "Bearer ";

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
            JsonResponses.writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                "A valid token is required in the Authorization header");
            return;
        }

        final String name;
        try {
            name = requestedName(request);
        } catch (final JsonException e) {
            JsonResponses.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                "The request body must be a JSON object");
            return;
        }
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            JsonResponses.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
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
        final String presented = header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())
            ? header.substring(BEARER_PREFIX.length()) : header;
        final IapToken token = this.tokenManager.parse(presented.trim());
        // TokenManager does not promise that parse refuses an expired token
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
     * Create the node, and answer with its path or with why it could not be created.
     *
     * @param response the HTTP response to write into
     * @param name the validated node name
     * @param token the verified token of the peer asking; a failure is logged with its user ID
     * @throws IOException when the response cannot be written
     */
    private void create(final SlingJakartaHttpServletResponse response, final String name, final IapToken token)
        throws IOException
    {
        final String path = ROOT_PATH + "/" + name;
        try (ResourceResolver resolver = this.resolverFactory
            .getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, SUBSERVICE_NAME))) {
            final Resource parent = resolver.getResource(ROOT_PATH);
            if (parent == null) {
                throw new PersistenceException(ROOT_PATH + " does not exist");
            }
            if (resolver.getResource(path) != null) {
                JsonResponses.writeError(response, HttpServletResponse.SC_CONFLICT, path + " already exists");
                return;
            }

            resolver.create(parent, name, Map.of("jcr:primaryType", "nt:unstructured"));
            resolver.commit();
            response.setHeader("Location", path);
            JsonResponses.write(response, HttpServletResponse.SC_CREATED, "created",
                Json.createObjectBuilder().add("path", path));
        } catch (final LoginException | PersistenceException e) {
            LOGGER.error("Could not create {} for {}: {}", path, token.getUserId(), e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(RemoteNodeServlet.class, "create")
                .about(path)
                .actingFor(token.getUserId()));
            JsonResponses.writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "The node could not be created");
        }
    }
}

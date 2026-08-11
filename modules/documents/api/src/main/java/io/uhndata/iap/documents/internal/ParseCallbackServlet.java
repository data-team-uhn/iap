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
package io.uhndata.iap.documents.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.servlets.SlingJakartaAllMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletPaths;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Receives parse outcomes from the Docling daemon, at {@code /system/documents/parseCallback}. The daemon POSTs the
 * body it promised when {@link ParseJobConsumer} dispatched the job — {@code {"job_id", "ok": true, "markdown_path",
 * "chunked", "chunks_dir", ...}}, or {@code {"job_id", "ok": false, "error"}} — and this endpoint records the outcome
 * on the job node, completing the lifecycle the dispatch left at {@code active}.
 *
 * <p>
 * The endpoint is outside Sling authentication (the daemon has no user session), so it authenticates callers itself:
 * the daemon must present the shared JWT as a bearer token, the same value both sides read from the
 * {@code IAP_DOCLING_CALLBACK_JWT} environment variable (an explicit {@code callbackToken} OSGi property overrides
 * it on this side). The token is compared as an opaque secret in constant time; its claims are not inspected. With
 * no token configured anywhere, every delivery is refused — fail closed, since an unauthenticated endpoint would let
 * anyone "complete" jobs with fabricated outputs.
 * </p>
 *
 * <p>
 * A repeated delivery for the same job simply overwrites the same outcome, so the daemon's retries are harmless.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class },
    property = { "sling.auth.requirements=-" + ParseJob.CALLBACK_PATH })
@SlingServletPaths(value = ParseJob.CALLBACK_PATH)
public class ParseCallbackServlet extends SlingJakartaAllMethodsServlet
{
    private static final long serialVersionUID = -6421752502964926636L;

    private static final Logger LOGGER = LoggerFactory.getLogger(ParseCallbackServlet.class);

    @Reference
    private transient ResourceResolverFactory resolverFactory;

    /** The expected {@code Authorization} header, or {@code null} when no token is configured. */
    private transient byte[] expectedAuthorization;

    /**
     * Read the shared callback token: the {@link ParseJob#TOKEN_PROPERTY} OSGi property when set, the
     * {@link ParseJob#TOKEN_VARIABLE} environment variable otherwise.
     *
     * @param configuration the component configuration
     */
    @Activate
    @Modified
    protected void activate(final Map<String, Object> configuration)
    {
        final String token = CallbackToken.resolve(configuration, environment(ParseJob.TOKEN_VARIABLE));
        if (token.isEmpty()) {
            this.expectedAuthorization = null;
            LOGGER.warn("No callback token is configured ({} or the {} environment variable);"
                + " parse callbacks will be refused", ParseJob.TOKEN_PROPERTY, ParseJob.TOKEN_VARIABLE);
        } else {
            this.expectedAuthorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    protected void doPost(final SlingJakartaHttpServletRequest request, final SlingJakartaHttpServletResponse response)
        throws IOException
    {
        if (this.expectedAuthorization == null) {
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "Callback authentication is not configured");
            return;
        }
        if (!isAuthorized(request.getHeader("Authorization"))) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid callback token");
            return;
        }
        final JsonObject outcome;
        try (JsonReader reader = Json.createReader(request.getReader())) {
            outcome = reader.readObject();
        } catch (final JsonException | ClassCastException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "The request body is not a JSON object");
            return;
        }
        final String jobId = outcome.getString(ParseJob.JSON_JOB_ID, null);
        if (jobId == null || !isJobId(jobId)) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "job_id must be a UUID");
            return;
        }
        final String markdown = outcome.getString("markdown_path", null);
        if (outcome.getBoolean("ok", false) && markdown == null) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "A successful outcome needs a markdown_path");
            return;
        }
        record(jobId, outcome, markdown, response);
    }

    /**
     * Record a validated outcome on its job node.
     *
     * @param jobId the identifier of the finished job
     * @param outcome the daemon's callback body
     * @param markdown the produced Markdown path, already checked to be present on a success
     * @param response where the result of the recording is written
     * @throws IOException if the response cannot be written
     */
    private void record(final String jobId, final JsonObject outcome, final String markdown,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        try (ResourceResolver resolver = openServiceResolver()) {
            final Resource jobNode = resolver.getResource(ParseJob.nodePath(jobId));
            final ModifiableValueMap properties = jobNode == null ? null : jobNode.adaptTo(ModifiableValueMap.class);
            if (properties == null || !jobId.equals(properties.get(ParseJob.PN_JOB_ID, String.class))) {
                writeError(response, HttpServletResponse.SC_NOT_FOUND, "No such job: " + jobId);
                return;
            }
            final String status;
            if (outcome.getBoolean("ok", false)) {
                status = ParseJob.STATUS_COMPLETED;
                final List<String> outputs = new ArrayList<>();
                outputs.add(markdown);
                final String chunks = outcome.getString("chunks_dir", null);
                if (chunks != null) {
                    outputs.add(chunks);
                }
                properties.put(ParseJob.PN_OUTPUTS, outputs.toArray(new String[0]));
            } else {
                status = ParseJob.STATUS_FAILED;
                properties.put(ParseJob.PN_ERROR,
                    outcome.getString(ParseJob.PN_ERROR, "The daemon reported a failure without details"));
            }
            properties.put(ParseJob.PN_STATUS, status);
            properties.put(ParseJob.PN_FINISHED, Calendar.getInstance());
            resolver.commit();
            writeJson(response, HttpServletResponse.SC_OK, Json.createObjectBuilder()
                .add(ParseJob.JSON_JOB_ID, jobId)
                .add(ParseJob.PN_STATUS, status)
                .build());
        } catch (final LoginException e) {
            LOGGER.error("Cannot access the parse jobs storage: {}", e.getMessage(), e);
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "The parse jobs storage is not accessible");
        } catch (final PersistenceException e) {
            LOGGER.error("Cannot record the outcome of parse job {}: {}", jobId, e.getMessage(), e);
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "The outcome could not be recorded");
        }
    }

    /**
     * Check a presented {@code Authorization} header against the expected bearer token, in constant time so the
     * comparison leaks nothing about how much of the token matched.
     *
     * @param authorization the presented header, may be {@code null} when not sent
     * @return {@code true} if the header carries exactly the expected token
     */
    private boolean isAuthorized(final String authorization)
    {
        return authorization != null
            && MessageDigest.isEqual(authorization.getBytes(StandardCharsets.UTF_8), this.expectedAuthorization);
    }

    /**
     * Read an environment variable. Overridable so that tests can supply one without controlling the real
     * environment.
     *
     * @param name the variable to read
     * @return the value, or {@code null} when not set
     */
    protected String environment(final String name)
    {
        return System.getenv(name);
    }

    /**
     * Check that a caller-supplied job identifier is one {@link ParseServlet} could have issued.
     *
     * @param jobId the identifier to check
     * @return {@code true} if the identifier is a well-formed UUID
     */
    private static boolean isJobId(final String jobId)
    {
        try {
            UUID.fromString(jobId);
            return true;
        } catch (final IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Open a new session as the parse jobs service user.
     *
     * @return a service resource resolver, closed by the caller
     * @throws LoginException if the service user is not available
     */
    private ResourceResolver openServiceResolver() throws LoginException
    {
        return this.resolverFactory
            .getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, ParseJob.SUBSERVICE));
    }

    private static void writeJson(final SlingJakartaHttpServletResponse response, final int status,
        final JsonObject body) throws IOException
    {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.getWriter().print(body.toString());
    }

    private static void writeError(final SlingJakartaHttpServletResponse response, final int status,
        final String message) throws IOException
    {
        writeJson(response, status, Json.createObjectBuilder().add(ParseJob.PN_ERROR, message).build());
    }
}

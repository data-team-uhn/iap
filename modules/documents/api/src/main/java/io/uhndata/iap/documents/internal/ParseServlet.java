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
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
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
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingJakartaAllMethodsServlet;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.JobManager;
import org.apache.sling.servlets.annotations.SlingServletPaths;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stub endpoint for parsing a document through the Docling daemon, at {@code /system/documents/parse}. Each request
 * becomes a Java-owned job: a node under {@code /var/documents/jobs} recording the lifecycle, and a Sling job that
 * performs the actual daemon call in the background, so the response comes back immediately and the caller polls.
 *
 * <p>
 * {@code POST /system/documents/parse?path=/shared-docs/dir/file.pdf&chunk=true} queues a parse of the given file.
 * The {@code path} is the document's location as the daemon sees it, on the volume shared with it; {@code chunk}
 * (optional, {@code true} by default) also splits the resulting Markdown into a chunk tree. The answer is
 * {@code {"job_id": "<uuid>", "status": "queued"}}.
 * </p>
 *
 * <p>
 * {@code GET /system/documents/parse?job_id=<uuid>} polls the job: {@code {"job_id", "status"}}, plus
 * {@code "outputs"} (the produced files) once completed, or {@code "error"} once failed.
 * </p>
 *
 * <p>
 * Job nodes are read and written with a service user, so callers only need to be authenticated, not authorized on
 * {@code /var}; finer authorization (who may parse what, who may see which job) is left for the real integration.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletPaths(value = ParseServlet.PATH)
public class ParseServlet extends SlingJakartaAllMethodsServlet
{
    /** The path this endpoint is served at. */
    static final String PATH = "/system/documents/parse";

    private static final long serialVersionUID = 2946115948960530740L;

    private static final Logger LOGGER = LoggerFactory.getLogger(ParseServlet.class);

    /** The {@code chunk} values meaning "do not chunk", mirroring how the daemon reads its own parameter. */
    private static final Set<String> FALSE_WORDS = Set.of("false", "0", "no");

    /** The name of the job identifier, as exchanged with the caller. */
    private static final String JOB_ID_KEY = "job_id";

    /** What the caller is told when the service session cannot be opened. */
    private static final String INACCESSIBLE = "The parse jobs storage is not accessible";

    /** What is logged when the service session cannot be opened. */
    private static final String LOG_INACCESSIBLE = "Cannot access the parse jobs storage: {}";

    @Reference
    private transient ResourceResolverFactory resolverFactory;

    @Reference
    private transient JobManager jobManager;

    @Override
    protected void doPost(final SlingJakartaHttpServletRequest request, final SlingJakartaHttpServletResponse response)
        throws IOException
    {
        final String path = request.getParameter(ParseJob.PN_PATH);
        if (path == null || path.isBlank()) {
            JsonResponse.error(response, HttpServletResponse.SC_BAD_REQUEST, "path parameter is required");
            return;
        }
        final boolean chunk = isChunkRequested(request.getParameter(ParseJob.PN_CHUNK));
        final String jobId = UUID.randomUUID().toString();
        try (ResourceResolver resolver = ParseJob.openResolver(this.resolverFactory)) {
            final Resource jobsRoot = resolver.getResource(ParseJob.JOBS_PATH);
            if (jobsRoot == null) {
                JsonResponse.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "The parse jobs storage is not initialized");
                return;
            }
            final Map<String, Object> properties = new HashMap<>();
            properties.put(ParseJob.PN_JOB_ID, jobId);
            properties.put(ParseJob.PN_STATUS, ParseJob.STATUS_QUEUED);
            properties.put(ParseJob.PN_PATH, path);
            properties.put(ParseJob.PN_CHUNK, chunk);
            properties.put(ParseJob.PN_CREATED, Calendar.getInstance());
            final Resource jobNode = resolver.create(jobsRoot, jobId, properties);
            // The node must be visible to the consumer before the job is queued
            resolver.commit();

            final Job job = this.jobManager.addJob(ParseJob.TOPIC, Map.of(ParseJob.PN_JOB_ID, jobId));
            if (job == null) {
                markUnqueueable(resolver, jobNode);
                JsonResponse.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "The parse job could not be queued");
                return;
            }

            // Built from the constant servlet path, never from the request, so nothing
            // attacker-controlled can steer where this points (CodeQL: unvalidated-url-redirection)
            response.setHeader("Location", PATH + "?" + JOB_ID_KEY + "=" + jobId);
            JsonResponse.write(response, HttpServletResponse.SC_ACCEPTED, Json.createObjectBuilder()
                .add(JOB_ID_KEY, jobId)
                .add(ParseJob.PN_STATUS, ParseJob.STATUS_QUEUED)
                .build());
        } catch (final LoginException e) {
            LOGGER.error(LOG_INACCESSIBLE, e.getMessage(), e);
            JsonResponse.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INACCESSIBLE);
        } catch (final PersistenceException e) {
            LOGGER.error("Cannot record parse job {}: {}", jobId, e.getMessage(), e);
            JsonResponse.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "The parse job could not be recorded");
        }
    }

    @Override
    protected void doGet(final SlingJakartaHttpServletRequest request, final SlingJakartaHttpServletResponse response)
        throws IOException
    {
        final String jobId = request.getParameter(JOB_ID_KEY);
        if (jobId == null || jobId.isBlank()) {
            JsonResponse.error(response, HttpServletResponse.SC_BAD_REQUEST, "job_id parameter is required");
            return;
        }
        if (!ParseJob.isJobId(jobId)) {
            JsonResponse.error(response, HttpServletResponse.SC_BAD_REQUEST, "job_id must be a UUID");
            return;
        }
        try (ResourceResolver resolver = ParseJob.openResolver(this.resolverFactory)) {
            final Resource jobNode = resolver.getResource(ParseJob.nodePath(jobId));
            final ValueMap properties = jobNode == null ? null : jobNode.getValueMap();
            if (properties == null || !jobId.equals(properties.get(ParseJob.PN_JOB_ID, String.class))) {
                JsonResponse.error(response, HttpServletResponse.SC_NOT_FOUND, "No such job: " + jobId);
                return;
            }
            JsonResponse.write(response, HttpServletResponse.SC_OK, toJson(jobId, properties));
        } catch (final LoginException e) {
            LOGGER.error(LOG_INACCESSIBLE, e.getMessage(), e);
            JsonResponse.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INACCESSIBLE);
        }
    }

    /**
     * Render the pollable state of a job: its identifier and status, plus the outputs once completed, or the error
     * once failed.
     *
     * @param jobId the identifier of the job
     * @param properties the properties of the job node
     * @return the JSON answering a poll
     */
    private static JsonObject toJson(final String jobId, final ValueMap properties)
    {
        final JsonObjectBuilder json = Json.createObjectBuilder()
            .add(JOB_ID_KEY, jobId)
            .add(ParseJob.PN_STATUS, properties.get(ParseJob.PN_STATUS, ParseJob.STATUS_QUEUED));
        final String[] outputs = properties.get(ParseJob.PN_OUTPUTS, String[].class);
        if (outputs != null) {
            final JsonArrayBuilder list = Json.createArrayBuilder();
            for (final String output : outputs) {
                list.add(output);
            }
            json.add(ParseJob.PN_OUTPUTS, list);
        }
        final String error = properties.get(ParseJob.PN_ERROR, String.class);
        if (error != null) {
            json.add(ParseJob.PN_ERROR, error);
        }
        return json.build();
    }

    /**
     * Interpret the {@code chunk} parameter the same way the daemon does: chunking is on unless explicitly refused.
     *
     * @param raw the raw parameter value, may be {@code null} when not sent
     * @return {@code false} only for an explicit "false", "0" or "no"
     */
    private static boolean isChunkRequested(final String raw)
    {
        return raw == null || !FALSE_WORDS.contains(raw.toLowerCase(Locale.ROOT));
    }

    /**
     * Record that a job node could never enter the queue, so that polling it reports the failure instead of an
     * eternal "queued".
     *
     * @param resolver the session the node was created with
     * @param jobNode the node of the job that could not be queued
     */
    private void markUnqueueable(final ResourceResolver resolver, final Resource jobNode)
    {
        final ModifiableValueMap properties = jobNode.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            return;
        }
        properties.put(ParseJob.PN_STATUS, ParseJob.STATUS_FAILED);
        properties.put(ParseJob.PN_ERROR, "The job could not be queued");
        properties.put(ParseJob.PN_FINISHED, Calendar.getInstance());
        try {
            resolver.commit();
        } catch (final PersistenceException e) {
            LOGGER.error("Cannot mark parse job {} as failed: {}", jobNode.getName(), e.getMessage(), e);
        }
    }

}

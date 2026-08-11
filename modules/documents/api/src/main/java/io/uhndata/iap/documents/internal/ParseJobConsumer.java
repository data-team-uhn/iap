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
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Calendar;
import java.util.Map;
import java.util.function.Consumer;

import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonReader;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.consumer.JobConsumer;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatches a queued document parse to the Docling daemon, without waiting for the conversion itself. The daemon is
 * asked asynchronously — {@code POST /parse?path=&chunk=&job_id=&callback=} — and answers "queued" right away, so no
 * thread sits on an open connection for the minutes a conversion takes; when the daemon finishes, it POSTs the
 * outcome to {@link ParseCallbackServlet}, which records it on the job node. This consumer only walks the node from
 * {@code queued} to {@code active}: the callback endpoint takes it from there.
 *
 * <p>
 * A refused or unreachable dispatch marks the job failed and is never retried automatically
 * ({@link JobResult#CANCEL}): re-submitting through the endpoint is the retry. A dispatch the daemon accepted but
 * never calls back about (a daemon crash mid-parse) currently leaves the job {@code active}; sweeping such stragglers
 * is left for the real integration.
 * </p>
 *
 * <p>
 * Configurable through OSGi with {@code daemonUrl} (default {@code http://localhost:18765}), {@code callbackUrl}
 * (where the daemon should POST outcomes, default {@code http://host.docker.internal:8080} + the callback path, which
 * reaches a host-run app from inside the daemon's container) and {@code responseTimeout} (seconds to wait for the
 * daemon to accept a dispatch, default 30 — accepting is quick, only the conversion is slow).
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = JobConsumer.class, property = { JobConsumer.PROPERTY_TOPICS + "=" + ParseJob.TOPIC })
public class ParseJobConsumer implements JobConsumer
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ParseJobConsumer.class);

    /** Where the daemon listens when no {@code daemonUrl} is configured. */
    private static final String DEFAULT_DAEMON_URL = "http://localhost:18765";

    /** Where the daemon should POST outcomes when no {@code callbackUrl} is configured. */
    private static final String DEFAULT_CALLBACK_URL = "http://host.docker.internal:8080" + ParseJob.CALLBACK_PATH;

    /** How long to wait for the daemon to accept a dispatch when no {@code responseTimeout} is configured. */
    private static final long DEFAULT_RESPONSE_TIMEOUT = 30;

    /** How long to wait for the daemon to accept a connection. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** How much of a non-JSON daemon answer is kept as the error message. */
    private static final int ERROR_EXCERPT_LENGTH = 200;

    @Reference
    private ResourceResolverFactory resolverFactory;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    private String daemonUrl;

    private String callbackUrl;

    private Duration responseTimeout;

    /**
     * Read the configuration, applying the defaults.
     *
     * @param configuration the component configuration
     */
    @Activate
    @Modified
    protected void activate(final Map<String, Object> configuration)
    {
        this.daemonUrl = url(configuration, "daemonUrl", DEFAULT_DAEMON_URL);
        this.callbackUrl = url(configuration, "callbackUrl", DEFAULT_CALLBACK_URL);
        long seconds = DEFAULT_RESPONSE_TIMEOUT;
        final Object timeout = configuration.get("responseTimeout");
        if (timeout != null) {
            try {
                seconds = Long.parseLong(String.valueOf(timeout));
            } catch (final NumberFormatException e) {
                LOGGER.warn("Ignoring non-numeric responseTimeout: {}", timeout);
            }
        }
        this.responseTimeout = Duration.ofSeconds(seconds > 0 ? seconds : DEFAULT_RESPONSE_TIMEOUT);
    }

    @Override
    public JobResult process(final Job job)
    {
        final String jobId = job.getProperty(ParseJob.PN_JOB_ID, String.class);
        if (jobId == null) {
            LOGGER.warn("Dropping a parse job without a job identifier");
            return JobResult.CANCEL;
        }
        final String path;
        final boolean chunk;
        try (ResourceResolver resolver = openServiceResolver()) {
            final Resource jobNode = resolver.getResource(ParseJob.nodePath(jobId));
            if (jobNode == null) {
                LOGGER.warn("Dropping parse job {}: its job node is gone", jobId);
                return JobResult.CANCEL;
            }
            final ValueMap properties = jobNode.getValueMap();
            path = properties.get(ParseJob.PN_PATH, String.class);
            chunk = properties.get(ParseJob.PN_CHUNK, Boolean.TRUE);
            update(jobNode, resolver, editable -> {
                editable.put(ParseJob.PN_STATUS, ParseJob.STATUS_ACTIVE);
                editable.put(ParseJob.PN_STARTED, Calendar.getInstance());
            });
        } catch (final LoginException | PersistenceException e) {
            LOGGER.error("Cannot mark parse job {} as active: {}", jobId, e.getMessage(), e);
            return JobResult.CANCEL;
        }
        if (path == null || path.isBlank()) {
            fail(jobId, "The job records no document path");
            return JobResult.CANCEL;
        }
        return dispatch(jobId, path, chunk);
    }

    /**
     * Hand the parse to the daemon and leave the job active; the daemon's callback will finish it.
     *
     * @param jobId the identifier of the job being processed
     * @param path the path of the document to parse, as seen by the daemon
     * @param chunk whether the document should also be chunked
     * @return {@link JobResult#OK} when the daemon accepted the parse, {@link JobResult#CANCEL} otherwise
     */
    private JobResult dispatch(final String jobId, final String path, final boolean chunk)
    {
        try {
            final HttpResponse<String> response = send(buildRequest(jobId, path, chunk));
            if (response.statusCode() == 200 || response.statusCode() == 202) {
                LOGGER.debug("Parse job {} accepted by the daemon", jobId);
                return JobResult.OK;
            }
            fail(jobId, "The daemon answered HTTP " + response.statusCode() + ": " + errorMessage(response.body()));
        } catch (final IOException | IllegalArgumentException e) {
            fail(jobId, "Calling the daemon failed: " + e.getMessage());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(jobId, "Interrupted while waiting for the daemon");
        }
        return JobResult.CANCEL;
    }

    /**
     * Turn a parse request into the daemon dispatch performing it.
     *
     * @param jobId the identifier of the job, echoed back by the daemon's callback
     * @param path the path of the document to parse, as seen by the daemon
     * @param chunk whether the document should also be chunked
     * @return the request to send
     */
    private HttpRequest buildRequest(final String jobId, final String path, final boolean chunk)
    {
        final String url = this.daemonUrl + "/parse?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8)
            + "&chunk=" + chunk
            + "&job_id=" + URLEncoder.encode(jobId, StandardCharsets.UTF_8)
            + "&callback=" + URLEncoder.encode(this.callbackUrl, StandardCharsets.UTF_8);
        return HttpRequest.newBuilder(URI.create(url))
            .timeout(this.responseTimeout)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    }

    /**
     * Actually send the request. Overridable so that a test can stand in for the daemon.
     *
     * @param request the request to send
     * @return the raw response
     * @throws IOException if the daemon could not be reached, or stopped answering
     * @throws InterruptedException if the calling thread was interrupted while waiting for the response
     */
    protected HttpResponse<String> send(final HttpRequest request) throws IOException, InterruptedException
    {
        return this.client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Extract the daemon's own error message from a refusal, falling back to an excerpt of the raw body when it is
     * not the usual {@code {"error": "..."}}.
     *
     * @param body the response body
     * @return a message safe to record on the job node
     */
    private static String errorMessage(final String body)
    {
        if (body == null || body.isBlank()) {
            return "(empty response)";
        }
        try (JsonReader reader = Json.createReader(new StringReader(body))) {
            final String error = reader.readObject().getString(ParseJob.PN_ERROR, null);
            if (error != null) {
                return error;
            }
        } catch (final JsonException | ClassCastException e) {
            // Not JSON, fall through to the raw excerpt
        }
        return body.length() > ERROR_EXCERPT_LENGTH ? body.substring(0, ERROR_EXCERPT_LENGTH) + "…" : body;
    }

    /**
     * Record a failure on the job node.
     *
     * @param jobId the identifier of the job that failed
     * @param message what went wrong
     */
    private void fail(final String jobId, final String message)
    {
        LOGGER.warn("Parse job {} failed: {}", jobId, message);
        try (ResourceResolver resolver = openServiceResolver()) {
            final Resource jobNode = resolver.getResource(ParseJob.nodePath(jobId));
            if (jobNode == null) {
                LOGGER.error("Cannot record the outcome of parse job {}: its job node is gone", jobId);
                return;
            }
            update(jobNode, resolver, properties -> {
                properties.put(ParseJob.PN_STATUS, ParseJob.STATUS_FAILED);
                properties.put(ParseJob.PN_ERROR, message);
                properties.put(ParseJob.PN_FINISHED, Calendar.getInstance());
            });
        } catch (final LoginException | PersistenceException e) {
            LOGGER.error("Cannot record the outcome of parse job {}: {}", jobId, e.getMessage(), e);
        }
    }

    /**
     * Apply changes to a job node and commit them.
     *
     * @param jobNode the node to change
     * @param resolver the session the node was read with
     * @param changes the changes to apply
     * @throws PersistenceException if the changes cannot be persisted
     */
    private static void update(final Resource jobNode, final ResourceResolver resolver,
        final Consumer<ModifiableValueMap> changes) throws PersistenceException
    {
        final ModifiableValueMap editable = jobNode.adaptTo(ModifiableValueMap.class);
        if (editable == null) {
            throw new PersistenceException("The job node cannot be modified");
        }
        changes.accept(editable);
        resolver.commit();
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

    /**
     * Read a URL from the configuration, trimming trailing slashes so paths can be appended cleanly.
     *
     * @param configuration the component configuration
     * @param name the configuration property to read
     * @param fallback the URL to use when the property is absent or blank
     * @return the configured URL, or the fallback
     */
    private static String url(final Map<String, Object> configuration, final String name, final String fallback)
    {
        final String configured = String.valueOf(configuration.getOrDefault(name, fallback));
        return (configured.isBlank() ? fallback : configured).replaceAll("/+$", "");
    }
}

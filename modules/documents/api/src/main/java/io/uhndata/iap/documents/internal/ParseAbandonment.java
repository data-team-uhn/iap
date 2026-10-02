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
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Calendar;
import java.util.Map;
import java.util.Objects;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.documents.api.ParseControl;
import io.uhndata.iap.documents.api.ParseService;

/**
 * Stops a parse that has not finished: asks the daemon to drop it, wipes the staging folder, and deletes the job
 * record. Kept off the dispatch consumer, which is already at the edge of what one class should know.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ParseControl.class)
public class ParseAbandonment implements ParseControl
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ParseAbandonment.class);

    private static final String DEFAULT_DAEMON_URL = "http://localhost:18765";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** Seconds to wait for the daemon to answer a cancel, unless {@code responseTimeout} says otherwise. */
    private static final long DEFAULT_RESPONSE_TIMEOUT = 30;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private ParseService parseService;

    private String daemonUrl = DEFAULT_DAEMON_URL;

    private Duration responseTimeout = Duration.ofSeconds(30);

    private String daemonAuthorization;

    /**
     * Read where the daemon listens, the same properties the dispatch uses.
     *
     * @param configuration the component configuration
     */
    @Activate
    @Modified
    protected void activate(final Map<String, Object> configuration)
    {
        final String configured = String.valueOf(configuration.getOrDefault("daemonUrl", DEFAULT_DAEMON_URL));
        this.daemonUrl = (configured.isBlank() ? DEFAULT_DAEMON_URL : configured).replaceAll("/+$", "");
        long seconds = DEFAULT_RESPONSE_TIMEOUT;
        try {
            seconds = Long.parseLong(String.valueOf(configuration.getOrDefault("responseTimeout", seconds)));
        } catch (final NumberFormatException e) {
            LOGGER.warn("Ignoring non-numeric responseTimeout: {}", configuration.get("responseTimeout"));
        }
        this.responseTimeout = Duration.ofSeconds(seconds > 0 ? seconds : DEFAULT_RESPONSE_TIMEOUT);
        final String token = String.valueOf(configuration.getOrDefault(ParseJob.DAEMON_TOKEN_PROPERTY, "")).trim();
        final String daemonToken = token.isEmpty() ? environment(ParseJob.DAEMON_TOKEN_VARIABLE) : token;
        this.daemonAuthorization =
            daemonToken == null || daemonToken.isBlank() ? null : "Bearer " + daemonToken.trim();
    }

    @Override
    public boolean abandon(final String jobId)
    {
        if (jobId == null || !ParseJob.isJobId(jobId)) {
            return true;
        }
        askDaemonToCancel(jobId);
        try (ResourceResolver resolver = ParseJob.openResolver(this.resolverFactory)) {
            final Resource jobNode = resolver.getResource(ParseJob.nodePath(jobId));
            if (jobNode == null) {
                return true;
            }
            final String status = jobNode.getValueMap().get(ParseJob.PN_STATUS, String.class);
            if (ParseJob.STATUS_COMPLETED.equals(status)) {
                // The callback already has this outcome. Deleting it here would drop a parse somebody took.
                return false;
            }
            if (this.parseService.discardStaging(jobNode.getValueMap().get(ParseJob.PN_PATH, String.class))) {
                resolver.delete(jobNode);
            } else {
                // A worker still holds a file open (Windows). The record names the folder, so the sweep that
                // drops old records clears it once the worker lets go.
                markStopped(jobNode);
            }
            resolver.commit();
            LOGGER.info("Parse stopped: job={}", jobId);
        } catch (final LoginException | PersistenceException e) {
            LOGGER.error("Cannot stop parse job {}: {}", jobId, e.getMessage(), e);
        }
        return true;
    }

    private static void markStopped(final Resource jobNode)
    {
        final ModifiableValueMap properties = Objects.requireNonNull(jobNode.adaptTo(ModifiableValueMap.class),
            "The service session that read the job can write it");
        properties.put(ParseJob.PN_STATUS, ParseJob.STATUS_FAILED);
        properties.put(ParseJob.PN_ERROR, "Stopped");
        properties.put(ParseJob.PN_FINISHED, Calendar.getInstance());
        properties.remove(ParseJob.PN_TARGET);
    }

    /**
     * Ask the daemon to drop a conversion. A daemon that cannot be reached is not a reason to keep the job: the
     * record and the staging folder are removed either way, and a callback for a record that is gone is ignored.
     *
     * @param jobId the job to cancel
     */
    private void askDaemonToCancel(final String jobId)
    {
        final String url = this.daemonUrl + "/cancel?job_id=" + URLEncoder.encode(jobId, StandardCharsets.UTF_8);
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
            .timeout(this.responseTimeout)
            .POST(HttpRequest.BodyPublishers.noBody());
        if (this.daemonAuthorization != null) {
            request.header("Authorization", this.daemonAuthorization);
        }
        try {
            final HttpResponse<String> response = send(request.build());
            if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                LOGGER.warn("The daemon refused to stop parse job {}: {} {}", jobId, response.statusCode(),
                    response.body());
            }
        } catch (final IOException e) {
            LOGGER.warn("The daemon could not be asked to stop parse job {}: {}", jobId, e.getMessage());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while asking the daemon to stop parse job {}", jobId);
        }
    }

    /**
     * Send the cancel. Overridable so a test can stand in for the daemon.
     *
     * @param request the request to send
     * @return the raw response, which is not read
     * @throws IOException if the daemon could not be reached
     * @throws InterruptedException if the calling thread was interrupted while waiting
     */
    protected HttpResponse<String> send(final HttpRequest request) throws IOException, InterruptedException
    {
        return this.client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Read an environment variable. Overridable so a test can supply one.
     *
     * @param name the variable to read
     * @return the value, or {@code null} when not set
     */
    protected String environment(final String name)
    {
        return System.getenv(name);
    }
}

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

import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

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
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.documents.api.ParseControl;
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;

/**
 * Stops a parse that has not finished: asks the daemon to drop it, and marks the job record failed. The record keeps
 * its target and its staging folder, so the sweep hands the stop to whoever still waits on the parse, and later
 * drops the record and the folder together. Kept off the dispatch consumer, which is already at the edge of what one
 * class should know.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ParseControl.class, configurationPid = DaemonConnection.PID)
@Designate(ocd = DaemonConnectionConfiguration.class)
public class ParseAbandonment implements ParseControl
{
    /** What a stopped job records as its error. */
    static final String STOPPED = "Stopped";

    private static final Logger LOGGER = LoggerFactory.getLogger(ParseAbandonment.class);

    private final HttpClient client = DaemonConnection.newClient();

    @Reference
    private ResourceResolverFactory resolverFactory;

    private DaemonConnection daemon = DaemonConnection.read(Map.of(), name -> null);

    /**
     * Read where the daemon listens, the same properties the dispatch uses.
     *
     * @param configuration the component configuration
     */
    @Activate
    @Modified
    protected void activate(final Map<String, Object> configuration)
    {
        this.daemon = DaemonConnection.read(configuration, this::environment);
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
            if (ParseJob.STATUS_COMPLETED.equals(status) || ParseJob.STATUS_FAILED.equals(status)) {
                // Already settled: the outcome is somebody's to take, and the sweep drops it with its folder
                return false;
            }
            markStopped(jobNode);
            try {
                resolver.commit();
            } catch (final PersistenceException e) {
                // A callback or the sweep changed the record first: a normal race, not a fault
                resolver.revert();
                LOGGER.info("Parse job {} settled before it could be stopped: {}", jobId, e.getMessage());
                return false;
            }
            LOGGER.info("Parse stopped: job={}", jobId);
        } catch (final LoginException e) {
            LOGGER.error("Cannot stop parse job {}: {}", jobId, e.getMessage(), e);
            // The service user is missing, which only an administrator can fix
            ErrorLogger.logError(e, ErrorContext.of(ParseAbandonment.class, "abandon").with("job", jobId));
        }
        // The record still names the folder, or may: it is the sweep's to remove, not the caller's
        return false;
    }

    /**
     * Record the stop as a failure. The target stays, since the caller's own change may yet be undone and the file
     * would then still wait on this job.
     *
     * @param jobNode the job to stop
     */
    private static void markStopped(final Resource jobNode)
    {
        final ModifiableValueMap properties = Objects.requireNonNull(jobNode.adaptTo(ModifiableValueMap.class),
            "The service session that read the job can write it");
        properties.put(ParseJob.PN_STATUS, ParseJob.STATUS_FAILED);
        properties.put(ParseJob.PN_ERROR, STOPPED);
        properties.put(ParseJob.PN_FINISHED, Calendar.getInstance());
        properties.remove(ParseJob.PN_OUTPUTS);
    }

    /**
     * Ask the daemon to drop a conversion, without waiting for its answer. The record is marked stopped either way,
     * and a callback for a stopped job is refused by whoever the job was for.
     *
     * @param jobId the job to cancel
     */
    private void askDaemonToCancel(final String jobId)
    {
        sendAsync(this.daemon.post("/cancel?job_id=" + URLEncoder.encode(jobId, StandardCharsets.UTF_8)))
            .whenComplete((response, error) -> {
                if (error != null) {
                    LOGGER.warn("The daemon could not be asked to stop parse job {}: {}", jobId, error.getMessage());
                } else if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                    LOGGER.warn("The daemon refused to stop parse job {}: {} {}", jobId, response.statusCode(),
                        response.body());
                    // Usually a token the two sides disagree on, which only an administrator can fix
                    ErrorLogger.logProblem("The document daemon refused to stop a parse",
                        ErrorContext.of(ParseAbandonment.class, "cancel").with("job", jobId)
                            .with("status", response.statusCode()));
                }
            });
    }

    /**
     * Send the cancel. Overridable so a test can stand in for the daemon.
     *
     * @param request the request to send
     * @return the daemon's response, once it arrives
     */
    protected CompletableFuture<HttpResponse<String>> sendAsync(final HttpRequest request)
    {
        return this.client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
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

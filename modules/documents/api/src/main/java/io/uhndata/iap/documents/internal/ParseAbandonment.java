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
import io.uhndata.iap.documents.api.ParseOutcome;
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;

/**
 * Stops a parse that has not finished: marks the job record failed, then asks the daemon to drop the parse. The record
 * keeps its target and its staging folder, so the sweep later hands the failure to whoever still waits on the parse,
 * then deletes the record and the folder together. This is a class of its own to keep {@link ParseJobConsumer}
 * small.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ParseControl.class, configurationPid = DaemonConnection.DAEMON_PID)
@Designate(ocd = DaemonConnectionConfiguration.class)
public class ParseAbandonment implements ParseControl
{
    /** The error recorded on a stopped job. */

    private static final Logger LOGGER = LoggerFactory.getLogger(ParseAbandonment.class);

    private final HttpClient client = DaemonConnection.newClient();

    @Reference
    private ResourceResolverFactory resolverFactory;

    private DaemonConnection daemon = DaemonConnection.read(Map.of(), name -> null);

    /**
     * Reads how to reach the daemon, from the same shared configuration the dispatch uses.
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
    public boolean stopParse(final String jobId)
    {
        if (jobId == null || !ParseJob.isJobId(jobId)) {
            return true;
        }
        boolean gone = false;
        boolean settled = false;
        try (ResourceResolver resolver = ParseJob.openResolver(this.resolverFactory)) {
            final Resource jobNode = resolver.getResource(ParseJob.nodePath(jobId));
            if (jobNode == null) {
                gone = true;
            } else if (isSettled(jobNode)) {
                // Already has an outcome: a handler takes it, and the sweep deletes the record and its folder
                settled = true;
            } else {
                stopRecord(resolver, jobNode, jobId);
            }
        } catch (final LoginException e) {
            LOGGER.error("Cannot stop parse job {}: {}", jobId, e.getMessage(), e);
            // The service user is missing, which only an administrator can fix
            ErrorLogger.logError(e, ErrorContext.of(ParseAbandonment.class, "stopParse").with("job", jobId));
        }
        if (!settled) {
            // Sent last: once the record says stopped, a queued job is never dispatched. Sent first, the cancel could
            // reach the daemon before a dispatch already on its way, and get "unknown"
            askDaemonToCancel(jobId);
        }
        // A record that still exists names the folder, so the sweep deletes it, not the caller
        return gone;
    }

    private static boolean isSettled(final Resource jobNode)
    {
        final String status = jobNode.getValueMap().get(ParseJob.PN_STATUS, String.class);
        return ParseJob.STATUS_COMPLETED.equals(status) || ParseJob.STATUS_FAILED.equals(status);
    }

    /**
     * Marks the job record stopped and commits it. When something else changed the record first, the stop is
     * reverted and the record is left as it is.
     *
     * @param resolver the service session the record was read through
     * @param jobNode the job to stop
     * @param jobId the job's id, for the log
     */
    private static void stopRecord(final ResourceResolver resolver, final Resource jobNode, final String jobId)
    {
        markStopped(jobNode);
        try {
            resolver.commit();
            LOGGER.info("Parse stopped: job={}", jobId);
        } catch (final PersistenceException e) {
            // A callback, the sweep or the dispatch changed the record first: a normal race, not a fault
            resolver.revert();
            LOGGER.info("Parse job {} changed before it could be stopped: {}", jobId, e.getMessage());
        }
    }

    /**
     * Records the stop as a failure and drops any outputs. The target stays: the caller's change may still be
     * undone, and then the file still waits on this job.
     *
     * @param jobNode the job to stop
     */
    private static void markStopped(final Resource jobNode)
    {
        final ModifiableValueMap properties = Objects.requireNonNull(jobNode.adaptTo(ModifiableValueMap.class),
            "The service session that read the job can write it");
        properties.put(ParseJob.PN_STATUS, ParseJob.STATUS_FAILED);
        properties.put(ParseJob.PN_ERROR, ParseOutcome.STOPPED_ERROR);
        properties.put(ParseJob.PN_FINISHED, Calendar.getInstance());
        properties.remove(ParseJob.PN_OUTPUTS);
    }

    /**
     * Asks the daemon to drop a parse, without waiting for its answer. Whatever it answers, a late callback for a
     * stopped job is refused by the handler the job was for.
     *
     * @param jobId the job to cancel
     */
    private void askDaemonToCancel(final String jobId)
    {
        sendAsync(this.daemon.buildPostRequest("/cancel?job_id=" + URLEncoder.encode(jobId, StandardCharsets.UTF_8)))
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
     * Sends the cancel request. Tests override this to stand in for the daemon.
     *
     * @param request the request to send
     * @return the daemon's response, when it arrives
     */
    protected CompletableFuture<HttpResponse<String>> sendAsync(final HttpRequest request)
    {
        return this.client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Reads an environment variable. Tests override this to supply one.
     *
     * @param name the variable to read
     * @return the value, or {@code null} when it is not set
     */
    protected String environment(final String name)
    {
        return System.getenv(name);
    }
}

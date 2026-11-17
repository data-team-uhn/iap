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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.apache.sling.api.resource.LoginException;
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
 * then deletes the record and the folder together.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ParseControl.class, configurationPid = DaemonConnection.DAEMON_PID)
@Designate(ocd = DaemonConnectionConfiguration.class)
public class ParseAbandonment implements ParseControl
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ParseAbandonment.class);

    /** A stop that loses to the dispatch is tried once more on the fresh record. */
    private static final int STOP_ATTEMPTS = 2;

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
        this.daemon = DaemonConnection.read(configuration, this::getEnvironmentVariable);
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
            } else {
                settled = stopRecord(resolver, jobId);
            }
        } catch (final LoginException e) {
            LOGGER.error("Cannot stop parse job {}: {}", jobId, e.getMessage(), e);
            // The service user is missing, which only an administrator can fix
            ErrorLogger.logError(e, ErrorContext.of(ParseAbandonment.class, "stopParse").with("job", jobId));
        }
        if (!settled) {
            // Sent after the record says stopped, so a queued job is never dispatched
            askDaemonToCancel(jobId);
        }
        // A record that still exists names the folder, so the sweep deletes it, not the caller
        return gone;
    }

    /**
     * Marks the job record stopped and commits it. The target stays: the caller's change may still be undone, and
     * then the file still waits on this job. When the dispatch changed the record first, the stop is tried once
     * more on the fresh record, so the record and the daemon both end up stopped.
     *
     * @param resolver the service session the record is read through
     * @param jobId the job to stop
     * @return {@code true} when the job already had an outcome or is gone, so the daemon has nothing to drop
     */
    private static boolean stopRecord(final ResourceResolver resolver, final String jobId)
    {
        for (int attempt = 1; attempt <= STOP_ATTEMPTS; ++attempt) {
            final Resource jobNode = resolver.getResource(ParseJob.nodePath(jobId));
            if (jobNode == null || new JobRecord(jobNode).isSettled()) {
                // A handler takes the outcome, and the sweep deletes the record and its folder
                return true;
            }
            try {
                new JobRecord(jobNode).markFailed(ParseOutcome.STOPPED_ERROR);
                resolver.commit();
                LOGGER.info("Parse stopped: job={}", jobId);
                return false;
            } catch (final PersistenceException e) {
                // A callback, the sweep or the dispatch changed the record first: a normal race, not a fault
                resolver.revert();
                resolver.refresh();
                LOGGER.info("Parse job {} changed before it could be stopped: {}", jobId, e.getMessage());
            }
        }
        return false;
    }

    /**
     * Asks the daemon to drop a parse, without waiting for its answer. Whatever it answers, the callback keeps a
     * stopped job stopped.
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
        return this.daemon.sendAsync(request);
    }

    /**
     * Reads an environment variable. Tests override this to supply one.
     *
     * @param name the variable to read
     * @return the value, or {@code null} when it is not set
     */
    protected String getEnvironmentVariable(final String name)
    {
        return System.getenv(name);
    }
}

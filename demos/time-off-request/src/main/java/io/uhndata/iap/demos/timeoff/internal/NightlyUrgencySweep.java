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
package io.uhndata.iap.demos.timeoff.internal;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.commons.scheduler.ScheduleOptions;
import org.apache.sling.commons.scheduler.Scheduler;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;

/**
 * Has every request waiting for its decision judged again once a night, because urgency changes with the date and
 * not with the request.
 *
 * <p>An undecided request for the far future becomes urgent when the calendar reaches it. Nothing about the request
 * changes, so no event fires and no workflow runs. A process deadline will not do either. The engine's timers
 * count a duration from when a task started, and cannot express "the day before this date".</p>
 *
 * <p>So the sweep supplies the event. It sends {@value #REASSESS_EVENT} to each waiting request. The demo's system
 * workflow of that name runs the same urgency step that the request's own process runs when it is sent. The engine
 * makes the change, as it makes every change, one request at a time.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class NightlyUrgencySweep implements Runnable
{
    /**
     * The cron expression for 00:05 every night, when "tomorrow" has just become "today" for the requests that were
     * waiting on it.
     */
    static final String SCHEDULE_EXPRESSION = "0 5 0 * * ?";

    /** The name of the event that has a request's urgency judged again. */
    static final String REASSESS_EVENT = "reassessTimeOffUrgency";

    private static final String JOB_NAME = "iap-demo-time-off-urgency";

    private static final String SUBSERVICE_NAME = "urgency";

    /**
     * The JCR-SQL2 query for the requests this demo's process is running that wait for a decision. The budget lookup
     * is the process's first step, so every request it runs carries the balance, whichever schema the request was
     * raised from.
     */
    private static final String WAITING_REQUESTS_QUERY = String.format(
        "SELECT * FROM [sub:Submission] AS submission WHERE submission.[%s] IS NOT NULL AND submission.[tags] = '%s'",
        TimeOffBudgetHandler.REMAINING_DAYS_PROPERTY, TimeOffUrgency.SUBMITTED_TAG);

    private static final Logger LOGGER = LoggerFactory.getLogger(NightlyUrgencySweep.class);

    @Reference
    private Scheduler scheduler;

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private WorkflowEngine engine;

    @Activate
    protected void activate()
    {
        final ScheduleOptions options = this.scheduler.EXPR(SCHEDULE_EXPRESSION);
        options.name(JOB_NAME);
        options.canRunConcurrently(false);
        // One sweep at a time, on one instance of a cluster
        options.onLeaderOnly(true);
        this.scheduler.schedule(this, options);
        LOGGER.info("Scheduled the nightly time off urgency sweep");
    }

    @Deactivate
    protected void deactivate()
    {
        this.scheduler.unschedule(JOB_NAME);
    }

    @Override
    public void run()
    {
        try (ResourceResolver resolver = this.resolverFactory.getServiceResourceResolver(
            Map.of(ResourceResolverFactory.SUBSERVICE, SUBSERVICE_NAME))) {
            sweep(resolver);
        } catch (final LoginException e) {
            LOGGER.error("The demo's service user is not available, so no request can be judged", e);
            ErrorLogger.logError(e, ErrorContext.of(NightlyUrgencySweep.class, "login"));
        } catch (final RuntimeException e) {
            LOGGER.error("The time off urgency sweep could not finish: {}", e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(NightlyUrgencySweep.class, "sweep"));
        }
    }

    /**
     * Has every waiting request judged again.
     *
     * @param resolver the session to find the requests through, and to send the events as
     */
    void sweep(final ResourceResolver resolver)
    {
        final Map<String, Exception> failures = new LinkedHashMap<>();
        requests(resolver).forEach(path -> reassess(resolver, path).ifPresent(failure -> failures.put(path, failure)));
        if (!failures.isEmpty()) {
            record(failures);
        }
    }

    /**
     * The paths of the waiting requests, read out in one go. Each event commits a change, and a query's iterator
     * re-checks its rows against the session, so sending while it is open could change what it returns.
     *
     * @param resolver the session to query through
     * @return the absolute paths of the waiting {@code sub:Submission} nodes; empty if none is waiting
     */
    private static List<String> requests(final ResourceResolver resolver)
    {
        final List<String> paths = new ArrayList<>();
        for (final Iterator<Resource> found = resolver.findResources(WAITING_REQUESTS_QUERY, "JCR-SQL2");
            found.hasNext();) {
            paths.add(found.next().getPath());
        }
        return paths;
    }

    /**
     * Has one request judged again.
     *
     * @param resolver the session to send the event as
     * @param path the absolute path of the {@code sub:Submission}
     * @return the exception that stopped the engine judging it; empty if it was judged, or the request no longer
     *     exists
     */
    private Optional<Exception> reassess(final ResourceResolver resolver, final String path)
    {
        final Resource request = resolver.getResource(path);
        if (request == null) {
            // Gone since the query ran
            return Optional.empty();
        }
        try {
            this.engine.receiveEvent(request, new WorkflowEvent(REASSESS_EVENT, Map.of()));
            return Optional.empty();
        } catch (final WorkflowException | RuntimeException e) {
            // One request that cannot be judged must not stop the sweep reaching the rest. It is recorded once for
            // the whole sweep, since one cause usually fails them all
            LOGGER.warn("Could not judge the urgency of {}: {}", path, e.getMessage(), e);
            return Optional.of(e);
        }
    }

    private static void record(final Map<String, Exception> failures)
    {
        final Map.Entry<String, Exception> first = failures.entrySet().iterator().next();
        LOGGER.error("The urgency of {} time off requests could not be judged", failures.size());
        ErrorLogger.logError(first.getValue(), ErrorContext.of(NightlyUrgencySweep.class, "reassess")
            .about(first.getKey())
            .with("failedRequests", failures.size()));
    }
}

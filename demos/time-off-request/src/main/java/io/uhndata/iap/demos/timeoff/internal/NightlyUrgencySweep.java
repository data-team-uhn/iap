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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
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

/**
 * Re-judges every open request once a night, because urgency changes with the date and not with the request.
 *
 * <p>A request for the far future that nobody has decided becomes urgent on its own, by the calendar reaching it.
 * Nothing about the request changes, so no event fires and no workflow runs — which is exactly why this cannot be
 * a deadline on the process. The engine's timers count a duration from the moment a task started;
 * "the day before an absolute date" is not something they can express, and pretending otherwise by arming a timer
 * at submission would be wrong the moment somebody edited the date.</p>
 *
 * <p>Every open request, rather than the ones a query says are urgent now: the sweep has to take the flag
 * <em>off</em> the requests that have stopped being urgent as surely as it puts it on the ones that have started,
 * and a query for the second set cannot see the first. At demo scale that is a traversal, which is what the
 * engine's own deadline sweep already accepts.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class NightlyUrgencySweep implements Runnable
{
    /** Just after midnight, when "tomorrow" has just become "today" for the requests that were waiting on it. */
    static final String SCHEDULE = "0 5 0 * * ?";

    /** The schema this demo owns. Every version of it is a child, and every request points at it. */
    static final String OWN_SCHEMA = "/Schemas/timeOffRequest";

    private static final String JOB_NAME = "iap-demo-time-off-urgency";

    private static final String SUBSERVICE = "urgency";

    /**
     * This demo's requests, judged one at a time, ordered so that a sweep reads them the same way twice.
     *
     * <p>Filtered on the submission's own {@code schema} reference rather than on its schema <em>version</em>:
     * asking for every version of a schema through the version property means a join, while the schema is one
     * property comparison — which is exactly why a submission carries both.</p>
     */
    private static final String OWN_REQUESTS =
        "SELECT * FROM [sub:Submission] AS submission WHERE submission.[schema] = '%s'"
            + " ORDER BY submission.[jcr:created] ASC";

    private static final Logger LOGGER = LoggerFactory.getLogger(NightlyUrgencySweep.class);

    @Reference
    private Scheduler scheduler;

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Activate
    protected void activate()
    {
        final ScheduleOptions options = this.scheduler.EXPR(SCHEDULE);
        options.name(JOB_NAME);
        // Two sweeps at once would both decide the same request, and the loser's answer would be the one that
        // stuck for no reason anybody could reconstruct
        options.canRunConcurrently(false);
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
        try (ResourceResolver resolver =
            this.resolverFactory.getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, SUBSERVICE))) {
            sweep(resolver, LocalDate.now());
        } catch (final LoginException e) {
            LOGGER.error("The demo's service user is not available, so no request can be judged", e);
            ErrorLogger.logError(e, ErrorContext.of(NightlyUrgencySweep.class, "login"));
        }
    }

    /**
     * Judges every request this demo owns.
     *
     * @param resolver the session to read and write through
     * @param today the date to judge them against
     */
    void sweep(final ResourceResolver resolver, final LocalDate today)
    {
        for (final String path : requests(resolver)) {
            final Resource request = resolver.getResource(path);
            if (request == null) {
                continue;
            }
            try {
                TimeOffUrgency.mark(request, today);
            } catch (final PersistenceException e) {
                // One request the sweep cannot write must not cost every later one its answer
                LOGGER.error("Could not judge the urgency of {}: {}", path, e.getMessage(), e);
                ErrorLogger.logError(e, ErrorContext.of(NightlyUrgencySweep.class, "mark").about(path));
            }
        }
        commit(resolver);
    }

    /**
     * The paths of this demo's requests, read out in one go: holding a query's own iterator open while writing
     * through the same session invites it to reflect the writes back.
     *
     * @param resolver the session to query through
     * @return the paths, oldest first, empty when the demo's schema is not installed
     */
    private static List<String> requests(final ResourceResolver resolver)
    {
        final String identifier = identifierOf(resolver.getResource(OWN_SCHEMA));
        if (identifier == null) {
            LOGGER.warn("The time off request schema is not installed, so there is nothing to judge");
            return List.of();
        }
        // A reference is stored as the target's identifier, so that is what the comparison takes. Written into
        // the query rather than bound because a repository-issued identifier is not caller input
        final String query = String.format(OWN_REQUESTS, identifier);
        final List<String> paths = new ArrayList<>();
        for (final Iterator<Resource> found = resolver.findResources(query, "JCR-SQL2"); found.hasNext();) {
            paths.add(found.next().getPath());
        }
        return paths;
    }

    /**
     * The identifier a reference to this resource would hold.
     *
     * @param resource the resource to identify, possibly {@code null}
     * @return its identifier, or {@code null} when there is no such resource or it is not referenceable
     */
    private static String identifierOf(final Resource resource)
    {
        final Node node = resource == null ? null : resource.adaptTo(Node.class);
        try {
            return node == null ? null : node.getIdentifier();
        } catch (final RepositoryException e) {
            LOGGER.error("Could not identify the time off request schema: {}", e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(NightlyUrgencySweep.class, "identifySchema"));
            return null;
        }
    }

    private static void commit(final ResourceResolver resolver)
    {
        try {
            if (resolver.hasChanges()) {
                resolver.commit();
            }
        } catch (final PersistenceException e) {
            LOGGER.error("Could not record what the urgency sweep decided", e);
            ErrorLogger.logError(e, ErrorContext.of(NightlyUrgencySweep.class, "commit"));
        }
    }
}

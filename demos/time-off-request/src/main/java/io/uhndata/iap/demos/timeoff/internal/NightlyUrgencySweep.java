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

import io.uhndata.iap.submissions.models.Submission;

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

    /** The requests this demo owns, whichever version of its schema they answer. */
    static final String OWN_REQUESTS = "/Schemas/timeOffRequest/";

    private static final String JOB_NAME = "iap-demo-time-off-urgency";

    private static final String SUBSERVICE = "urgency";

    /** Every submission, judged one at a time. Ordered so that a sweep reads them the same way twice. */
    private static final String ALL_SUBMISSIONS =
        "SELECT * FROM [sub:Submission] AS submission ORDER BY submission.[jcr:created] ASC";

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
            if (request == null || !ownRequest(request)) {
                continue;
            }
            try {
                TimeOffUrgency.mark(request, today);
            } catch (final PersistenceException e) {
                // One request the sweep cannot write must not cost every later one its answer
                LOGGER.error("Could not judge the urgency of {}: {}", path, e.getMessage(), e);
            }
        }
        commit(resolver);
    }

    /**
     * Whether a submission answers this demo's schema. Read from the request rather than asked of the query,
     * because the reference is stored as an identifier while the schema is addressed by path.
     *
     * @param request the submission to check
     * @return {@code true} if it answers a version of the time off request schema
     */
    private static boolean ownRequest(final Resource request)
    {
        final Submission submission = request.adaptTo(Submission.class);
        return submission != null && submission.getSchemaVersion() != null
            && submission.getSchemaVersion().getPath().startsWith(OWN_REQUESTS);
    }

    /**
     * The paths of every submission, read out in one go: holding a query's own iterator open while writing
     * through the same session invites it to reflect the writes back.
     *
     * @param resolver the session to query through
     * @return the paths, oldest first
     */
    private static List<String> requests(final ResourceResolver resolver)
    {
        final List<String> paths = new ArrayList<>();
        for (final Iterator<Resource> found = resolver.findResources(ALL_SUBMISSIONS, "JCR-SQL2");
            found.hasNext();) {
            paths.add(found.next().getPath());
        }
        return paths;
    }

    private static void commit(final ResourceResolver resolver)
    {
        try {
            if (resolver.hasChanges()) {
                resolver.commit();
            }
        } catch (final PersistenceException e) {
            LOGGER.error("Could not record what the urgency sweep decided", e);
        }
    }
}

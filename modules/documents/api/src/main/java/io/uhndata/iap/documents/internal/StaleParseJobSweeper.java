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

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.documents.api.ParseService;

/**
 * Fails parse jobs that nobody will ever finish, drops the records of jobs nobody came back for, and takes their
 * staging folders off the shared volume with them.
 *
 * <p>
 * The daemon is the only thing that ends an {@code active} job, through the callback. A daemon killed mid-parse, a
 * container replaced while it was working, a callback refused because the token was wrong — each leaves a job
 * {@code active} for good. Nothing upstream copes with that: the {@code sub:File} stays {@code queued}, so no
 * submission ever sees all its parses settle, the reading is never queued, and the submitter is left with a
 * spinner that has nothing behind it. A stuck job is worse than a failed one, because a failure can be retried and
 * a spinner cannot.
 * </p>
 *
 * <p>
 * So a job unanswered past {@link #DEFAULT_MAX_AGE_MINUTES} minutes is declared failed and handed to the outcome
 * handlers exactly as a real failure is. Re-submitting is the retry, which is how every other failure here already
 * works. The age is generous on purpose: it has to sit comfortably above the daemon's own
 * {@code IAP_DOCLING_PARSE_TIMEOUT_SECONDS}, or this would fail parses that were still going to answer.
 * </p>
 *
 * <p>
 * The staging folder goes with it. A parse works in a folder of its own, so once the job is over nothing in there
 * belongs to anybody — including a {@code .tmp} left behind when a worker was killed between writing an output and
 * renaming it into place, which the daemon's own cleanup never got to run on. Abandoning the folder is the only
 * way such a file survives, and the abandoned document beside it is the larger leak of the two.
 * </p>
 *
 * <p>
 * Runs every {@code scheduler.period} seconds, never two at a time, and only on the leader in a cluster, so a job
 * is swept exactly once however many instances are running.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = Runnable.class, property = {
    "scheduler.period:Long=" + StaleParseJobSweeper.DEFAULT_PERIOD_SECONDS,
    "scheduler.concurrent:Boolean=false",
    "scheduler.runOn=LEADER"
})
public class StaleParseJobSweeper implements Runnable
{
    /** How often the sweep runs when nothing says otherwise: every five minutes. */
    static final long DEFAULT_PERIOD_SECONDS = 300;

    /** The configuration property overriding how long a job may go unanswered. */
    static final String MAX_AGE_PROPERTY = "maxAgeMinutes";

    /**
     * How long a job may go unanswered before it is declared lost. Half an hour, which is above the daemon's own
     * quarter-hour ceiling on a whole conversion, with room for the queue in front of it.
     */
    static final long DEFAULT_MAX_AGE_MINUTES = 30;

    private static final Logger LOGGER = LoggerFactory.getLogger(StaleParseJobSweeper.class);

    /** What is recorded on a job the daemon never answered for. */
    private static final String LOST = "No outcome arrived within %d minutes; the parse was given up on";

    private static final long MILLISECONDS_PER_MINUTE = 60_000L;

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private ParseOutcomeDispatcher outcomes;

    @Reference
    private ParseService parseService;

    /** Volatile because the sweep reads it on the scheduler's thread while a reconfiguration writes it. */
    private volatile long maxAgeMinutes;

    /**
     * Read how long a job may go unanswered.
     *
     * @param configuration the component configuration
     */
    @Activate
    @Modified
    protected void activate(final Map<String, Object> configuration)
    {
        long minutes = DEFAULT_MAX_AGE_MINUTES;
        final Object configured = configuration.get(MAX_AGE_PROPERTY);
        if (configured != null) {
            try {
                minutes = Long.parseLong(String.valueOf(configured));
            } catch (final NumberFormatException e) {
                LOGGER.warn("Ignoring non-numeric {}: {}", MAX_AGE_PROPERTY, configured);
            }
        }
        this.maxAgeMinutes = minutes > 0 ? minutes : DEFAULT_MAX_AGE_MINUTES;
    }

    @Override
    public void run()
    {
        try (ResourceResolver resolver = ParseJob.openResolver(this.resolverFactory)) {
            final Resource jobsRoot = resolver.getResource(ParseJob.JOBS_PATH);
            if (jobsRoot == null) {
                return;
            }
            final long deadline = System.currentTimeMillis() - this.maxAgeMinutes * MILLISECONDS_PER_MINUTE;
            for (final Resource jobNode : stale(jobsRoot, deadline)) {
                sweep(resolver, jobNode);
            }
            for (final Resource jobNode : forgotten(jobsRoot, deadline)) {
                forget(resolver, jobNode);
            }
        } catch (final LoginException e) {
            LOGGER.error("Cannot access the parse jobs storage to sweep it: {}", e.getMessage(), e);
        }
    }

    /**
     * The jobs that have been waiting too long.
     *
     * <p>Collected before anything is written, because sweeping one deletes its node and the listing being walked
     * is the one it would be deleted from.</p>
     *
     * @param jobsRoot the node holding the job nodes
     * @param deadline the moment before which an unfinished job is declared lost
     * @return the job nodes to sweep
     */
    private static List<Resource> stale(final Resource jobsRoot, final long deadline)
    {
        final List<Resource> stale = new ArrayList<>();
        for (final Resource jobNode : jobsRoot.getChildren()) {
            final ValueMap properties = jobNode.getValueMap();
            final String status = properties.get(ParseJob.PN_STATUS, String.class);
            if (!ParseJob.STATUS_QUEUED.equals(status) && !ParseJob.STATUS_ACTIVE.equals(status)) {
                continue;
            }
            final Calendar since = waitingSince(properties);
            if (since != null && since.getTimeInMillis() < deadline) {
                stale.add(jobNode);
            }
        }
        return stale;
    }

    /**
     * The settled jobs nobody came back for.
     *
     * <p>A job queued with a {@code target} is handed to its handler and its record deleted straight away. A job
     * queued without one is nobody's to hand over, so its record is kept for polling - and was kept for good,
     * because nothing deleted it and this sweep only ever looked at jobs that had not finished. Its staging
     * folder stayed on the volume with it. So did the record of a job whose handler refused the outcome, which
     * nothing retries either.</p>
     *
     * <p>The same age answers both: a settled record is worth keeping while somebody might still poll it, and
     * worth nothing afterwards.</p>
     *
     * @param jobsRoot the node holding the job nodes
     * @param deadline the moment before which a settled job's record is no longer worth keeping
     * @return the job nodes to forget
     */
    private static List<Resource> forgotten(final Resource jobsRoot, final long deadline)
    {
        final List<Resource> forgotten = new ArrayList<>();
        for (final Resource jobNode : jobsRoot.getChildren()) {
            final ValueMap properties = jobNode.getValueMap();
            final String status = properties.get(ParseJob.PN_STATUS, String.class);
            if (!ParseJob.STATUS_COMPLETED.equals(status) && !ParseJob.STATUS_FAILED.equals(status)) {
                continue;
            }
            final Calendar finished = properties.get(ParseJob.PN_FINISHED, Calendar.class);
            if (finished != null && finished.getTimeInMillis() < deadline) {
                forgotten.add(jobNode);
            }
        }
        return forgotten;
    }

    /**
     * Drop one settled job: clear the volume it worked on, then delete its record.
     *
     * @param resolver the session the job nodes are read and written through
     * @param jobNode the job to forget
     */
    private void forget(final ResourceResolver resolver, final Resource jobNode)
    {
        final String jobId = jobNode.getValueMap().get(ParseJob.PN_JOB_ID, jobNode.getName());
        final String staged = jobNode.getValueMap().get(ParseJob.PN_PATH, String.class);
        this.parseService.discardStaging(staged);
        try {
            resolver.delete(jobNode);
            resolver.commit();
            LOGGER.info("Parse job {} settled more than {} minutes ago; its record is dropped", jobId,
                this.maxAgeMinutes);
        } catch (final PersistenceException e) {
            LOGGER.error("Cannot drop the record of parse job {}: {}", jobId, e.getMessage(), e);
            resolver.revert();
        }
    }

    /**
     * When a job started waiting: from the dispatch, or failing that from when it was queued. A job that never
     * reached the daemon is as lost as one that did, and it has no started time to be judged by.
     *
     * @param properties the job node
     * @return the moment to measure the wait from, or {@code null} when the job records neither
     */
    private static Calendar waitingSince(final ValueMap properties)
    {
        final Calendar started = properties.get(ParseJob.PN_STARTED, Calendar.class);
        return started == null ? properties.get(ParseJob.PN_CREATED, Calendar.class) : started;
    }

    /**
     * Declare one job lost: record the failure, clear the volume, then tell whoever was waiting on it.
     *
     * @param resolver the session the job nodes are read and written through
     * @param jobNode the job to give up on
     */
    private void sweep(final ResourceResolver resolver, final Resource jobNode)
    {
        final String jobId = jobNode.getValueMap().get(ParseJob.PN_JOB_ID, jobNode.getName());
        final ModifiableValueMap properties = jobNode.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            LOGGER.error("Cannot give up on parse job {}: its node cannot be modified", jobId);
            return;
        }
        final String staged = jobNode.getValueMap().get(ParseJob.PN_PATH, String.class);
        properties.put(ParseJob.PN_STATUS, ParseJob.STATUS_FAILED);
        properties.put(ParseJob.PN_ERROR, String.format(LOST, this.maxAgeMinutes));
        properties.put(ParseJob.PN_FINISHED, Calendar.getInstance());
        properties.remove(ParseJob.PN_OUTPUTS);
        try {
            resolver.commit();
        } catch (final PersistenceException e) {
            LOGGER.error("Cannot give up on parse job {}: {}", jobId, e.getMessage(), e);
            resolver.revert();
            return;
        }
        LOGGER.warn("Parse job {} gave no outcome within {} minutes; declared failed", jobId, this.maxAgeMinutes);
        // The outputs are missing or half written and the upload is one the repository already holds, so there is
        // nothing left on the volume worth keeping
        this.parseService.discardStaging(staged);
        this.outcomes.settle(resolver, jobNode);
    }
}

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

import io.uhndata.iap.documents.api.ParseOutcome;
import io.uhndata.iap.documents.api.ParseService;
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;

/**
 * Fails parse jobs that nobody will ever finish, drops the records of jobs nobody came back for, and takes their
 * staging folders off the shared volume with them.
 *
 * <p>
 * Only the daemon's callback, a failed dispatch, or a stop ends an {@code active} job. A daemon killed mid-parse, a
 * container replaced while it was working, or a callback refused for a wrong token leaves the job {@code active} for
 * good. Then the {@code sub:File} stays {@code queued}, the submission never sees all its parses finish, the reading
 * never starts, and the submitter sees a spinner that never stops. A stuck job is worse than a failed one: a failure
 * can be retried, a spinner cannot.
 * </p>
 *
 * <p>
 * So a job unanswered past {@link #DEFAULT_MAX_AGE_MINUTES} minutes is declared failed and handed to the outcome
 * handlers exactly as a real failure is. An outcome no handler took is offered again on each sweep, until its
 * record is old enough to drop. The age is generous on purpose: it has to sit comfortably above the daemon's own
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
     * quarter-hour ceiling on a PDF conversion, with room for the queue in front of it. A Word document has no
     * such ceiling.
     */
    static final long DEFAULT_MAX_AGE_MINUTES = 30;

    private static final Logger LOGGER = LoggerFactory.getLogger(StaleParseJobSweeper.class);

    /** What is recorded on a job the daemon never answered for. */
    private static final String LOST_MESSAGE = "No outcome arrived within %d minutes; the parse was given up on";

    private static final long MILLISECONDS_PER_MINUTE = 60_000L;

    /** How long a settled record is left alone before its outcome is offered again: two minutes. */
    private static final long RETRY_AFTER_MILLISECONDS = 2 * MILLISECONDS_PER_MINUTE;

    /** How long a record is kept for a staging folder that cannot be removed: a day. */
    private static final long GIVE_UP_ON_FOLDER_MILLISECONDS = 24 * 60 * MILLISECONDS_PER_MINUTE;

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
            final long settledBefore = System.currentTimeMillis() - RETRY_AFTER_MILLISECONDS;
            final List<Resource> jobs = new ArrayList<>();
            jobsRoot.getChildren().forEach(jobs::add);
            // Sorted before anything is written, because sweeping or forgetting a job deletes it from the listing
            final List<Resource> lost = jobs.stream().filter(job -> isLost(job, deadline)).toList();
            final List<Resource> untaken =
                jobs.stream().filter(job -> isUntaken(job, deadline, settledBefore)).toList();
            final List<Resource> forgotten = jobs.stream().filter(job -> isForgotten(job, deadline)).toList();
            lost.forEach(job -> sweep(resolver, job));
            untaken.forEach(job -> this.outcomes.settleAgain(resolver, job));
            forgotten.forEach(job -> forget(resolver, job));
        } catch (final LoginException e) {
            LOGGER.error("Cannot access the parse jobs storage to sweep it: {}", e.getMessage(), e);
        }
    }

    /**
     * Whether a job has waited too long for its outcome.
     *
     * @param jobNode the job
     * @param deadline the moment before which an unfinished job is declared lost
     * @return {@code true} for a queued or active job waiting since before the deadline
     */
    private static boolean isLost(final Resource jobNode, final long deadline)
    {
        final Calendar since = waitingSince(jobNode.getValueMap());
        return new JobRecord(jobNode).isWaiting() && since != null && since.getTimeInMillis() < deadline;
    }

    /**
     * Whether a settled job with a target still waits for a handler to take its outcome. Only jobs settled at least
     * two minutes ago, so the request that settled them is over. A handler may refuse an outcome only for now, for
     * example when the file's upload is not committed yet, so the outcome is offered again on every sweep until it
     * is taken or forgotten.
     *
     * @param jobNode the job
     * @param deadline the moment before which a settled record is forgotten instead
     * @param settledBefore the moment the job must have settled before
     * @return {@code true} for a job to hand over again
     */
    private static boolean isUntaken(final Resource jobNode, final long deadline, final long settledBefore)
    {
        final ValueMap properties = jobNode.getValueMap();
        return new JobRecord(jobNode).isSettled() && properties.get(ParseJob.PN_TARGET, String.class) != null
            && isBetween(properties.get(ParseJob.PN_FINISHED, Calendar.class), deadline, settledBefore);
    }

    /** Whether a moment is at or after {@code from} and before {@code to}; {@code false} for a missing moment. */
    private static boolean isBetween(final Calendar when, final long from, final long to)
    {
        return when != null && when.getTimeInMillis() >= from && when.getTimeInMillis() < to;
    }

    /**
     * Whether nobody came back for a settled job.
     *
     * <p>A job queued with a {@code target} is handed to its handler and its record deleted straight away. A job
     * queued without one is nobody's to hand over, so its record is kept for polling, then dropped here with its
     * staging folder. So is the record of a job whose handler refused the outcome, which is offered again on
     * every sweep until then.</p>
     *
     * <p>The same age answers both: a settled record is worth keeping while somebody might still poll it, and
     * worth nothing afterwards.</p>
     *
     * @param jobNode the job
     * @param deadline the moment before which a settled job's record is no longer worth keeping
     * @return {@code true} for a job to forget
     */
    private static boolean isForgotten(final Resource jobNode, final long deadline)
    {
        final Calendar finished = jobNode.getValueMap().get(ParseJob.PN_FINISHED, Calendar.class);
        return new JobRecord(jobNode).isSettled() && finished != null && finished.getTimeInMillis() < deadline;
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
        if (!this.parseService.discardStaging(staged)) {
            if (!isFolderPastRetryLimit(jobNode)) {
                // Keep the record: it is the only thing that names the folder, so the next sweep tries again
                return;
            }
            reportFolderLeftBehind(jobNode);
        }
        // Read before the delete: a deleted node's properties can no longer be read
        final String target = jobNode.getValueMap().get(ParseJob.PN_TARGET, String.class);
        final String error = jobNode.getValueMap().get(ParseJob.PN_ERROR, String.class);
        try {
            resolver.delete(jobNode);
            resolver.commit();
            LOGGER.info("Parse job {} settled more than {} minutes ago; its record is dropped", jobId,
                this.maxAgeMinutes);
            // A stopped parse is meant to go untaken: the file stopped waiting for it
            if (target != null && !ParseOutcome.STOPPED_ERROR.equals(error)) {
                reportUntaken(jobId, target);
            }
        } catch (final PersistenceException e) {
            LOGGER.error("Cannot drop the record of parse job {}: {}", jobId, e.getMessage(), e);
            resolver.revert();
        }
    }

    /**
     * Reports that an outcome nobody took was dropped. A job with a target is deleted as soon as a handler takes it,
     * so one still here was usually refused on every sweep, and whatever waited on it may now wait for good.
     */
    private void reportUntaken(final String jobId, final String target)
    {
        LOGGER.warn("No handler took the outcome of parse job {} for {} within {} minutes; it is dropped", jobId,
            target, this.maxAgeMinutes);
        ErrorLogger.logProblem("A parse outcome was never taken in, so its file may stay waiting for it",
            ErrorContext.of(StaleParseJobSweeper.class, "forget").about(target).with("job", jobId));
    }

    /**
     * Whether to stop retrying a staging folder that cannot be removed. A worker lets go of a file within minutes,
     * so after a day something else holds it, and keeping the record would keep it forever.
     *
     * @param jobNode the settled job whose folder could not be removed
     * @return {@code true} when the job finished more than a day ago, so the record is dropped anyway
     */
    private static boolean isFolderPastRetryLimit(final Resource jobNode)
    {
        final Calendar finished = jobNode.getValueMap().get(ParseJob.PN_FINISHED, Calendar.getInstance());
        return finished.getTimeInMillis() < System.currentTimeMillis() - GIVE_UP_ON_FOLDER_MILLISECONDS;
    }

    /**
     * Reports a staging folder the sweep stops retrying. Nothing will name it once the record goes.
     *
     * @param jobNode the settled job whose folder could not be removed
     */
    private static void reportFolderLeftBehind(final Resource jobNode)
    {
        final String jobId = jobNode.getValueMap().get(ParseJob.PN_JOB_ID, jobNode.getName());
        final String staged = jobNode.getValueMap().get(ParseJob.PN_PATH, String.class);
        LOGGER.error("The staging folder of parse job {} still cannot be removed after a day; giving up on {}",
            jobId, staged);
        // Nothing will name the folder once the record goes, so only an administrator can clear it now
        ErrorLogger.logProblem("A parse staging folder cannot be removed",
            ErrorContext.of(StaleParseJobSweeper.class, "forget").with("job", jobId).with("folder", staged));
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
        final String staged = jobNode.getValueMap().get(ParseJob.PN_PATH, String.class);
        try {
            new JobRecord(jobNode).markFailed(String.format(LOST_MESSAGE, this.maxAgeMinutes));
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

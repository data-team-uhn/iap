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
package io.uhndata.iap.extraction.internal;

import java.util.Map;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.consumer.JobConsumer.JobResult;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;

/**
 * Runs a background reading of a submission: the steps every kind of {@link ReadingJob} shares.
 *
 * <p>A job is queued once the step that asked for it has committed ({@link ReadingQueue}), and decides whether
 * the reading can start: when a parse is still going, the job is dropped, since that parse queues its own job
 * when it ends. It then claims the reading on the submission, so two jobs never both pay a model to read the same
 * document.</p>
 *
 * <p>A job writes nothing itself. The claim and a give-up are events too ({@code readingStarted},
 * {@code readingFailed}), so the engine makes every change to the submission.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ReadingJobs.class)
public class ReadingJobs
{
    /** The job property naming the submission to read. */
    public static final String SUBMISSION_PROPERTY = "submission";

    /** The event that claims the reading for a job. */
    static final String READING_STARTED_EVENT = "readingStarted";

    /** The event that gives a reading up. */
    static final String READING_FAILED_EVENT = "readingFailed";

    /** What the submitter is told when the schema sets up nothing that can read the documents. */
    static final String NOT_SET_UP_MESSAGE = "Nothing is set up to read this request's documents";

    /** What the submitter is told when the reading failed for a reason an administrator must fix. */
    static final String COULD_NOT_READ_MESSAGE = "The document could not be read";

    private static final Logger LOGGER = LoggerFactory.getLogger(ReadingJobs.class);

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private WorkflowEngine engine;

    @Reference
    private ReadingRuns runs;

    /**
     * Runs one job: claims the reading and reads, or finds there is nothing for this job to do.
     *
     * @param job the Sling job; its id is written as the claim
     * @param reading what this kind of job does
     * @return {@code OK} when the reading ran, or {@code CANCEL} when there is nothing for this job to do or the
     *     reading failed
     */
    JobResult run(final Job job, final ReadingJob reading)
    {
        final String path = job.getProperty(SUBMISSION_PROPERTY, String.class);
        if (path == null) {
            LOGGER.warn("Dropping a reading job that names no submission: topic={}", job.getTopic());
            return JobResult.CANCEL;
        }
        try (ResourceResolver resolver = this.resolverFactory.getServiceResourceResolver(
            Map.of(ResourceResolverFactory.SUBSERVICE, ParseCompletionHandler.SUBSERVICE_NAME))) {
            this.runs.begin(path);
            try {
                return runReading(resolver, job, reading, path);
            } finally {
                // Also clears a stop that came after the last model call: left set, it would stop the next job
                // this pooled thread runs
                this.runs.end();
            }
        } catch (final ReadingStoppedException e) {
            Thread.interrupted();
            LOGGER.info("Reading of {} was stopped", path);
            giveUp(path, job.getId(), ExtractionStatus.STOPPED_MESSAGE);
            return JobResult.CANCEL;
        } catch (final LoginException e) {
            LOGGER.error("Cannot read submissions to read {}: {}", path, e.getMessage(), e);
            // The service user is missing: no reading can run until a deployment fault is fixed
            ErrorLogger.logError(e, ErrorContext.of(reading.getClass(), "openSession").about(path));
            return JobResult.CANCEL;
        } catch (final WorkflowException | RuntimeException e) {
            // RuntimeException too, because a malformed definition throws one.
            // Not left to Sling to retry: giveUp has already let the claim go, so a retry would claim it again
            // and pay the model twice.
            LOGGER.error("Reading {} failed: {}", path, e.getMessage(), e);
            // The submitter only sees that the document could not be read; the cause is for an administrator
            ErrorLogger.logError(e, ErrorContext.of(reading.getClass(), "read").about(path));
            return JobResult.CANCEL;
        }
    }

    /**
     * Reads the submission, if this job is the one to do it.
     *
     * @param resolver the session to read through; it sees only committed data
     * @param job the job; its id is written as the claim
     * @param reading what this kind of job does
     * @param path the submission to read
     * @return {@code OK} when the reading ran, or {@code CANCEL} when there is nothing for this job to do
     * @throws WorkflowException when the reading itself fails
     */
    private JobResult runReading(final ResourceResolver resolver, final Job job, final ReadingJob reading,
        final String path) throws WorkflowException
    {
        final Resource submission = resolver.getResource(path);
        if (submission == null) {
            LOGGER.warn("Dropping the reading job for {}: it is gone", path);
            return JobResult.CANCEL;
        }
        if (!claimReadingIfReady(job, reading, submission)) {
            return JobResult.CANCEL;
        }
        // Times the whole reading, all model calls included, for the log line below
        final long startedAt = System.nanoTime();
        LOGGER.info("Reading run started: submission={} topic={}", path, job.getTopic());
        try {
            reading.read(submission, job.getId());
        } catch (final ReadingStoppedException e) {
            throw e;
        } catch (final WorkflowException | RuntimeException e) {
            if (ReadingRuns.isCausedByStop(e)) {
                Thread.interrupted();
                throw new ReadingStoppedException();
            }
            // The engine reverted the walk, but not the claim committed before it
            giveUp(submission, job.getId(), COULD_NOT_READ_MESSAGE, true);
            throw e;
        }
        LOGGER.info("Reading run done: submission={} topic={} status={} ms={}", path, job.getTopic(),
            submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class),
            (System.nanoTime() - startedAt) / 1_000_000L);
        return JobResult.OK;
    }

    /**
     * Claims the reading for this job, when there is something for it to read now. A reading that cannot start is
     * given up, with the reason.
     *
     * @param job the job; its id is written as the claim
     * @param reading what this kind of job does
     * @param submission the submission to read
     * @return {@code true} when this job now holds the reading
     * @throws WorkflowException when the engine refuses the claim for a reason other than another job having it
     */
    private boolean claimReadingIfReady(final Job job, final ReadingJob reading, final Resource submission)
        throws WorkflowException
    {
        final String reason;
        try {
            final Submission model = SubmissionFiles.getSubmission(submission);
            if (isParseStillRunning(submission, model)) {
                return false;
            }
            if (reading.isAlreadyRead(model)) {
                LOGGER.info("Nothing left to read: submission={} topic={}", submission.getPath(), job.getTopic());
                return false;
            }
            reason = findReasonNotToStart(reading, submission, model);
        } catch (final RuntimeException e) {
            giveUp(submission, job.getId(), COULD_NOT_READ_MESSAGE, false);
            throw e;
        }
        if (reason != null) {
            giveUp(submission, job.getId(), reason, false);
            return false;
        }
        return claimReading(submission, job.getId());
    }

    /**
     * Why a reading cannot start, if it cannot.
     *
     * @param reading what this kind of job does
     * @param submission the submission to read
     * @param model the submission as a model
     * @return the message for the submitter, or {@code null} when the schema version can be read and this kind of
     *     job finds nothing missing
     */
    private static String findReasonNotToStart(final ReadingJob reading, final Resource submission,
        final Submission model)
    {
        if (model.findSchemaVersion() == null) {
            LOGGER.error("The extraction service user cannot read the schema version of {}", submission.getPath());
            ErrorLogger.logProblem("The extraction service user cannot read a submission's schema version",
                ErrorContext.of(reading.getClass(), "findSchemaVersion").about(submission));
            return COULD_NOT_READ_MESSAGE;
        }
        return reading.findReasonNotToStart(model);
    }

    /**
     * Checks whether a parse of the submission is still going. That parse queues its own job when it ends, so this
     * one is not needed. Retried instead, it could outlive the reading it was queued for and take over the next one.
     *
     * @param submission the submission to read
     * @param model the submission as a model
     * @return {@code true} when the job should be dropped
     */
    private static boolean isParseStillRunning(final Resource submission, final Submission model)
    {
        if (SubmissionFiles.isEveryParseSettled(model)) {
            return false;
        }
        LOGGER.info("A parse of {} is still going; its own job reads it when it ends", submission.getPath());
        return true;
    }

    /**
     * Claims the reading of a submission for this job through the {@code readingStarted} system workflow
     * ({@link ClaimReadingHandler}).
     *
     * @param submission the submission to read
     * @param jobId the job's id, written as the claim
     * @return {@code true} when this job has the reading, {@code false} when another job has it
     * @throws WorkflowException when the engine refuses the claim for another reason
     */
    private boolean claimReading(final Resource submission, final String jobId) throws WorkflowException
    {
        try {
            this.engine.receiveEvent(submission,
                new WorkflowEvent(READING_STARTED_EVENT, Map.of(ReadingEvents.JOB_KEY, jobId)));
            return true;
        } catch (final InvalidStateException e) {
            // Another job has the claim, or claimed it at the same time and committed first
            LOGGER.debug("The reading of {} was claimed by another job: {}", submission.getPath(), e.getMessage());
            return false;
        } catch (final WorkflowException | RuntimeException e) {
            giveUp(submission, jobId, COULD_NOT_READ_MESSAGE, false);
            throw e;
        }
    }

    /**
     * Sends what a step of the reading found to the submission's system workflows, which save it.
     *
     * @param submission the submission being read
     * @param outcome the event carrying what the step found, and the job's id
     * @return {@code false} when the reading is no longer this job's, so the outcome was dropped
     * @throws WorkflowException when the engine refuses the outcome for another reason
     */
    boolean sendOutcome(final Resource submission, final WorkflowEvent outcome) throws WorkflowException
    {
        try {
            this.engine.receiveEvent(submission, outcome);
            return true;
        } catch (final InvalidStateException e) {
            // A Stop or a Read again took the reading while this job worked on it
            LOGGER.info("Dropping {} on {}: {}", outcome.getName(), submission.getPath(), e.getMessage());
            return false;
        }
    }

    /**
     * Gives the reading up through the {@code readingFailed} system workflow ({@link GiveUpReadingHandler}): marks
     * it failed on the submission and releases the claim. A job that stops before its reading ends gives it up,
     * since {@code running} was committed before the job ran and nothing else would end it. A failure here is only
     * logged, since nothing else is left to help the submission.
     *
     * @param submission the submission whose reading failed
     * @param jobId this job's id
     * @param reason the message shown to the person looking at the submission
     * @param claimed whether this job had claimed the reading
     */
    void giveUp(final Resource submission, final String jobId, final String reason, final boolean claimed)
    {
        try {
            this.engine.receiveEvent(submission, new WorkflowEvent(READING_FAILED_EVENT, Map.of(
                ReadingEvents.JOB_KEY, jobId, ReadingEvents.MESSAGE_KEY, reason,
                GiveUpReadingHandler.CLAIMED_KEY, claimed)));
        } catch (final WorkflowException | RuntimeException e) {
            LOGGER.error("Could not record that the reading of {} failed: {}", submission.getPath(),
                e.getMessage(), e);
            // The submission now spins forever, and nothing else will say why
            ErrorLogger.logError(e, ErrorContext.of(ReadingJobs.class, "giveUp").about(submission));
        }
    }

    /**
     * Gives up a reading this job claimed through a new session, for when the job's own session is already closed.
     *
     * @param path the submission whose reading failed
     * @param jobId this job's id
     * @param reason the message shown to the person looking at the submission
     */
    private void giveUp(final String path, final String jobId, final String reason)
    {
        try (ResourceResolver resolver = this.resolverFactory.getServiceResourceResolver(
            Map.of(ResourceResolverFactory.SUBSERVICE, ParseCompletionHandler.SUBSERVICE_NAME))) {
            final Resource submission = resolver.getResource(path);
            if (submission != null) {
                giveUp(submission, jobId, reason, true);
            }
        } catch (final LoginException e) {
            LOGGER.error("Cannot record that the reading of {} was stopped: {}", path, e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(ReadingJobs.class, "recordStop").about(path));
        }
    }
}

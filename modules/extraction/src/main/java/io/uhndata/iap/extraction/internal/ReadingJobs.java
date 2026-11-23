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

import java.util.HashMap;
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
            giveUpOwnReadingInNewSession(path, job.getId(), ExtractionStatus.STOPPED_MESSAGE);
            return JobResult.CANCEL;
        } catch (final LoginException e) {
            LOGGER.error("Cannot read submissions to read {}: {}", path, e.getMessage(), e);
            // The service user is missing: no reading can run until a deployment fault is fixed
            ErrorLogger.logError(e, ErrorContext.of(reading.getClass(), "openSession").about(path));
            return JobResult.CANCEL;
        } catch (final WorkflowException | RuntimeException e) {
            // RuntimeException too, because a malformed definition throws one.
            // Not left to Sling to retry: a failed reading is final. If giving it up failed too, the claim still
            // holds this job's id, so a retry would read again and pay the model twice.
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
        } catch (final ReadingEndedException e) {
            LOGGER.info("Reading run ended early: submission={} topic={}: {}", path, job.getTopic(), e.getMessage());
            return JobResult.OK;
        } catch (final ReadingStoppedException e) {
            throw e;
        } catch (final WorkflowException | RuntimeException e) {
            if (ReadingRuns.isCausedByStop(e)) {
                Thread.interrupted();
                throw new ReadingStoppedException();
            }
            // The engine reverted the walk, but not the claim committed before it
            giveUpOwnReadingUninterrupted(submission, job.getId());
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
            // Still waiting when a later reading started, such as the one Next starts after the checks: that
            // reading is not this job's to read, give up or end
            if (!ReadingEvents.isCurrentReading(job.getProperty(ReadingEvents.READING_ID_KEY, String.class),
                submission.getValueMap())) {
                LOGGER.info("The job was queued for an earlier reading: submission={} topic={}",
                    submission.getPath(), job.getTopic());
                return false;
            }
            // The job that finds every parse settled reads, and a later one finds the reading ended. A Stop ends
            // it too. One still running is this job's to end, even when nothing is left to read.
            if (!ExtractionStatus.isRunning(model)) {
                LOGGER.info("The reading has ended: submission={} topic={}", submission.getPath(), job.getTopic());
                return false;
            }
            reason = findReasonNotToStart(reading, submission, model);
        } catch (final RuntimeException e) {
            giveUpBeforeClaiming(submission, job, COULD_NOT_READ_MESSAGE);
            throw e;
        }
        if (reason != null) {
            giveUpBeforeClaiming(submission, job, reason);
            return false;
        }
        return claimReading(submission, job);
    }

    /**
     * The payload of an event a job sends before it holds the reading: its id, and the reading it was queued for,
     * which the step checks again in its own transaction.
     */
    private static Map<String, Object> createUnclaimedPayload(final Job job)
    {
        final Map<String, Object> payload = new HashMap<>();
        payload.put(ReadingEvents.JOB_KEY, job.getId());
        final String readingId = job.getProperty(ReadingEvents.READING_ID_KEY, String.class);
        if (readingId != null) {
            payload.put(ReadingEvents.READING_ID_KEY, readingId);
        }
        return payload;
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
     * one is not needed.
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
     * @param job the job; its id is written as the claim
     * @return {@code true} when this job has the reading, {@code false} when another job has it or a later
     *     reading started
     * @throws WorkflowException when the engine refuses the claim for another reason
     */
    private boolean claimReading(final Resource submission, final Job job) throws WorkflowException
    {
        try {
            this.engine.receiveEvent(submission, new WorkflowEvent(READING_STARTED_EVENT, createUnclaimedPayload(job)));
            return true;
        } catch (final InvalidStateException e) {
            // Another job has the claim, or claimed it at the same time and committed first, or a later reading
            // started since the check
            LOGGER.debug("The reading of {} is not this job's: {}", submission.getPath(), e.getMessage());
            return false;
        } catch (final WorkflowException | RuntimeException e) {
            giveUpBeforeClaiming(submission, job, COULD_NOT_READ_MESSAGE);
            throw e;
        }
    }

    /**
     * Sends what a step of the reading found to the submission's system workflows, which save it.
     *
     * @param submission the submission being read
     * @param outcome the event carrying what the step found, and the job's id
     * @throws ReadingEndedException when the reading is no longer this job's, so the outcome was dropped
     * @throws WorkflowException when the engine refuses the outcome for another reason
     */
    void sendOutcome(final Resource submission, final WorkflowEvent outcome)
        throws ReadingEndedException, WorkflowException
    {
        try {
            this.engine.receiveEvent(submission, outcome);
        } catch (final InvalidStateException e) {
            // A Stop or a Read again took the reading while this job worked on it
            LOGGER.info("Dropping {} on {}: {}", outcome.getName(), submission.getPath(), e.getMessage());
            throw new ReadingEndedException(e);
        }
    }

    /**
     * Gives up the reading this job claimed.
     *
     * @param submission the submission whose reading failed
     * @param jobId this job's id, which the claim holds
     * @param reason the message shown to the person looking at the submission
     */
    void giveUpOwnReading(final Resource submission, final String jobId, final String reason)
    {
        sendReadingFailed(submission, Map.of(ReadingEvents.JOB_KEY, jobId, ReadingEvents.MESSAGE_KEY, reason,
            GiveUpReadingHandler.CLAIMED_KEY, Boolean.TRUE));
    }

    /**
     * Gives up the reading this job claimed, with the thread's interrupt held back meanwhile. An interrupt from
     * elsewhere, such as a shutdown, would make the engine's retry and the repository fail, and the reading would
     * stay {@code running}. The interrupt is set again after.
     *
     * @param submission the submission whose reading failed
     * @param jobId this job's id, which the claim holds
     */
    private void giveUpOwnReadingUninterrupted(final Resource submission, final String jobId)
    {
        final boolean interrupted = Thread.interrupted();
        try {
            giveUpOwnReading(submission, jobId, COULD_NOT_READ_MESSAGE);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Gives up a reading this job has not claimed yet.
     *
     * @param submission the submission whose reading failed
     * @param job this job
     * @param reason the message shown to the person looking at the submission
     */
    private void giveUpBeforeClaiming(final Resource submission, final Job job, final String reason)
    {
        final Map<String, Object> payload = createUnclaimedPayload(job);
        payload.put(ReadingEvents.MESSAGE_KEY, reason);
        payload.put(GiveUpReadingHandler.CLAIMED_KEY, Boolean.FALSE);
        sendReadingFailed(submission, payload);
    }

    /**
     * Gives the reading up through the {@code readingFailed} system workflow ({@link GiveUpReadingHandler}): marks
     * it failed on the submission and releases the claim. A job that stops before its reading ends gives it up,
     * since {@code running} was committed before the job ran and nothing else would end it. A failure here is only
     * logged, since nothing else is left to help the submission.
     */
    private void sendReadingFailed(final Resource submission, final Map<String, Object> payload)
    {
        try {
            this.engine.receiveEvent(submission, new WorkflowEvent(READING_FAILED_EVENT, payload));
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
    private void giveUpOwnReadingInNewSession(final String path, final String jobId, final String reason)
    {
        try (ResourceResolver resolver = this.resolverFactory.getServiceResourceResolver(
            Map.of(ResourceResolverFactory.SUBSERVICE, ParseCompletionHandler.SUBSERVICE_NAME))) {
            final Resource submission = resolver.getResource(path);
            if (submission != null) {
                giveUpOwnReading(submission, jobId, reason);
            }
        } catch (final LoginException e) {
            LOGGER.error("Cannot record that the reading of {} was stopped: {}", path, e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(ReadingJobs.class, "recordStop").about(path));
        }
    }

    /**
     * The submission as the engine last saved it, while its reading still runs. Each step is saved through the
     * engine's session, which the job's own session sees only after a refresh.
     *
     * @param submission the submission, read through the job's session
     * @return the submission, or {@code null} when it is gone or its reading ended, such as by a Stop
     */
    Resource findStillRunning(final Resource submission)
    {
        final ResourceResolver resolver = submission.getResourceResolver();
        resolver.refresh();
        final Resource current = resolver.getResource(submission.getPath());
        return current != null && ExtractionStatus.isRunning(current) ? current : null;
    }
}

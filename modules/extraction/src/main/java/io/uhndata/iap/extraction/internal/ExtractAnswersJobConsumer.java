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
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.consumer.JobConsumer;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;

/**
 * Runs the reading of a submission's answers as a background job. It fires the {@code extractAnswers} event on the
 * submission, and that system workflow starts the reading workflow the schema version names.
 *
 * <p>Every finished parse queues one of these jobs, and {@code readAgain} queues one too, so the job decides
 * whether the reading can start. It sees only committed data: when a parse still looks unfinished, the job fails
 * so Sling runs it again shortly. It then claims the reading on the submission, so two jobs never both pay a
 * model to read the same document.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = JobConsumer.class,
    property = { JobConsumer.PROPERTY_TOPICS + "=" + ExtractAnswersJobConsumer.JOB_TOPIC })
public class ExtractAnswersJobConsumer implements JobConsumer
{
    /** The job topic. */
    public static final String JOB_TOPIC = "iap/extraction/extract";

    /** The job property naming the submission to read. */
    public static final String SUBMISSION_PROPERTY = "submission";

    /**
     * The job property set when the submitter asked to read again. The job is queued before that request commits,
     * so it waits until the submission shows {@code running}; before that it would still see the old claim.
     */
    public static final String READ_AGAIN = "readAgain";

    /** The event that starts the reading. */
    static final String EVENT_NAME = "extractAnswers";

    /** What the submitter is told when the schema names no reading workflow that exists. */
    static final String NOTHING_READS = "Nothing is set up to read this request's documents";

    /** What the submitter is told when the reading failed for a reason an administrator must fix. */
    static final String COULD_NOT_READ = "The document could not be read";

    private static final Logger LOGGER = LoggerFactory.getLogger(ExtractAnswersJobConsumer.class);

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private WorkflowEngine engine;

    @Reference
    private ReadingRuns runs;

    @Override
    public JobResult process(final Job job)
    {
        final String path = job.getProperty(SUBMISSION_PROPERTY, String.class);
        if (path == null) {
            LOGGER.warn("Dropping an extraction job that names no submission");
            return JobResult.CANCEL;
        }
        try (ResourceResolver resolver = this.resolverFactory.getServiceResourceResolver(
            Map.of(ResourceResolverFactory.SUBSERVICE, ParseCompletionHandler.SUBSERVICE_NAME))) {
            this.runs.begin(path);
            try {
                return runReading(resolver, job, path);
            } finally {
                // Also clears a stop that came after the last model call: left set, it would stop the next job
                // this pooled thread runs
                this.runs.end(path);
            }
        } catch (final ReadingRuns.Stopped e) {
            Thread.interrupted();
            LOGGER.info("Reading of {} was stopped", path);
            giveUp(path, ExtractionStatus.STOPPED_MESSAGE);
            return JobResult.CANCEL;
        } catch (final LoginException e) {
            LOGGER.error("Cannot read submissions to extract answers from {}: {}", path, e.getMessage(), e);
            // The service user is missing: no reading can run until a deployment fault is fixed
            ErrorLogger.logError(e, ErrorContext.of(ExtractAnswersJobConsumer.class, "openSession").about(path));
            return JobResult.CANCEL;
        } catch (final WorkflowException | RuntimeException e) {
            // RuntimeException too, because a malformed definition throws one.
            // Not left to Sling to retry: giveUp has already let the claim go, so a retry would claim it again
            // and pay the model twice.
            LOGGER.error("Extracting answers from {} failed: {}", path, e.getMessage(), e);
            // The submitter only sees that the document could not be read; the cause is for an administrator
            ErrorLogger.logError(e, ErrorContext.of(ExtractAnswersJobConsumer.class, "read").about(path));
            return JobResult.CANCEL;
        }
    }

    /**
     * Reads the submission, if this job is the one to do it.
     *
     * @param resolver the session to read and claim through; it sees only committed data
     * @param job the job; its id is written as the claim
     * @param path the submission to read
     * @return {@code OK} when the reading ran, {@code FAILED} to have Sling run the job again later, or
     *         {@code CANCEL} when there is nothing for this job to do
     * @throws WorkflowException when the reading itself fails
     */
    private JobResult runReading(final ResourceResolver resolver, final Job job, final String path)
        throws WorkflowException
    {
        final Resource submission = resolver.getResource(path);
        if (submission == null) {
            LOGGER.warn("Dropping the extraction job for {}: it is gone", path);
            return JobResult.CANCEL;
        }
        if (isTooEarly(job, submission)) {
            return JobResult.FAILED;
        }
        if (!checkOut(submission) || !canStart(resolver, submission)) {
            return JobResult.CANCEL;
        }
        if (!claimReading(resolver, submission, job.getId())) {
            LOGGER.debug("The reading of {} is already somebody else's", path);
            return JobResult.CANCEL;
        }
        // Times the whole reading, all model calls included, for the log line below
        final long startedAt = System.nanoTime();
        LOGGER.info("Reading run started: submission={}", path);
        try {
            this.engine.receiveEvent(submission, new WorkflowEvent(EVENT_NAME, Map.of()));
        } catch (final ReadingRuns.Stopped e) {
            throw e;
        } catch (final WorkflowException | RuntimeException e) {
            if (ReadingRuns.isStopped(e)) {
                Thread.interrupted();
                throw new ReadingRuns.Stopped();
            }
            // The engine reverted the walk, but `running` and this job's claim were committed before it. Left as
            // they are, nothing could ever read the submission again and its spinner would never stop.
            giveUp(resolver, submission, COULD_NOT_READ);
            throw e;
        }
        LOGGER.info("Reading run done: submission={} status={} ms={}", path,
            submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class),
            (System.nanoTime() - startedAt) / 1_000_000L);
        return JobResult.OK;
    }

    /**
     * Checks the submission out if it is checked in, so this job and the reading can write to it.
     *
     * @param submission the submission to read
     * @return {@code false} when it cannot be checked out, which this job cannot fix
     */
    private static boolean checkOut(final Resource submission)
    {
        try {
            ExtractionStatus.checkOut(submission);
            return true;
        } catch (final PersistenceException e) {
            LOGGER.error("Cannot check out {} to read it: {}", submission.getPath(), e.getMessage(), e);
            // The service user lacks the right to check it out: a deployment fault
            ErrorLogger.logError(e, ErrorContext.of(ExtractAnswersJobConsumer.class, "checkOut").about(submission));
            return false;
        }
    }

    /**
     * Checks that a reading can start, and gives the reading up when it cannot. Fired anyway, the event would start
     * nothing and leave {@code running} on the submission for good.
     *
     * @param resolver this job's session
     * @param submission the submission to read
     * @return {@code true} when the schema version can be read and names a reading workflow that exists
     */
    private static boolean canStart(final ResourceResolver resolver, final Resource submission)
    {
        final String path = submission.getPath();
        if (SubmissionFiles.getSubmission(submission).findSchemaVersion() == null) {
            LOGGER.error("The extraction service user cannot read the schema version of {}", path);
            ErrorLogger.logProblem("The extraction service user cannot read a submission's schema version",
                ErrorContext.of(ExtractAnswersJobConsumer.class, "findSchemaVersion").about(path));
            giveUp(resolver, submission, COULD_NOT_READ);
            return false;
        }
        if (!SubmissionFiles.hasReadingWorkflow(SubmissionFiles.getSubmission(submission), resolver)) {
            LOGGER.warn("The schema of {} names no active reading workflow that exists", path);
            // A schema set up to parse its documents but with nothing to read them
            ErrorLogger.logProblem("The schema version names no active reading workflow that exists",
                ErrorContext.of(ExtractAnswersJobConsumer.class, "findReadingWorkflow").about(path));
            giveUp(resolver, submission, NOTHING_READS);
            return false;
        }
        return true;
    }

    /**
     * Checks whether the submission is not ready for this job yet, so Sling should run it again in a moment.
     *
     * @param job the job
     * @param submission the submission to read
     * @return {@code true} when the job should wait
     */
    private static boolean isTooEarly(final Job job, final Resource submission)
    {
        if (Boolean.TRUE.equals(job.getProperty(READ_AGAIN, Boolean.class)) && !ExtractionStatus.STATUS_RUNNING.equals(
            submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class))) {
            // The read again request that queued this job has not committed yet, so the old claim is still there
            LOGGER.info("The request to read {} again is not committed yet; looking again shortly",
                submission.getPath());
            return true;
        }
        if (!SubmissionFiles.isEveryParseSettled(SubmissionFiles.getSubmission(submission))) {
            // Retried, not dropped: this job is queued before its parse is committed, so that parse can look
            // unfinished. A parse that really is still going queues its own job when it ends.
            LOGGER.info("A parse of {} is still going, or has not been committed yet; looking again shortly",
                submission.getPath());
            return true;
        }
        return false;
    }

    /**
     * Gives the reading up: marks it failed on the submission and releases the claim, in a commit of its own.
     *
     * <p>The failed status stops the spinner in the view, and the released claim lets a later parse or a read
     * again request start a new reading.</p>
     *
     * @param resolver this job's session
     * @param submission the submission whose reading failed
     * @param reason the message shown to the person looking at the submission
     */
    private static void giveUp(final ResourceResolver resolver, final Resource submission, final String reason)
    {
        resolver.refresh();
        final ModifiableValueMap properties = submission.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            LOGGER.error("The reading of {} failed and its claim cannot be taken back down", submission.getPath());
            // The submission now spins forever; only missing rights for the service user can cause this
            ErrorLogger.logProblem("The extraction service user cannot write the submission",
                ErrorContext.of(ExtractAnswersJobConsumer.class, "giveUp").about(submission));
            return;
        }
        ExtractionStatus.releaseClaim(properties);
        try {
            ExtractionStatus.record(submission, ExtractionStatus.STATUS_FAILED, reason);
            resolver.commit();
        } catch (final PersistenceException e) {
            LOGGER.error("Could not record that the reading of {} failed: {}", submission.getPath(),
                e.getMessage(), e);
            // The submission now spins forever, and nothing else will say why
            ErrorLogger.logError(e, ErrorContext.of(ExtractAnswersJobConsumer.class, "giveUp").about(submission));
            resolver.revert();
        }
    }

    /**
     * Gives the reading up through a new session, for when the job's own session is already closed.
     *
     * @param path the submission whose reading failed
     * @param reason the message shown to the person looking at the submission
     */
    private void giveUp(final String path, final String reason)
    {
        try (ResourceResolver resolver = this.resolverFactory.getServiceResourceResolver(
            Map.of(ResourceResolverFactory.SUBSERVICE, ParseCompletionHandler.SUBSERVICE_NAME))) {
            final Resource submission = resolver.getResource(path);
            if (submission != null) {
                giveUp(resolver, submission, reason);
            }
        } catch (final LoginException e) {
            LOGGER.error("Cannot record that the reading of {} was stopped: {}", path, e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(ExtractAnswersJobConsumer.class, "recordStop").about(path));
        }
    }

    /**
     * Claims the reading of a submission for this job, unless another job already has it.
     *
     * <p>The claim is written and committed on its own. When two jobs both find it unset, the second commit
     * conflicts with the first and the repository refuses it, so only one job pays a model to read the
     * document.</p>
     *
     * <p>A claim that already holds this job's id still counts as this job's: Sling runs a job again, with the
     * same id, when the instance stopped before the job finished.</p>
     *
     * @param resolver the session to claim through
     * @param submission the submission to read
     * @param jobId the job's id, written as the claim
     * @return {@code true} when this job has the reading
     */
    private static boolean claimReading(final ResourceResolver resolver, final Resource submission,
        final String jobId)
    {
        final ModifiableValueMap properties = submission.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            LOGGER.warn("Not allowed to claim the reading of {}", submission.getPath());
            // No reading can ever start: the service user's rights are a deployment fault
            ErrorLogger.logProblem("The extraction service user cannot write the submission",
                ErrorContext.of(ExtractAnswersJobConsumer.class, "claim").about(submission));
            return false;
        }
        if (properties.get(ExtractionStatus.READING_CLAIMED, Boolean.FALSE).booleanValue()) {
            return jobId.equals(properties.get(ExtractionStatus.READING_CLAIMED_BY, String.class));
        }
        properties.put(ExtractionStatus.READING_CLAIMED, Boolean.TRUE);
        properties.put(ExtractionStatus.READING_CLAIMED_BY, jobId);
        try {
            resolver.commit();
            return true;
        } catch (final PersistenceException e) {
            LOGGER.debug("The reading of {} was claimed by another job: {}", submission.getPath(), e.getMessage());
            resolver.revert();
            return false;
        }
    }
}

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

import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;

/**
 * Runs the reading of a submission's answers in the background: fires the {@code extractAnswers} event on the
 * submission, and the system workflow catching it does the rest - the schema's reading workflow - in one commit.
 * The same event can be fired from the submission itself, {@code POST <submission>.extractAnswers.json}, to read
 * it again.
 *
 * <p>Every parse that lands queues one of these, so this is also where it is decided whose turn it is. Its own
 * session sees committed state, which is what makes the question answerable: a job that finds a parse still
 * going comes back to it shortly, since what it sees may be a parse that has landed but not committed. The
 * reading is then claimed on the submission, so two jobs that both find everything settled cannot both pay a
 * model to read the same document.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = JobConsumer.class,
    property = { JobConsumer.PROPERTY_TOPICS + "=" + ExtractAnswersJobConsumer.TOPIC })
public class ExtractAnswersJobConsumer implements JobConsumer
{
    /** The job topic. */
    public static final String TOPIC = "iap/extraction/extract";

    /** The job property naming the submission to read. */
    public static final String SUBMISSION = "submission";

    /** The event the reading is. */
    static final String EVENT = "extractAnswers";

    private static final Logger LOGGER = LoggerFactory.getLogger(ExtractAnswersJobConsumer.class);

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private WorkflowEngine engine;

    @Override
    public JobResult process(final Job job)
    {
        final String path = job.getProperty(SUBMISSION, String.class);
        if (path == null) {
            LOGGER.warn("Dropping an extraction job that names no submission");
            return JobResult.CANCEL;
        }
        try (ResourceResolver resolver = this.resolverFactory.getServiceResourceResolver(
            Map.of(ResourceResolverFactory.SUBSERVICE, ParseCompletionHandler.SUBSERVICE))) {
            return runReading(resolver, path);
        } catch (final LoginException e) {
            LOGGER.error("Cannot read submissions to extract answers from {}: {}", path, e.getMessage(), e);
            return JobResult.CANCEL;
        } catch (final WorkflowException | RuntimeException e) {
            // Not retried: a second attempt would ask the model the same questions again, at the same cost.
            // RuntimeException too, because the walk asserts its way through a definition and a malformed one
            // throws rather than returning - and letting that out would leave the job to Sling's own retry, which
            // meets the claim below and cancels anyway, with nothing said about why.
            LOGGER.error("Extracting answers from {} failed: {}", path, e.getMessage(), e);
            return JobResult.CANCEL;
        }
    }

    /**
     * Read a submission, if this job is the one to do it.
     *
     * @param resolver the session to read and claim through, which sees committed state
     * @param path the submission to read
     * @return what became of the job
     * @throws WorkflowException when the reading itself fails
     */
    private JobResult runReading(final ResourceResolver resolver, final String path) throws WorkflowException
    {
        final Resource submission = resolver.getResource(path);
        if (submission == null) {
            LOGGER.warn("Dropping the extraction job for {}: it is gone", path);
            return JobResult.CANCEL;
        }
        if (!SubmissionFiles.allParsesSettled(SubmissionFiles.submission(submission))) {
            // Looked at again in a moment rather than dropped, because "still going" is not always true. This
            // job is queued from inside the walk that records the parse, and the JobManager commits the job
            // node in a session of its own - so a job that starts quickly enough reads the very parse it was
            // queued for as unfinished. Dropping on that leaves a submission at `running` with nothing left to
            // move it on and a spinner that never stops, which is exactly what happens when the last two
            // parses of a submission land seconds apart.
            //
            // A parse that really is still going fails this again until Sling gives up on the job, by which
            // time the one its own landing queues has taken the reading.
            LOGGER.info("A parse of {} is still going, or has not been committed yet; looking again shortly",
                path);
            return JobResult.FAILED;
        }
        if (!claimReading(resolver, submission)) {
            LOGGER.debug("The reading of {} is already somebody else's", path);
            return JobResult.CANCEL;
        }
        // Brackets every model call a reading makes, so one line says what the whole of it cost
        final long startedAt = System.nanoTime();
        LOGGER.info("Reading run started: submission={}", path);
        try {
            this.engine.receiveEvent(submission, new WorkflowEvent(EVENT, Map.of()));
        } catch (final WorkflowException | RuntimeException e) {
            // The engine reverted the whole walk, so the `running` the parse step committed is still there and
            // this job still holds the claim. Left alone that is a submission nothing can ever read again and a
            // spinner that never stops, which is the one outcome this pipeline treats as worse than a bad answer.
            giveUp(resolver, submission, "The document could not be read");
            throw e;
        }
        LOGGER.info("Reading run done: submission={} status={} ms={}", path,
            submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class),
            (System.nanoTime() - startedAt) / 1_000_000L);
        return JobResult.OK;
    }

    /**
     * Give the reading up: say so on the submission and take the claim back down, in a commit of this job's own.
     *
     * <p>The engine's session has been reverted by the time this runs, so nothing here rides on it. What it
     * writes has to stand on its own: a status the view can stop spinning on, and a claim let go so a later
     * parse - or somebody asking for the reading again - can take it.</p>
     *
     * @param resolver this job's session
     * @param submission the submission whose reading failed
     * @param reason what to tell the person looking at it
     */
    private static void giveUp(final ResourceResolver resolver, final Resource submission, final String reason)
    {
        resolver.refresh();
        final ModifiableValueMap properties = submission.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            LOGGER.error("The reading of {} failed and its claim cannot be taken back down", submission.getPath());
            return;
        }
        properties.put(ExtractionStatus.PROPERTY, ExtractionStatus.FAILED);
        properties.put(ExtractionStatus.MESSAGE, reason);
        properties.remove(ExtractionStatus.READING_CLAIMED);
        try {
            resolver.commit();
        } catch (final PersistenceException e) {
            LOGGER.error("Could not record that the reading of {} failed: {}", submission.getPath(),
                e.getMessage(), e);
            resolver.revert();
        }
    }

    /**
     * Take the reading of a submission, if it is still going.
     *
     * <p>The claim is a property written and committed on its own. Two jobs that both got this far have both
     * read it unset, and the second one's commit meets the first one's write, which the repository refuses - so
     * the loser hears about it here rather than by paying a model to read a document twice.</p>
     *
     * @param resolver the session to claim through
     * @param submission the submission to read
     * @return {@code true} when this job has the reading
     */
    private static boolean claimReading(final ResourceResolver resolver, final Resource submission)
    {
        final ModifiableValueMap properties = submission.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            LOGGER.warn("Not allowed to claim the reading of {}", submission.getPath());
            return false;
        }
        if (properties.get(ExtractionStatus.READING_CLAIMED, Boolean.FALSE).booleanValue()) {
            return false;
        }
        properties.put(ExtractionStatus.READING_CLAIMED, Boolean.TRUE);
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

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
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.consumer.JobConsumer;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.schemas.models.ClassificationRequirement;
import io.uhndata.iap.schemas.models.DecisionRequirement;
import io.uhndata.iap.schemas.models.ValidationRequirement;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;

/**
 * The background job that checks a submission's uploads, the first steps of the intake. For each document, in the
 * order of the schema's document requirements, it runs the document's validation, if it has one, then its
 * categorisation, if it has one ({@link DecisionChain}), so both calls share the document's prompt cache
 * ({@link ExtractionPrompt}).
 *
 * <p>A validation is asked through {@link DocumentValidator}, a categorisation through {@link DocumentCategoriser}.
 * A validation's {@code no} ends the reading, so nothing after it is asked. Once every decision is made, the job
 * fires {@code decided}, which ends the reading as {@code done} and, while a category is not confirmed, asks the
 * submitter to confirm it ({@link EndReadingHandler}). A job that finds nothing left to decide still ends the
 * reading it was queued for, such as the parse of a document already checked: nothing else would end it.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = JobConsumer.class,
    property = { JobConsumer.PROPERTY_TOPICS + "=" + DecisionJobConsumer.JOB_TOPIC })
public class DecisionJobConsumer implements JobConsumer, ReadingJob
{
    /** The job topic. */
    public static final String JOB_TOPIC = "iap/extraction/decide";

    /** The event that ends the checks. */
    static final String DECIDED_EVENT = "decided";

    /**
     * What the submitter is told while a category is not confirmed. The same whether or not the model was sure: the
     * submitter is asked the same question either way, with or without a suggestion.
     */
    static final String CONFIRM_CATEGORY_MESSAGE = "Confirm the category to continue";

    @Reference
    private ReadingJobs jobs;

    @Reference
    private DocumentValidator validator;

    @Reference
    private DocumentCategoriser categoriser;

    @Override
    public JobResult process(final Job job)
    {
        return this.jobs.run(job, this);
    }

    /**
     * Whether the reading this job was queued for has ended: another job of the same submission ended it, or a
     * Stop did. One still running is this job's to end, even when every decision is made already.
     */
    @Override
    public boolean isAlreadyRead(final Submission submission)
    {
        return !ExtractionStatus.isRunning(submission);
    }

    @Override
    public String findReasonNotToStart(final Submission submission)
    {
        return DecisionChain.findReasonNotToStart(submission);
    }

    @Override
    public void read(final Resource submission, final String jobId) throws WorkflowException
    {
        final List<DecisionRequirement> pending =
            DecisionChain.getPendingDecisions(SubmissionFiles.getSubmission(submission));
        for (final DecisionRequirement decision : pending) {
            final Resource current = findStillRunning(submission);
            if (current == null || !decide(current, jobId, decision)) {
                return;
            }
        }
        final Resource current = findStillRunning(submission);
        if (current != null) {
            finish(current, jobId);
        }
    }

    /**
     * The submission as the engine last saved it, while its reading still runs.
     *
     * @return the submission, or {@code null} when it is gone or a no or a Stop ended its reading
     */
    private static Resource findStillRunning(final Resource submission)
    {
        final ResourceResolver resolver = submission.getResourceResolver();
        // Each step is saved through the engine's session, which this one sees only after a refresh
        resolver.refresh();
        final Resource current = resolver.getResource(submission.getPath());
        return current != null && ExtractionStatus.isRunning(current) ? current : null;
    }

    /**
     * Makes one decision about one document.
     *
     * @return {@code false} when the job must stop: the reading was given up, or is no longer this job's
     */
    private boolean decide(final Resource submission, final String jobId, final DecisionRequirement decision)
        throws WorkflowException
    {
        final File file = DecisionChain.findFile(SubmissionFiles.getSubmission(submission), decision);
        return switch (decision) {
            case final ValidationRequirement validation -> this.validator.validate(submission, jobId, validation, file);
            case final ClassificationRequirement classification ->
                this.categoriser.categorise(submission, jobId, classification, file);
            case null, default -> throw DecisionChain.createUnknownDecisionException(decision);
        };
    }

    /**
     * Ends the checks through the {@code decided} system workflow, asking the submitter to confirm the category while
     * it is not confirmed.
     */
    private void finish(final Resource submission, final String jobId)
        throws WorkflowException
    {
        final Map<String, Object> payload = new HashMap<>();
        payload.put(ReadingEvents.JOB_KEY, jobId);
        if (!DecisionChain.isCategoryConfirmed(SubmissionFiles.getSubmission(submission))) {
            payload.put(ReadingEvents.MESSAGE_KEY, CONFIRM_CATEGORY_MESSAGE);
        }
        this.jobs.sendOutcome(submission, new WorkflowEvent(DECIDED_EVENT, payload));
    }
}

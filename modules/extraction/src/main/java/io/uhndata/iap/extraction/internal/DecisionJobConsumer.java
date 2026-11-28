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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.Resource;
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
 * Once the intake took a validation's {@code no}, nothing after it is asked. Once every decision is made, or after
 * such a {@code no}, the job fires {@code decided}, which ends the reading as {@code done} ({@link EndReadingHandler}).
 * After a {@code no}, the message names the rejected document. While a category is not confirmed, it asks the
 * submitter to confirm it. A job that finds nothing left to decide still ends the reading it was queued for, such as
 * the parse of a document already checked: nothing else would end it.</p>
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

    @Reference
    private DecisionChain chain;

    @Override
    public JobResult process(final Job job)
    {
        return this.jobs.run(job, this);
    }

    @Override
    public String findReasonNotToStart(final Submission submission)
    {
        return this.chain.findReasonNotToStart(submission);
    }

    @Override
    public void read(final Resource submission, final String jobId) throws ReadingEndedException, WorkflowException
    {
        final Set<String> tried = new HashSet<>();
        Resource current = this.jobs.findStillRunning(submission);
        while (current != null) {
            final DecisionRequirement next = findNextDecision(current, tried);
            if (next == null) {
                finish(current, jobId);
                return;
            }
            tried.add(next.getPath());
            decide(current, jobId, next);
            current = this.jobs.findStillRunning(submission);
        }
    }

    /**
     * The first decision still to make that this job has not tried yet. Asked after each step, since a decision
     * whose condition reads an earlier verdict applies only once that verdict is saved.
     *
     * @return the decision, or {@code null} when nothing is left to decide
     */
    private DecisionRequirement findNextDecision(final Resource submission, final Set<String> tried)
    {
        return this.chain.getPendingDecisions(SubmissionFiles.getSubmission(submission)).stream()
            .filter(decision -> !tried.contains(decision.getPath()))
            .findFirst()
            .orElse(null);
    }

    /**
     * Makes one decision about one document.
     *
     * @throws ReadingEndedException when the job must stop: the reading was given up, or is no longer this job's
     */
    private void decide(final Resource submission, final String jobId, final DecisionRequirement decision)
        throws ReadingEndedException, WorkflowException
    {
        final File file = this.chain.findFile(SubmissionFiles.getSubmission(submission), decision);
        switch (decision) {
            case final ValidationRequirement validation -> this.validator.validate(submission, jobId, validation, file);
            case final ClassificationRequirement classification ->
                this.categoriser.categorise(submission, jobId, classification, file);
            case null -> throw new IllegalStateException("The chain holds no missing decision");
        }
    }

    /**
     * Ends the checks through the {@code decided} system workflow, saying which document was rejected, or asking the
     * submitter to confirm the category while it is not confirmed.
     */
    private void finish(final Resource submission, final String jobId)
        throws ReadingEndedException, WorkflowException
    {
        final Map<String, Object> payload = new HashMap<>();
        payload.put(ReadingEvents.JOB_KEY, jobId);
        final String message = findClosingMessage(SubmissionFiles.getSubmission(submission));
        if (message != null) {
            payload.put(ReadingEvents.MESSAGE_KEY, message);
        }
        this.jobs.sendOutcome(submission, new WorkflowEvent(DECIDED_EVENT, payload));
    }

    /** What the submitter is told when the checks end, or {@code null} when nothing is asked of them. */
    private String findClosingMessage(final Submission submission)
    {
        final String rejection = this.chain.findRejectionMessage(submission);
        if (rejection != null) {
            return rejection;
        }
        return this.chain.isCategoryConfirmed(submission) ? null : CONFIRM_CATEGORY_MESSAGE;
    }
}

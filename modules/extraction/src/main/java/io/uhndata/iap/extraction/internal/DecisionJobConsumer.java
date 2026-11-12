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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.schemas.models.ClassificationRequirement;
import io.uhndata.iap.schemas.models.DecisionRequirement;
import io.uhndata.iap.schemas.models.ValidationRequirement;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;

/**
 * The background job that checks a submission's uploads, the first steps of the intake. For each document, in the
 * order of the schema's document requirements, it runs the document's validation, if it has one, then its
 * categorisation, if it has one ({@link DecisionChain}). Asking about the same document twice in a row lets the
 * second model call reuse the prompt cache the first one filled, since the document text comes first in the prompt.
 *
 * <p>A validation is asked through {@link DocumentValidator}, a categorisation through {@link DocumentCategoriser}.
 * A validation's {@code no} ends the reading, so nothing after it is asked. Once every decision is made, the job
 * fires {@code decided}, which ends the reading as {@code done} and asks the submitter to confirm the category
 * ({@link FinishDecisionsHandler}).</p>
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
     * What the submitter is told once a document was categorised. The same whether or not the model was sure: the
     * submitter is asked the same question either way, with or without a suggestion.
     */
    static final String CONFIRM_CATEGORY_MESSAGE = "Confirm the category to continue";

    private static final Logger LOGGER = LoggerFactory.getLogger(DecisionJobConsumer.class);

    @Reference
    private ReadingJobs jobs;

    @Reference
    private WorkflowEngine engine;

    @Reference
    private DocumentValidator validator;

    @Reference
    private DocumentCategoriser categoriser;

    @Override
    public JobResult process(final Job job)
    {
        return this.jobs.run(job, this);
    }

    @Override
    public boolean isAlreadyRead(final Submission submission)
    {
        return DecisionChain.isFinished(submission);
    }

    @Override
    public String findReasonNotToStart(final ResourceResolver resolver, final Submission submission)
    {
        return DecisionChain.findReasonNotToStart(submission);
    }

    @Override
    public void read(final Resource submission, final String jobId) throws WorkflowException
    {
        final ResourceResolver resolver = submission.getResourceResolver();
        final List<DecisionRequirement> pending =
            DecisionChain.getPendingDecisions(SubmissionFiles.getSubmission(submission));
        boolean categorised = false;
        // One round per decision, and a last one that ends the chain
        for (int next = 0; next <= pending.size(); next++) {
            // Each step is saved through the engine's session, which this one sees only after a refresh
            resolver.refresh();
            final Resource current = resolver.getResource(submission.getPath());
            if (current == null || next > 0 && !ExtractionStatus.isRunning(current)) {
                // A no or a Stop ended the reading
                return;
            }
            if (next == pending.size()) {
                finish(current, jobId, categorised);
            } else if (!decide(current, jobId, pending.get(next))) {
                return;
            } else {
                categorised |= pending.get(next) instanceof ClassificationRequirement;
            }
        }
    }

    /**
     * Makes one decision about one document.
     *
     * @return {@code false} when the job must stop: the reading was given up, or is no longer this job's
     */
    private boolean decide(final Resource submission, final String jobId, final DecisionRequirement decision)
        throws WorkflowException
    {
        final File file = DecisionChain.getFile(SubmissionFiles.getSubmission(submission), decision);
        if (decision instanceof final ValidationRequirement validation) {
            return this.validator.validate(submission, jobId, validation, file);
        }
        return this.categoriser.categorise(submission, jobId, (ClassificationRequirement) decision, file);
    }

    /**
     * Ends the checks through the {@code decided} system workflow, asking the submitter to confirm the category when
     * a document was categorised.
     */
    private void finish(final Resource submission, final String jobId, final boolean categorised)
        throws WorkflowException
    {
        final Map<String, Object> payload = new HashMap<>();
        payload.put(ClaimReadingHandler.JOB_KEY, jobId);
        if (categorised) {
            payload.put(FinishDecisionsHandler.MESSAGE_KEY, CONFIRM_CATEGORY_MESSAGE);
        }
        try {
            this.engine.receiveEvent(submission, new WorkflowEvent(DECIDED_EVENT, payload));
        } catch (final InvalidStateException e) {
            // A Stop or a Read again took the reading after the last step
            LOGGER.info("Not finishing the checks of {}: {}", submission.getPath(), e.getMessage());
        }
    }
}

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

import java.util.List;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * What the submitter's requests to read share, Next and Read again: who may ask and when, and how the reading is
 * queued. The reading runs as a job, with the same claim, progress and Stop as one a parse queues, instead of
 * inside the request.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ReadingRequests.class)
public class ReadingRequests
{
    /** Why Next is refused while a document is still to be checked. */
    static final String CHECKS_PENDING_MESSAGE = "The documents have not all been checked yet, please read them again";

    @Reference
    private ReadingQueue readingQueue;

    @Reference
    private DecisionChain chain;

    @Reference
    private FormsToRead formsToRead;

    /**
     * Checks that the submitter may ask for a reading now: only the person who created the request, while it is a
     * draft, while no reading runs, and unless a document was rejected.
     *
     * @param target the submission's resource
     * @param submission the submission
     * @param actor who asks
     * @throws WorkflowException when the request is refused
     */
    void checkMayRead(final Resource target, final Submission submission, final String actor)
        throws WorkflowException
    {
        SubmitterAccess.checkMayChange(submission, actor);
        if (ExtractionStatus.isRunning(target)) {
            throw new InvalidStateException("This request is already being read");
        }
        checkNotRejected(submission);
    }

    /**
     * Checks that the intake did not take a no for a document of the request. The same documents would get the same
     * answer, so no new reading or parse starts. A no not handed over yet does not count: the next reading hands it
     * over.
     *
     * @param submission the submission
     * @throws InvalidStateException when the request was rejected, saying which document
     */
    void checkNotRejected(final Submission submission) throws InvalidStateException
    {
        final String rejection = this.chain.findRejectionMessage(submission);
        if (rejection != null) {
            throw new InvalidStateException(rejection);
        }
    }

    /**
     * Checks that the forms can be read now, and returns those with questions left. Next and Read again both ask
     * it before reading the forms, so neither can accept a state the other refuses.
     *
     * @param submission the submission
     * @return the forms with open questions, in schema order; empty when everything was read already
     * @throws InvalidStateException when a category is not confirmed, or a document a form reads cannot be read
     */
    List<FormsToRead.FormToRead> checkFormsToRead(final Submission submission) throws InvalidStateException
    {
        checkCategoryConfirmed(submission);
        // Checked before anything is read, so the submitter learns which document needs attention instead of
        // being told nothing is left
        final FormsToRead.ReadingPlan plan = this.formsToRead.planReading(submission);
        if (plan.reasonNotToStart() != null) {
            throw new InvalidStateException(plan.reasonNotToStart());
        }
        return plan.forms();
    }

    /** Checks that the submitter confirmed every category, which decides which forms apply. */
    private void checkCategoryConfirmed(final Submission submission) throws InvalidStateException
    {
        if (!this.chain.isCategoryConfirmed(submission)) {
            throw new InvalidStateException(DecisionJobConsumer.CONFIRM_CATEGORY_MESSAGE);
        }
    }

    /**
     * Checks that every decision about the uploads is made, so no form is read from a document not checked yet.
     *
     * @param submission the submission
     * @throws InvalidStateException when a validation or categorisation is still to be made
     */
    void checkDecisionsMade(final Submission submission) throws InvalidStateException
    {
        if (!this.chain.getPendingDecisions(submission).isEmpty()) {
            throw new InvalidStateException(CHECKS_PENDING_MESSAGE);
        }
    }

    /**
     * Marks the submission {@code running} and queues the reading job once this step has committed.
     *
     * @param context the step the submitter's request runs in
     * @param topic the job's topic
     * @throws PersistenceException when the status cannot be written
     */
    void queue(final WorkflowTaskContext context, final String topic) throws PersistenceException
    {
        this.readingQueue.queueAfterCommit(context, topic, ExtractionStatus.startReading(context.getTarget()));
    }
}

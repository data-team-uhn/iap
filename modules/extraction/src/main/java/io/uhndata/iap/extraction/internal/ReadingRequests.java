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

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
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
    @Reference
    private ReadingQueue readingQueue;

    /**
     * Checks that the submitter may ask for a reading now: only the person who created the request, while it is a
     * draft, while no reading runs, and unless a document was rejected.
     *
     * @param target the submission's resource
     * @param submission the submission
     * @param actor who asks
     * @throws WorkflowException when the request is refused
     */
    static void checkMayRead(final Resource target, final Submission submission, final String actor)
        throws WorkflowException
    {
        SubmitterAccess.checkMayChange(submission, actor);
        if (ExtractionStatus.isRunning(target)) {
            throw new InvalidStateException("This request is already being read");
        }
        checkNotBlocked(target);
    }

    /**
     * Checks that no validation rejected a document of the request. The same documents get the same answer, and
     * the intake that ended there never takes a new one, so nothing more is read.
     *
     * @param target the submission's resource
     * @throws WorkflowException when the request is blocked, with the validation's message
     */
    static void checkNotBlocked(final Resource target) throws WorkflowException
    {
        final ValueMap properties = target.getValueMap();
        if (Boolean.TRUE.equals(properties.get(ExtractionStatus.BLOCKED_PROPERTY, Boolean.class))) {
            throw new InvalidStateException(
                properties.get(ExtractionStatus.MESSAGE_PROPERTY, "This request cannot go on with these documents"));
        }
    }

    /**
     * Checks that the submitter confirmed every category, which decides which forms apply.
     *
     * @param submission the submission
     * @throws InvalidStateException when a category question has no answer yet
     */
    static void checkCategoryConfirmed(final Submission submission) throws InvalidStateException
    {
        if (!DecisionChain.isCategoryConfirmed(submission)) {
            throw new InvalidStateException(DecisionJobConsumer.CONFIRM_CATEGORY_MESSAGE);
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
        final Resource target = context.getTarget();
        ExtractionStatus.recordStatus(target, ExtractionStatus.STATUS_RUNNING, null);
        // Otherwise the last reading's claim would turn the new job away
        ExtractionStatus.releaseClaim(target);
        this.readingQueue.queueAfterCommit(context, topic);
    }
}

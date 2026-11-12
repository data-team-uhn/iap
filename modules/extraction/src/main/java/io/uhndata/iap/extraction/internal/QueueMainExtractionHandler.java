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

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.event.jobs.JobManager;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Service task {@code queueMainExtraction}: the submitter clicked Next after confirming the category, so the
 * answers are read out of the documents. The reading is queued as a job ({@link MainExtractionJobConsumer}), with
 * the same claim, progress and Stop as the document checks, instead of running inside the request.
 *
 * <p>It is refused while a reading runs, after a document was rejected, and until every category is confirmed,
 * since the category decides which forms apply. When no form has an open question, nothing is queued.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class QueueMainExtractionHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "queueMainExtraction";

    private static final Logger LOGGER = LoggerFactory.getLogger(QueueMainExtractionHandler.class);

    @Reference
    private JobManager jobManager;

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource target = context.getTarget();
        final Submission submission = SubmissionFiles.getSubmission(target);
        SubmitterAccess.checkMayChange(submission, context.getActor());
        final ValueMap properties = target.getValueMap();
        if (ExtractionStatus.isRunning(target)) {
            throw new InvalidStateException("This request is already being read");
        }
        if (Boolean.TRUE.equals(properties.get(ExtractionStatus.BLOCKED_PROPERTY, Boolean.class))) {
            // The validation said the upload is not what the request needs: only a new document helps
            throw new InvalidStateException(
                properties.get(ExtractionStatus.MESSAGE_PROPERTY, "This request cannot go on with these documents"));
        }
        if (!DecisionChain.isCategoryConfirmed(submission)) {
            throw new InvalidStateException(DecisionJobConsumer.CONFIRM_CATEGORY_MESSAGE);
        }
        if (FormsToRead.getFormsToRead(submission).isEmpty()) {
            LOGGER.info("Nothing left to read: submission={}", target.getPath());
            return;
        }
        ExtractionStatus.recordStatus(target, ExtractionStatus.STATUS_RUNNING, null);
        // Otherwise the document checks' claim would turn the new job away
        ExtractionStatus.releaseClaim(target);
        // Queued before this walk commits, so the job is told to wait until it sees the `running` written above
        if (this.jobManager.addJob(MainExtractionJobConsumer.JOB_TOPIC, Map.of(ReadingJobs.SUBMISSION_PROPERTY,
            target.getPath(), ReadingJobs.REQUESTED_PROPERTY, Boolean.TRUE)) == null) {
            throw new PersistenceException("The reading of " + target.getPath() + " could not be queued");
        }
        LOGGER.info("Main extraction queued: submission={}", target.getPath());
    }
}

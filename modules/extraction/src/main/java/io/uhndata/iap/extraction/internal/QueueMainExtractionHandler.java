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
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.utils.VersioningUtils;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Service task {@code queueMainExtraction}: the submitter clicked Next after confirming the category, so the
 * answers are read out of the documents. The reading is queued as a job ({@link MainExtractionJobConsumer}), with
 * the same claim, progress and Stop as the document checks ({@link ReadingRequests}).
 *
 * <p>It is refused while a reading runs, after a document was rejected, and until every category is confirmed,
 * since the category decides which forms apply. When no form has an open question, nothing is queued, and the
 * reading's message is cleared.</p>
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
    private ReadingRequests requests;

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
        ReadingRequests.checkMayRead(target, submission, context.getActor());
        ReadingRequests.checkCategoryConfirmed(submission);
        if (FormsToRead.getFormsToRead(submission).isEmpty()) {
            // Clears the message the checks left, which asked for the category just confirmed
            VersioningUtils.checkOut(target);
            ExtractionStatus.recordStatus(target, ExtractionStatus.STATUS_DONE, null);
            LOGGER.info("Nothing left to read: submission={}", target.getPath());
            return;
        }
        this.requests.queue(context, MainExtractionJobConsumer.JOB_TOPIC);
        LOGGER.info("Main extraction queued: submission={}", target.getPath());
    }
}

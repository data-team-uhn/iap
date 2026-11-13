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
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Service task {@code readAgain}: the submitter asks to read the submission again, such as after the model could
 * not be reached. It runs the first step not done yet:
 *
 * <ol>
 * <li>a validation or categorisation not made yet: the document checks ({@link DecisionJobConsumer});</li>
 * <li>a category not confirmed yet: refused, the submitter confirms it first;</li>
 * <li>otherwise the main extraction ({@link MainExtractionJobConsumer}), for the questions never read.</li>
 * </ol>
 *
 * <p>It is refused while a reading runs, after a document was rejected (the same document gets the same answer),
 * and when every question was read already.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class ReadAgainHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "readAgain";

    /** What the submitter is told when every question was read already. */
    static final String NOTHING_LEFT_MESSAGE = "Every question has been read already";

    private static final Logger LOGGER = LoggerFactory.getLogger(ReadAgainHandler.class);

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
        if (!DecisionChain.getPendingDecisions(submission).isEmpty()) {
            this.requests.queue(context, DecisionJobConsumer.JOB_TOPIC);
            LOGGER.info("Reading asked for again: submission={} step=checks", target.getPath());
            return;
        }
        ReadingRequests.checkCategoryConfirmed(submission);
        if (FormsToRead.getFormsToRead(submission).isEmpty()) {
            throw new InvalidStateException(NOTHING_LEFT_MESSAGE);
        }
        this.requests.queue(context, MainExtractionJobConsumer.JOB_TOPIC);
        LOGGER.info("Reading asked for again: submission={} step=answers", target.getPath());
    }
}

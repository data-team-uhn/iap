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
 * Service task {@code readAgain}: the submitter asks to read the submission again after a reading ended. The
 * reading is queued as a job, like the one a parse queues, so it has the same claim, progress and way to stop it,
 * instead of running inside the request that asked. It is refused while a reading is still running.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class ReadAgainHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "readAgain";

    private static final Logger LOGGER = LoggerFactory.getLogger(ReadAgainHandler.class);

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
        if (ExtractionStatus.STATUS_RUNNING.equals(
            target.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class))) {
            throw new InvalidStateException("This request is already being read");
        }
        ExtractionStatus.record(target, ExtractionStatus.STATUS_RUNNING, null);
        // Otherwise the last reading's claim would turn the new job away
        ExtractionStatus.releaseClaim(target);
        // Queued before this walk commits, so the job is told to wait until it sees the `running` written above
        if (this.jobManager.addJob(ExtractAnswersJobConsumer.JOB_TOPIC,
            Map.of(ExtractAnswersJobConsumer.SUBMISSION_PROPERTY,
            target.getPath(), ExtractAnswersJobConsumer.READ_AGAIN, Boolean.TRUE)) == null) {
            throw new PersistenceException("The reading of " + target.getPath() + " could not be queued");
        }
        LOGGER.info("Reading asked for again: submission={}", target.getPath());
    }
}

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

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.consumer.JobConsumer;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;

/**
 * Runs the reading of a submission's answers as a background job. It fires the {@code extractAnswers} event on the
 * submission, and that system workflow starts the reading workflow the schema version names.
 *
 * <p>Every finished parse queues one of these jobs, and {@code readAgain} queues one too. {@link ReadingJobs} runs
 * the steps every reading shares: waiting for the parses, the claim, and giving up.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = JobConsumer.class,
    property = { JobConsumer.PROPERTY_TOPICS + "=" + ExtractAnswersJobConsumer.JOB_TOPIC })
public class ExtractAnswersJobConsumer implements JobConsumer, ReadingJob
{
    /** The job topic. */
    public static final String JOB_TOPIC = "iap/extraction/extract";

    /** The event that starts the reading. */
    static final String EXTRACT_ANSWERS_EVENT = "extractAnswers";

    @Reference
    private ReadingJobs jobs;

    @Reference
    private WorkflowEngine engine;

    @Override
    public JobResult process(final Job job)
    {
        return this.jobs.run(job, this);
    }

    /**
     * Never: this reading keeps its claim when it is done, so the other jobs of the same reading find it taken.
     *
     * @param submission the submission to read
     * @return {@code false}
     */
    @Override
    public boolean isAlreadyRead(final Submission submission)
    {
        return false;
    }

    /**
     * Why the reading cannot start: the schema version names no reading workflow, or one that cannot run.
     *
     * @param resolver this job's session
     * @param submission the submission to read
     * @return the message for the submitter, or {@code null} when the reading workflow exists and is tagged active
     */
    @Override
    public String findReasonNotToStart(final ResourceResolver resolver, final Submission submission)
    {
        final SubmissionFiles.ReadingWorkflowState reading =
            SubmissionFiles.checkReadingWorkflow(submission, resolver);
        if (reading == SubmissionFiles.ReadingWorkflowState.CANNOT_RUN) {
            SubmissionFiles.reportReadingWorkflowCannotRun(ExtractAnswersJobConsumer.class, submission.getPath());
        }
        return reading == SubmissionFiles.ReadingWorkflowState.CAN_RUN ? null : ReadingJobs.NOT_SET_UP_MESSAGE;
    }

    @Override
    public void read(final Resource submission, final String jobId) throws WorkflowException
    {
        this.engine.receiveEvent(submission, new WorkflowEvent(EXTRACT_ANSWERS_EVENT, Map.of()));
    }
}

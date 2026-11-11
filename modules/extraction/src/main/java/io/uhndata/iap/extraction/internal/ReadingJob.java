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

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.WorkflowException;

/**
 * One kind of background reading: what it needs before it starts, and the reading itself. {@link ReadingJobs} runs
 * the steps every kind shares around these two.
 *
 * @version $Id$
 * @since 0.1.0
 */
interface ReadingJob
{
    /**
     * Why this reading cannot start, if it cannot. It is asked after every parse has settled and the schema version
     * was found readable, and before the reading is claimed.
     *
     * @param resolver the job's session
     * @param submission the submission to read
     * @return the message for the submitter, or {@code null} when the reading can start
     */
    String findReasonNotToStart(ResourceResolver resolver, Submission submission);

    /**
     * Reads the submission. The reading is already claimed for this job.
     *
     * @param submission the submission to read, through the job's session
     * @param jobId this job's id, which the claim holds
     * @throws WorkflowException when the engine refuses an event the reading fires; the reading is then given up
     */
    void read(Resource submission, String jobId) throws WorkflowException;
}

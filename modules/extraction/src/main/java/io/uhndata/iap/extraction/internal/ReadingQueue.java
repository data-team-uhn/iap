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

import org.apache.sling.event.jobs.JobManager;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Queues the background jobs that read a submission. A job is queued only once the step that asks for it has
 * committed, so the job sees what that step wrote, such as the {@code running} status or a finished parse.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ReadingQueue.class)
public class ReadingQueue
{
    @Reference
    private JobManager jobManager;

    /**
     * Queues a reading job for the step's submission once the step has committed. A job that cannot be queued is
     * recorded against the step, and the submission keeps showing {@code running} until the submitter stops it.
     *
     * @param context the step that asks for the reading
     * @param topic the job's topic, which says what kind of reading it is
     */
    void queueAfterCommit(final WorkflowTaskContext context, final String topic)
    {
        final String path = context.getTarget().getPath();
        context.afterCommit(() -> {
            if (this.jobManager.addJob(topic, Map.of(ReadingJobs.SUBMISSION_PROPERTY, path)) == null) {
                throw new IllegalStateException("The reading of " + path + " could not be queued");
            }
        });
    }
}

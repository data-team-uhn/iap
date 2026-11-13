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
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.JobManager;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link QueueDecisionsHandler}: queuing the job that reads a submission.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class QueueDecisionsHandlerTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final JobManager jobManager = Mockito.mock(JobManager.class);

    private final QueueDecisionsHandler handler = new QueueDecisionsHandler();

    private SubmissionTree tree;

    private Resource submission;

    @BeforeEach
    void setUp()
    {
        this.tree = new SubmissionTree(this.context);
        this.tree.createSchemaVersion();
        this.submission = this.tree.createSubmission();
        final ReadingQueue queue = new ReadingQueue();
        TestComponents.inject(queue, "jobManager", this.jobManager);
        TestComponents.inject(this.handler, "readingQueue", queue);
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("queueDecisions", this.handler.getName());
    }

    // The job decides whether it is time to read, because it sees committed data. Checked inside this
    // transaction, two parses landing at once could each see the other as unfinished.
    @Test
    void queuesTheChecksForTheReadingAfterTheCommit() throws Exception
    {
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.READING_ID_PROPERTY, "r1");
        Mockito.when(this.jobManager.addJob(Mockito.eq(DecisionJobConsumer.JOB_TOPIC), Mockito.anyMap()))
            .thenReturn(Mockito.mock(Job.class));

        final WorkflowTaskContext step = TaskContexts.createContext(this.submission, Map.of(), new HashMap<>());
        this.handler.execute(step);
        TaskContexts.commit(step);

        Mockito.verify(this.jobManager).addJob(DecisionJobConsumer.JOB_TOPIC,
            Map.of(ReadingJobs.SUBMISSION_PROPERTY, this.submission.getPath(), ReadingEvents.READING_ID_KEY, "r1"));
    }

    // A job queued before the commit could start before its step's writes are visible
    @Test
    void queuesNothingBeforeTheStepCommits()
    {
        this.tree.createFile("completed");

        this.handler.execute(TaskContexts.createContext(this.submission, Map.of(), new HashMap<>()));

        Mockito.verifyNoInteractions(this.jobManager);
    }

    // The step has committed by then, so the engine records the failure against it
    @Test
    void failsAfterTheCommitWhenTheJobCannotBeQueued()
    {
        this.tree.createFile("completed");
        final WorkflowTaskContext step = TaskContexts.createContext(this.submission, Map.of(), new HashMap<>());
        this.handler.execute(step);

        assertThrows(IllegalStateException.class, () -> TaskContexts.commit(step));
    }
}

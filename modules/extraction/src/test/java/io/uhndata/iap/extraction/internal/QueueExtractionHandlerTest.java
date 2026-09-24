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

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.PersistenceException;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link QueueExtractionHandler}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class QueueExtractionHandlerTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final JobManager jobManager = Mockito.mock(JobManager.class);

    private final QueueExtractionHandler handler = new QueueExtractionHandler();

    private SubmissionTree tree;

    private Resource submission;

    @BeforeEach
    void setUp() throws Exception
    {
        this.tree = new SubmissionTree(this.context);
        this.tree.schemaVersion();
        this.submission = this.tree.submission();
        final Field field = QueueExtractionHandler.class.getDeclaredField("jobManager");
        field.setAccessible(true);
        field.set(this.handler, this.jobManager);
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("queueExtraction", this.handler.getName());
    }

    @Test
    void queuesTheReading() throws Exception
    {
        this.tree.file("completed");
        this.tree.file("failed");
        Mockito.when(this.jobManager.addJob(Mockito.eq(ExtractAnswersJobConsumer.TOPIC), Mockito.anyMap()))
            .thenReturn(Mockito.mock(Job.class));

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), new HashMap<>()));

        Mockito.verify(this.jobManager).addJob(ExtractAnswersJobConsumer.TOPIC,
            Map.of(ExtractAnswersJobConsumer.SUBMISSION, this.submission.getPath()));
    }

    // Every parse that lands queues one, even while another is still going. Whether this one's turn has come
    // is the job's to answer, from a session that sees committed state; asked from inside this transaction,
    // two parses landing at once could each see the other as unfinished and neither would queue anything.
    @Test
    void queuesTheReadingEvenWhileAnotherParseIsStillGoing() throws Exception
    {
        this.tree.file("completed");
        this.tree.file("queued");
        Mockito.when(this.jobManager.addJob(Mockito.eq(ExtractAnswersJobConsumer.TOPIC), Mockito.anyMap()))
            .thenReturn(Mockito.mock(Job.class));

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), new HashMap<>()));

        Mockito.verify(this.jobManager).addJob(ExtractAnswersJobConsumer.TOPIC,
            Map.of(ExtractAnswersJobConsumer.SUBMISSION, this.submission.getPath()));
    }

    @Test
    void refusesWhenTheJobCannotBeQueued()
    {
        this.tree.file("completed");

        assertThrows(PersistenceException.class,
            () -> this.handler.execute(TaskContexts.of(this.submission, Map.of(), new HashMap<>())));
    }
}

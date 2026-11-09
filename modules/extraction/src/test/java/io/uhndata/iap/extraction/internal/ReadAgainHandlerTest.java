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

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.JobManager;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link ReadAgainHandler}: when the submitter can ask for a new reading, and how it is queued.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ReadAgainHandlerTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final JobManager jobManager = Mockito.mock(JobManager.class);

    private final ReadAgainHandler handler = new ReadAgainHandler();

    private Resource submission;

    @BeforeEach
    void setUp() throws Exception
    {
        final SubmissionTree tree = new SubmissionTree(this.context);
        tree.createSchemaVersion();
        this.submission = tree.createSubmission();
        final ModifiableValueMap properties = this.submission.adaptTo(ModifiableValueMap.class);
        properties.put(ExtractionStatus.STATUS_PROPERTY, ExtractionStatus.STATUS_FAILED);
        properties.put(ExtractionStatus.MESSAGE_PROPERTY, "The model's answer could not be read");
        properties.put(ExtractionStatus.READING_CLAIMED, true);
        final Field field = ReadAgainHandler.class.getDeclaredField("jobManager");
        field.setAccessible(true);
        field.set(this.handler, this.jobManager);
        Mockito.when(this.jobManager.addJob(Mockito.anyString(), Mockito.anyMap()))
            .thenReturn(Mockito.mock(Job.class));
    }

    private WorkflowTaskContext createTask()
    {
        return TaskContexts.of(this.submission, Map.of(), new HashMap<>());
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("readAgain", this.handler.getName());
    }

    // Queued as a job, like after a parse, so the reading gets the same claim, progress and stop
    @Test
    void queuesTheReadingAsAJobAndSaysItIsRunning() throws Exception
    {
        this.handler.execute(createTask());

        Mockito.verify(this.jobManager).addJob(ExtractAnswersJobConsumer.JOB_TOPIC,
            Map.of(ExtractAnswersJobConsumer.SUBMISSION_PROPERTY, this.submission.getPath(),
                ExtractAnswersJobConsumer.READ_AGAIN, Boolean.TRUE));
        final ValueMap recorded = this.submission.getValueMap();
        assertEquals(ExtractionStatus.STATUS_RUNNING, recorded.get(ExtractionStatus.STATUS_PROPERTY, String.class));
        assertNull(recorded.get(ExtractionStatus.MESSAGE_PROPERTY, String.class),
            "the old failure is no longer the news");
        assertFalse(recorded.containsKey(ExtractionStatus.READING_CLAIMED), "or the job would turn itself away");
    }

    @Test
    void refusesWhileAReadingIsAlreadyGoing()
    {
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.STATUS_PROPERTY,
            ExtractionStatus.STATUS_RUNNING);

        assertThrows(InvalidStateException.class, () -> this.handler.execute(createTask()));
        Mockito.verifyNoInteractions(this.jobManager);
    }

    // A schema that names no reading workflow never reads its documents, so there is nothing to read again
    @Test
    void refusesWhenTheSchemaReadsNothing()
    {
        this.context.resourceResolver().getResource(SubmissionTree.VERSION_PATH).adaptTo(ModifiableValueMap.class)
            .remove(SubmissionFiles.READING_WORKFLOW);

        final InvalidStateException refused =
            assertThrows(InvalidStateException.class, () -> this.handler.execute(createTask()));

        assertEquals(ExtractAnswersJobConsumer.NOTHING_READS, refused.getMessage());
        Mockito.verifyNoInteractions(this.jobManager);
    }

    @Test
    void refusesAnybodyButTheSubmitter()
    {
        this.submission.adaptTo(ModifiableValueMap.class).put("createdBy", "a-reviewer");

        assertThrows(NotAuthorizedException.class, () -> this.handler.execute(createTask()));
        Mockito.verifyNoInteractions(this.jobManager);
    }

    @Test
    void refusesARequestAlreadySent()
    {
        this.submission.adaptTo(ModifiableValueMap.class).put("tags", new String[0]);

        assertThrows(InvalidStateException.class, () -> this.handler.execute(createTask()));
    }

    @Test
    void saysSoWhenTheReadingCannotBeQueued()
    {
        Mockito.when(this.jobManager.addJob(Mockito.anyString(), Mockito.anyMap())).thenReturn(null);

        assertThrows(PersistenceException.class, () -> this.handler.execute(createTask()));
    }
}

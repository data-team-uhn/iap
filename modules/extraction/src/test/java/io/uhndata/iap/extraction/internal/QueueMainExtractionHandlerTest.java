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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link QueueMainExtractionHandler}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class QueueMainExtractionHandlerTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final JobManager jobManager = Mockito.mock(JobManager.class);

    private final QueueMainExtractionHandler handler = new QueueMainExtractionHandler();

    private SubmissionTree tree;

    private Resource submission;

    private Resource category;

    @BeforeEach
    void setUp() throws Exception
    {
        this.tree = new SubmissionTree(this.context);
        this.tree.createSchemaVersion();
        this.submission = this.tree.createSubmission();
        final Resource proposal = this.tree.createDocumentRequirement("proposal");
        this.tree.createParsedFileFor(proposal, "A study of sleep in shift workers.");
        this.context.resourceResolver().getResource(SubmissionTree.FORM_PATH).adaptTo(ModifiableValueMap.class)
            .put("documents", new String[] { "proposal" });
        this.tree.createQuestion("title", "The study's full title.");
        this.category = this.tree.createClassificationQuestion("study_kind", "proposal", "What kind of study?",
            "interventional", "observational");
        answer(this.category, "interventional");
        final ModifiableValueMap properties = this.submission.adaptTo(ModifiableValueMap.class);
        properties.put(ExtractionStatus.STATUS_PROPERTY, ExtractionStatus.STATUS_DONE);
        properties.put(ExtractionStatus.MESSAGE_PROPERTY, DecisionJobConsumer.CONFIRM_CATEGORY_MESSAGE);
        properties.put(ExtractionStatus.READING_CLAIMED_PROPERTY, true);
        final Field field = QueueMainExtractionHandler.class.getDeclaredField("jobManager");
        field.setAccessible(true);
        field.set(this.handler, this.jobManager);
        Mockito.when(this.jobManager.addJob(Mockito.anyString(), Mockito.anyMap()))
            .thenReturn(Mockito.mock(Job.class));
    }

    private Resource answer(final Resource question, final String value)
    {
        final Resource answer = this.context.create().resource(
            SubmissionTree.SUBMISSION_PATH + "/a-" + question.getName(), Map.of("jcr:primaryType", "sub:Answer",
                "sling:resourceType", "sub/Answer", "value", new String[] { value }));
        SubmissionTree.reference(answer, "question", question);
        return answer;
    }

    private WorkflowTaskContext createTask()
    {
        return TaskContexts.of(this.submission, Map.of(), new HashMap<>());
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("queueMainExtraction", this.handler.getName());
    }

    // Queued as a job, like the document checks, so it gets the same claim, progress and Stop
    @Test
    void queuesTheReadingAsAJobAndSaysItIsRunning() throws Exception
    {
        this.handler.execute(createTask());

        Mockito.verify(this.jobManager).addJob(MainExtractionJobConsumer.JOB_TOPIC,
            Map.of(ReadingJobs.SUBMISSION_PROPERTY, this.submission.getPath(),
                ReadingJobs.REQUESTED_PROPERTY, Boolean.TRUE));
        final ValueMap recorded = this.submission.getValueMap();
        assertEquals(ExtractionStatus.STATUS_RUNNING, recorded.get(ExtractionStatus.STATUS_PROPERTY, String.class));
        assertFalse(recorded.containsKey(ExtractionStatus.MESSAGE_PROPERTY));
        assertFalse(recorded.containsKey(ExtractionStatus.READING_CLAIMED_PROPERTY),
            "or the job would turn itself away");
    }

    // The category decides which forms apply
    @Test
    void refusesUntilTheCategoryIsConfirmed()
    {
        this.context.resourceResolver().getResource(SubmissionTree.SUBMISSION_PATH + "/a-study_kind")
            .adaptTo(ModifiableValueMap.class).remove("value");

        final InvalidStateException refused =
            assertThrows(InvalidStateException.class, () -> this.handler.execute(createTask()));

        assertEquals(DecisionJobConsumer.CONFIRM_CATEGORY_MESSAGE, refused.getMessage());
        Mockito.verifyNoInteractions(this.jobManager);
    }

    @Test
    void refusesWhileAReadingIsAlreadyGoing()
    {
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.STATUS_PROPERTY,
            ExtractionStatus.STATUS_RUNNING);

        assertThrows(InvalidStateException.class, () -> this.handler.execute(createTask()));
        Mockito.verifyNoInteractions(this.jobManager);
    }

    // Only a new document helps once a validation said no
    @Test
    void refusesARequestThatCannotGoOn()
    {
        final ModifiableValueMap properties = this.submission.adaptTo(ModifiableValueMap.class);
        properties.put(ExtractionStatus.BLOCKED_PROPERTY, true);
        properties.put(ExtractionStatus.MESSAGE_PROPERTY, "Unable to identify the proposal");

        final InvalidStateException refused =
            assertThrows(InvalidStateException.class, () -> this.handler.execute(createTask()));

        assertEquals("Unable to identify the proposal", refused.getMessage());
        Mockito.verifyNoInteractions(this.jobManager);
    }

    @Test
    void queuesNothingWhenNoQuestionIsOpen() throws Exception
    {
        answer(this.context.resourceResolver().getResource(SubmissionTree.FORM_PATH + "/title"), "Sleep");

        this.handler.execute(createTask());

        Mockito.verifyNoInteractions(this.jobManager);
        assertEquals(ExtractionStatus.STATUS_DONE, this.submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY));
    }

    @Test
    void refusesAnybodyButTheSubmitter()
    {
        this.submission.adaptTo(ModifiableValueMap.class).put("createdBy", "a-reviewer");

        assertThrows(NotAuthorizedException.class, () -> this.handler.execute(createTask()));
        Mockito.verifyNoInteractions(this.jobManager);
    }

    @Test
    void saysSoWhenTheReadingCannotBeQueued()
    {
        Mockito.when(this.jobManager.addJob(Mockito.anyString(), Mockito.anyMap())).thenReturn(null);

        assertThrows(PersistenceException.class, () -> this.handler.execute(createTask()));
    }
}

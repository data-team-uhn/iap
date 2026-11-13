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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    private Resource categoryAnswer;

    @BeforeEach
    void setUp() throws Exception
    {
        this.tree = new SubmissionTree(this.context);
        this.tree.createSchemaVersion();
        this.submission = this.tree.createSubmission();
        final Resource proposal = this.tree.createDocumentRequirement("proposal");
        this.tree.createParsedFileFor(proposal, "A study of sleep in shift workers.");
        this.tree.setFormDocuments(SubmissionTree.FORM_PATH, "proposal");
        this.tree.createQuestion("title", "The study's full title.");
        this.category = this.tree.createClassificationQuestion("study_kind", "proposal", "What kind of study?",
            "interventional", "observational");
        this.categoryAnswer = this.tree.createAnswer(this.category, "interventional");
        final ModifiableValueMap properties = this.submission.adaptTo(ModifiableValueMap.class);
        properties.put(ExtractionStatus.STATUS_PROPERTY, ExtractionStatus.STATUS_DONE);
        properties.put(ExtractionStatus.MESSAGE_PROPERTY, DecisionJobConsumer.CONFIRM_CATEGORY_MESSAGE);
        properties.put(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, "an-earlier-job");
        TestComponents.inject(this.handler, "requests",
            TestComponents.createRequests(this.jobManager, new DecisionChain()));
        Mockito.when(this.jobManager.addJob(Mockito.anyString(), Mockito.anyMap()))
            .thenReturn(Mockito.mock(Job.class));
    }

    private WorkflowTaskContext createTask()
    {
        return TaskContexts.createContext(this.submission, Map.of(), new HashMap<>());
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("queueMainExtraction", this.handler.getName());
    }

    @Test
    void queuesTheReadingAsAJobAndSaysItIsRunning() throws Exception
    {
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.READING_ID_PROPERTY, "checks");
        final WorkflowTaskContext step = createTask();
        this.handler.execute(step);
        Mockito.verifyNoInteractions(this.jobManager);
        TaskContexts.commit(step);

        verifyQueued(MainExtractionJobConsumer.JOB_TOPIC);
        final ValueMap recorded = this.submission.getValueMap();
        assertNotEquals("checks", recorded.get(ExtractionStatus.READING_ID_PROPERTY, String.class),
            "a job still waiting from the checks would take this reading over");
        assertEquals(ExtractionStatus.STATUS_RUNNING, recorded.get(ExtractionStatus.STATUS_PROPERTY, String.class));
        assertFalse(recorded.containsKey(ExtractionStatus.MESSAGE_PROPERTY));
        assertFalse(recorded.containsKey(ExtractionStatus.READING_CLAIMED_BY_PROPERTY),
            "or the job would turn itself away");
    }

    // The category decides which forms apply. The model read it, and the submitter has not confirmed it yet.
    @Test
    void refusesUntilTheCategoryIsConfirmed()
    {
        this.categoryAnswer.adaptTo(ModifiableValueMap.class).remove("value");
        this.tree.createRun(this.categoryAnswer, SubmissionTree.SURE, "interventional");

        final InvalidStateException refused =
            assertThrows(InvalidStateException.class, () -> this.handler.execute(createTask()));

        assertEquals(DecisionJobConsumer.CONFIRM_CATEGORY_MESSAGE, refused.getMessage());
    }

    // A form read from a document nobody checked may be read from the wrong document
    @Test
    void refusesWhileADocumentIsStillToBeChecked()
    {
        this.tree.createValidationRequirement("is_proposal", "proposal", "Is it a proposal?");

        final InvalidStateException refused =
            assertThrows(InvalidStateException.class, () -> this.handler.execute(createTask()));

        assertEquals(ReadingRequests.CHECKS_PENDING_MESSAGE, refused.getMessage());
    }

    // The consent was left out, so it has no category to confirm
    @Test
    void readsWithoutTheCategoryOfADocumentNotUploaded() throws Exception
    {
        this.tree.createDocumentRequirement("consent").adaptTo(ModifiableValueMap.class).put("required", false);
        this.tree.createClassificationQuestion("consent_kind", "consent", "What kind of consent?", "written",
            "verbal");
        final WorkflowTaskContext step = createTask();
        this.handler.execute(step);
        TaskContexts.commit(step);

        verifyQueued(MainExtractionJobConsumer.JOB_TOPIC);
    }

    /** Checks that the job was queued with the reading id this request started. */
    private void verifyQueued(final String topic)
    {
        Mockito.verify(this.jobManager).addJob(topic, Map.of(ReadingJobs.SUBMISSION_PROPERTY,
            this.submission.getPath(), ReadingEvents.READING_ID_KEY,
            this.submission.getValueMap().get(ExtractionStatus.READING_ID_PROPERTY, String.class)));
    }

    // The category was just confirmed, so asking to confirm it is no longer the news
    @Test
    void queuesNothingWhenNoQuestionIsOpen() throws Exception
    {
        this.tree.createAnswer(this.context.resourceResolver().getResource(SubmissionTree.FORM_PATH + "/title"),
            "Sleep");

        final WorkflowTaskContext step = createTask();
        this.handler.execute(step);
        TaskContexts.commit(step);

        Mockito.verifyNoInteractions(this.jobManager);
        assertEquals(ExtractionStatus.STATUS_DONE, this.submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY));
        assertFalse(this.submission.getValueMap().containsKey(ExtractionStatus.MESSAGE_PROPERTY));
    }

    // The shared checks are tested in ReadingRequestsTest; this one shows the handler asks them
    @Test
    void refusesAnybodyButTheSubmitter()
    {
        this.submission.adaptTo(ModifiableValueMap.class).put("createdBy", "a-reviewer");

        assertThrows(NotAuthorizedException.class, () -> this.handler.execute(createTask()));
    }
}

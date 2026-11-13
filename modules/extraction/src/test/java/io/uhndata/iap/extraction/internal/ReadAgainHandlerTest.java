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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link ReadAgainHandler}: which step it runs again, and when the submitter may ask.
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

    private SubmissionTree tree;

    private Resource submission;

    private Resource category;

    @BeforeEach
    void setUp() throws Exception
    {
        this.tree = new SubmissionTree(this.context);
        this.tree.createSchemaVersion();
        this.submission = this.tree.createSubmission();
        this.tree.createParsedFileFor(this.tree.createDocumentRequirement("proposal"),
            "A study of sleep in shift workers.");
        this.tree.setFormDocuments(SubmissionTree.FORM_PATH, "proposal");
        this.tree.createQuestion("title", "The study's full title.");
        this.category = this.tree.createClassificationQuestion("study_kind", "proposal", "What kind of study?",
            "interventional", "observational");
        final ModifiableValueMap properties = this.submission.adaptTo(ModifiableValueMap.class);
        properties.put(ExtractionStatus.STATUS_PROPERTY, ExtractionStatus.STATUS_FAILED);
        properties.put(ExtractionStatus.MESSAGE_PROPERTY, "The model could not be reached");
        properties.put(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, "an-earlier-job");
        final DecisionChain chain = new DecisionChain();
        TestComponents.inject(this.handler, "requests", TestComponents.createRequests(this.jobManager, chain));
        TestComponents.inject(this.handler, "chain", chain);
        Mockito.when(this.jobManager.addJob(Mockito.anyString(), Mockito.anyMap()))
            .thenReturn(Mockito.mock(Job.class));
    }

    private WorkflowTaskContext createTask()
    {
        return TaskContexts.createContext(this.submission, Map.of(), new HashMap<>());
    }

    /** Runs the handler and commits its step, so the job it queues after the commit is queued. */
    private void executeAndCommit() throws Exception
    {
        final WorkflowTaskContext step = createTask();
        this.handler.execute(step);
        TaskContexts.commit(step);
    }

    private void verifyQueued(final String topic)
    {
        Mockito.verify(this.jobManager).addJob(topic, Map.of(ReadingJobs.SUBMISSION_PROPERTY,
            this.submission.getPath(), ReadingEvents.READING_ID_KEY,
            this.submission.getValueMap().get(ExtractionStatus.READING_ID_PROPERTY, String.class)));
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("readAgain", this.handler.getName());
    }

    // The categorisation never ran, such as when the model could not be reached
    @Test
    void runsTheDocumentChecksWhenACategoryWasNeverRead() throws Exception
    {
        executeAndCommit();

        verifyQueued(DecisionJobConsumer.JOB_TOPIC);
        final ValueMap recorded = this.submission.getValueMap();
        assertEquals(ExtractionStatus.STATUS_RUNNING, recorded.get(ExtractionStatus.STATUS_PROPERTY, String.class));
        assertNull(recorded.get(ExtractionStatus.MESSAGE_PROPERTY, String.class),
            "the old failure is no longer the news");
        assertFalse(recorded.containsKey(ExtractionStatus.READING_CLAIMED_BY_PROPERTY),
            "or the job would turn itself away");
    }

    @Test
    void runsTheDocumentChecksWhenAValidationHasNoVerdict() throws Exception
    {
        this.tree.createAnswer(this.category, "interventional");
        this.tree.createValidationRequirement("is_proposal", "proposal", "Is it a proposal?");

        executeAndCommit();

        verifyQueued(DecisionJobConsumer.JOB_TOPIC);
    }

    @Test
    void refusesUntilTheCategoryIsConfirmed()
    {
        // The model read the category before, and the submitter has not confirmed it yet
        this.tree.createExtractedAnswer(this.category, "interventional");

        final InvalidStateException refused =
            assertThrows(InvalidStateException.class, () -> this.handler.execute(createTask()));

        assertEquals(DecisionJobConsumer.CONFIRM_CATEGORY_MESSAGE, refused.getMessage());
    }

    @Test
    void readsTheAnswersOnceTheCategoryIsConfirmed() throws Exception
    {
        this.tree.createAnswer(this.category, "interventional");

        executeAndCommit();

        verifyQueued(MainExtractionJobConsumer.JOB_TOPIC);
    }

    @Test
    void refusesWhenEveryQuestionIsAnswered()
    {
        this.tree.createAnswer(this.category, "interventional");
        this.tree.createAnswer(this.context.resourceResolver().getResource(SubmissionTree.FORM_PATH + "/title"),
            "Sleep");

        final InvalidStateException refused =
            assertThrows(InvalidStateException.class, () -> this.handler.execute(createTask()));

        assertEquals(ReadAgainHandler.NOTHING_LEFT_MESSAGE, refused.getMessage());
    }

    // The intake has not taken the no yet: the next reading hands it over, without asking the model again
    @Test
    void readsAgainToHandOverANoTheIntakeHasNotTaken() throws Exception
    {
        this.tree.createVerdict(this.tree.createValidationRequirement("is_proposal", "proposal", "Is it one?"), "no");
        this.tree.createIntakeTask("is_proposal", "created", IntakeTasks.READER_USER);
        this.tree.createAnswer(this.category, "interventional");

        executeAndCommit();

        verifyQueued(DecisionJobConsumer.JOB_TOPIC);
    }

    // The shared checks are tested in ReadingRequestsTest; this one shows the handler asks them
    @Test
    void refusesAnybodyButTheSubmitter()
    {
        this.submission.adaptTo(ModifiableValueMap.class).put("createdBy", "a-reviewer");

        assertThrows(NotAuthorizedException.class, () -> this.handler.execute(createTask()));
    }

    @Test
    void refusesARequestAlreadySent()
    {
        this.submission.adaptTo(ModifiableValueMap.class).put("tags", new String[0]);

        assertThrows(InvalidStateException.class, () -> this.handler.execute(createTask()));
    }
}

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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link ReadingRequests}: the checks Next and Read again share, and how both queue a reading.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ReadingRequestsTest
{
    private static final String TOPIC = MainExtractionJobConsumer.JOB_TOPIC;

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final JobManager jobManager = Mockito.mock(JobManager.class);

    private ReadingRequests requests;

    private SubmissionTree tree;

    private Resource submission;

    private Resource file;

    @BeforeEach
    void setUp()
    {
        this.tree = new SubmissionTree(this.context);
        this.tree.createSchemaVersion();
        this.submission = this.tree.createSubmission();
        this.file = this.tree.createParsedFileFor(this.tree.createDocumentRequirement("proposal"),
            "A study of sleep in shift workers.");
        this.tree.setFormDocuments(SubmissionTree.FORM_PATH, "proposal");
        this.tree.createQuestion("title", "The study's full title.");
        final ModifiableValueMap properties = this.submission.adaptTo(ModifiableValueMap.class);
        properties.put(ExtractionStatus.STATUS_PROPERTY, ExtractionStatus.STATUS_FAILED);
        properties.put(ExtractionStatus.MESSAGE_PROPERTY, "The model could not be reached");
        properties.put(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, "an-earlier-job");
        properties.put(ExtractionStatus.READING_ID_PROPERTY, "an-earlier-reading");
        this.requests = TestComponents.createRequests(this.jobManager, new DecisionChain());
        Mockito.when(this.jobManager.addJob(Mockito.anyString(), Mockito.anyMap()))
            .thenReturn(Mockito.mock(Job.class));
    }

    private void checkMayRead(final String actor) throws Exception
    {
        this.requests.checkMayRead(this.submission, SubmissionFiles.getSubmission(this.submission), actor);
    }

    private WorkflowTaskContext createTask()
    {
        return TaskContexts.createContext(this.submission, Map.of(), new HashMap<>());
    }

    @Test
    void letsTheSubmitterAskWhileNothingIsRead()
    {
        assertDoesNotThrow(() -> checkMayRead("demo-researcher"));
    }

    @Test
    void refusesAnybodyButTheSubmitter()
    {
        assertThrows(NotAuthorizedException.class, () -> checkMayRead("a-reviewer"));
    }

    @Test
    void refusesWhileAReadingIsAlreadyGoing()
    {
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.STATUS_PROPERTY,
            ExtractionStatus.STATUS_RUNNING);

        assertThrows(InvalidStateException.class, () -> checkMayRead("demo-researcher"));
    }

    // The same document gets the same answer: only a new one helps
    @Test
    void refusesAfterADocumentWasRejected()
    {
        this.tree.createVerdict(this.tree.createValidationRequirement("is_proposal", "proposal", "Is it one?"), "no");
        this.tree.createIntakeTask("is_proposal", "completed", IntakeTasks.READER_USER);

        final InvalidStateException refused =
            assertThrows(InvalidStateException.class, () -> checkMayRead("demo-researcher"));

        assertEquals("Unable to identify the proposal", refused.getMessage());
    }

    // Nothing would be left to read only because the upload did not parse, so the submitter is told which one
    @Test
    void refusesWithTheReasonADocumentCannotBeRead()
    {
        this.file.adaptTo(ModifiableValueMap.class).put(ParsePropertyNames.PARSE_STATUS_PROPERTY,
            ParsePropertyNames.STATUS_FAILED);

        final InvalidStateException refused = assertThrows(InvalidStateException.class,
            () -> this.requests.checkFormsToRead(SubmissionFiles.getSubmission(this.submission)));

        assertEquals("The proposal could not be read, please try again", refused.getMessage());
    }

    // A job queued before the commit could run before the status it needs is saved
    @Test
    void queuesTheJobOnlyOnceTheStepCommits() throws Exception
    {
        final WorkflowTaskContext step = createTask();

        this.requests.queue(step, TOPIC);

        Mockito.verifyNoInteractions(this.jobManager);
        TaskContexts.commit(step);
        final ValueMap recorded = this.submission.getValueMap();
        final String readingId = recorded.get(ExtractionStatus.READING_ID_PROPERTY, String.class);
        Mockito.verify(this.jobManager).addJob(TOPIC, Map.of(ReadingJobs.SUBMISSION_PROPERTY,
            this.submission.getPath(), ReadingEvents.READING_ID_KEY, readingId));
        assertNotEquals("an-earlier-reading", readingId, "a job still waiting from before would take it over");
    }

    @Test
    void marksTheSubmissionRunningForTheNewReading() throws Exception
    {
        this.requests.queue(createTask(), TOPIC);

        final ValueMap recorded = this.submission.getValueMap();
        assertEquals(ExtractionStatus.STATUS_RUNNING, recorded.get(ExtractionStatus.STATUS_PROPERTY, String.class));
        assertFalse(recorded.containsKey(ExtractionStatus.MESSAGE_PROPERTY), "the old failure is no longer the news");
        assertFalse(recorded.containsKey(ExtractionStatus.READING_CLAIMED_BY_PROPERTY),
            "or the job would turn itself away");
    }

    // The step has committed by then, so the engine records the failure against it
    @Test
    void failsAfterTheCommitWhenTheReadingCannotBeQueued() throws Exception
    {
        Mockito.when(this.jobManager.addJob(Mockito.anyString(), Mockito.anyMap())).thenReturn(null);
        final WorkflowTaskContext step = createTask();
        this.requests.queue(step, TOPIC);

        assertThrows(IllegalStateException.class, () -> TaskContexts.commit(step));
    }
}

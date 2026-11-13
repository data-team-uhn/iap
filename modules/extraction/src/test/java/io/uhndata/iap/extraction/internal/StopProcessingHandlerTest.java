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

import java.util.List;
import java.util.Map;
import java.util.Set;

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

import io.uhndata.iap.documents.api.ParseControl;
import io.uhndata.iap.documents.api.ParseService;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link StopProcessingHandler}: what stopping a reading drops, and what it keeps.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class StopProcessingHandlerTest
{
    private static final String JOB_ID = "86a4c102-4b6a-4933-bc33-cc02e0e26eb7";

    private static final String STAGED = "/shared-docs/x/proposal.pdf";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final ParseControl parses = Mockito.mock(ParseControl.class);

    private final ParseService parseService = Mockito.mock(ParseService.class);

    private final JobManager jobManager = Mockito.mock(JobManager.class);

    private final ReadingRuns runs = new ReadingRuns();

    private final StopProcessingHandler handler = new StopProcessingHandler();

    private SubmissionTree tree;

    private Resource submission;

    @BeforeEach
    void setUp() throws Exception
    {
        this.tree = new SubmissionTree(this.context);
        this.tree.createSchemaVersion();
        this.submission = this.tree.createSubmission();
        TestComponents.inject(this.handler, "parses", this.parses);
        TestComponents.inject(this.handler, "parseService", this.parseService);
        TestComponents.inject(this.handler, "runs", this.runs);
        TestComponents.inject(this.handler, "jobManager", this.jobManager);
        // A stop only does something while a reading is running
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.STATUS_PROPERTY,
            ExtractionStatus.STATUS_RUNNING);
        Mockito.when(this.parses.stopParse(Mockito.any())).thenReturn(true);
        Mockito.when(this.parseService.discardStaging(Mockito.any())).thenReturn(true);
    }

    @Test
    void doesNothingWhenNoReadingIsGoing() throws Exception
    {
        final Resource file = this.tree.createFile("completed");
        this.tree.addMarkdown(file, "the protocol");
        final ModifiableValueMap properties = this.submission.adaptTo(ModifiableValueMap.class);
        properties.put(ExtractionStatus.STATUS_PROPERTY, ExtractionStatus.STATUS_DONE);
        properties.put(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, "an-earlier-job");

        stop();

        assertEquals(ExtractionStatus.STATUS_DONE,
            this.submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class));
        assertEquals("an-earlier-job",
            this.submission.getValueMap().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, String.class));
        assertEquals("completed", file.getValueMap().get("parseStatus", String.class), "a finished reading stays");
        assertNotNull(file.getChild("markdownFile"));
        Mockito.verifyNoInteractions(this.jobManager, this.parses, this.parseService);
    }

    @Test
    void refusesAnybodyButTheSubmitter()
    {
        this.submission.adaptTo(ModifiableValueMap.class).put("createdBy", "a-reviewer");

        assertThrows(NotAuthorizedException.class,
            () -> this.handler.execute(TaskContexts.createContext(this.submission, Map.of(), Map.of())));
    }

    // A job record still names the folder: the parse may have finished and be read in right now
    @Test
    void leavesTheFolderOfAParseThatAlreadyFinished() throws Exception
    {
        final Resource file = this.tree.createFile("queued");
        final ModifiableValueMap properties = file.adaptTo(ModifiableValueMap.class);
        properties.put(ParseDocumentsHandler.PARSE_JOB_ID_PROPERTY, JOB_ID);
        properties.put(ParseDocumentsHandler.SHARED_PATH_PROPERTY, STAGED);
        Mockito.when(this.parses.stopParse(JOB_ID)).thenReturn(false);

        stop();

        Mockito.verify(this.parseService, Mockito.never()).discardStaging(Mockito.any());
    }

    // No job record names this folder, so no sweep would ever find it
    @Test
    void wipesTheFolderOfAParseWhoseRecordIsGone() throws Exception
    {
        final Resource file = this.tree.createFile("queued");
        final ModifiableValueMap properties = file.adaptTo(ModifiableValueMap.class);
        properties.put(ParseDocumentsHandler.PARSE_JOB_ID_PROPERTY, JOB_ID);
        properties.put(ParseDocumentsHandler.SHARED_PATH_PROPERTY, STAGED);

        stop();

        Mockito.verify(this.parseService).discardStaging(STAGED);
        assertEquals("failed", file.getValueMap().get("parseStatus", String.class));
    }

    // Only the text the stopped call was reading is deleted; other documents keep theirs for the next try
    @Test
    void deletesOnlyTheMarkdownTheStoppedStepWasReading() throws Exception
    {
        final Resource read = this.tree.createFile("completed");
        this.tree.addMarkdown(read, "the questionnaire");
        final Resource other = this.tree.createFile("completed");
        this.tree.addMarkdown(other, "the preamble");

        stopWhileReading(Set.of(read.getPath()));

        assertNull(read.getChild("markdownFile"));
        assertNotNull(other.getChild("markdownFile"));
    }

    // Left completed with its text gone, the next reading would find nothing to read instead of parsing again
    @Test
    void marksTheDocumentTheStoppedStepWasReadingFailed() throws Exception
    {
        final Resource read = this.tree.createFile("completed");
        this.tree.addMarkdown(read, "the questionnaire");
        final Resource other = this.tree.createFile("completed");
        this.tree.addMarkdown(other, "the preamble");

        stopWhileReading(Set.of(read.getPath()));

        assertEquals("failed", read.getValueMap().get(ParsePropertyNames.PARSE_STATUS_PROPERTY, String.class));
        assertEquals("Stopped while the document was being read",
            read.getValueMap().get(ParsePropertyNames.PARSE_ERROR_PROPERTY, String.class));
        assertEquals("completed", other.getValueMap().get(ParsePropertyNames.PARSE_STATUS_PROPERTY, String.class));
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("stopProcessing", this.handler.getName());
    }

    @Test
    void removesTheQueuedReadingJobsOfTheSubmission() throws Exception
    {
        final Job answers = Mockito.mock(Job.class);
        Mockito.when(answers.getId()).thenReturn("answers-1");
        stubQueuedJobs(MainExtractionJobConsumer.JOB_TOPIC, answers);
        final Job validation = Mockito.mock(Job.class);
        Mockito.when(validation.getId()).thenReturn("validation-1");
        stubQueuedJobs(DecisionJobConsumer.JOB_TOPIC, validation);

        stop();

        Mockito.verify(this.jobManager).removeJobById("answers-1");
        Mockito.verify(this.jobManager).removeJobById("validation-1");
    }

    /** Queues the job for this submission only, so a lookup that leaves the submission out finds nothing. */
    @SuppressWarnings("unchecked")
    private void stubQueuedJobs(final String topic, final Job job)
    {
        Mockito.when(this.jobManager.findJobs(JobManager.QueryType.QUEUED, topic, 0L,
            Map.of(ReadingJobs.SUBMISSION_PROPERTY, this.submission.getPath()))).thenReturn(List.of(job));
    }

    @Test
    void marksAQueuedParseStopped() throws Exception
    {
        final Resource file = this.tree.createFile("queued");
        final ModifiableValueMap properties = file.adaptTo(ModifiableValueMap.class);
        properties.put(ParseDocumentsHandler.PARSE_JOB_ID_PROPERTY, JOB_ID);
        properties.put(ParseDocumentsHandler.SHARED_PATH_PROPERTY, STAGED);

        stop();

        Mockito.verify(this.parses).stopParse(JOB_ID);
        assertEquals("failed", file.getValueMap().get(ParsePropertyNames.PARSE_STATUS_PROPERTY, String.class));
        assertNull(file.getValueMap().get(ParseDocumentsHandler.PARSE_JOB_ID_PROPERTY, String.class));
    }

    @Test
    void endsTheReadingAsStopped() throws Exception
    {
        stop();

        assertEquals(ExtractionStatus.STATUS_FAILED,
            this.submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class));
        assertEquals(ExtractionStatus.STOPPED_MESSAGE,
            this.submission.getValueMap().get(ExtractionStatus.MESSAGE_PROPERTY, String.class));
    }

    // A claim left by a reading that ended, or by a restart, does not mean a model is reading anything
    @Test
    void keepsTheTextWhenNoReadingIsUnderWay() throws Exception
    {
        final Resource file = this.tree.createFile("completed");
        this.tree.addMarkdown(file, "the protocol");
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.READING_CLAIMED_BY_PROPERTY,
            "an-earlier-job");

        stop();

        assertNotNull(file.getChild("markdownFile"));
        assertEquals("completed", file.getValueMap().get("parseStatus", String.class));
        assertNull(this.submission.getValueMap().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, String.class));
        Mockito.verifyNoInteractions(this.parses);
    }

    // A reading that has not reached a model call yet has read nothing, so the text stays
    @Test
    void keepsTheTextWhenTheReadingHasNotAskedYet() throws Exception
    {
        final Resource file = this.tree.createFile("completed");
        this.tree.addMarkdown(file, "the protocol");

        stopWhileReading(Set.of());

        assertNotNull(file.getChild("markdownFile"));
    }

    @Test
    void tellsTheThreadThatIsReadingToStop() throws Exception
    {
        this.runs.begin(this.submission.getPath());
        try {
            stop();
            assertThrows(ReadingStoppedException.class, ReadingRuns::enterModelCall);
        } finally {
            this.runs.end();
            Thread.interrupted();
        }
    }

    private void stop() throws Exception
    {
        final WorkflowTaskContext step = TaskContexts.createContext(this.submission, Map.of(), Map.of());
        this.handler.execute(step);
        TaskContexts.commit(step);
    }

    /**
     * Stops while a model call reads the given files.
     *
     * @param files the files the call reads; none for a reading that has not asked yet
     */
    private void stopWhileReading(final Set<String> files) throws Exception
    {
        this.runs.begin(this.submission.getPath());
        if (!files.isEmpty()) {
            this.runs.setFiles(this.submission.getPath(), files);
        }
        try {
            stop();
        } finally {
            this.runs.end();
            // The stop interrupts the reading thread, which is the test's own thread here
            Thread.interrupted();
        }
    }
}

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
        inject("parses", this.parses);
        inject("parseService", this.parseService);
        inject("runs", this.runs);
        inject("jobManager", this.jobManager);
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
        properties.put(ExtractionStatus.READING_CLAIMED_PROPERTY, Boolean.TRUE);

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of()));

        assertEquals(ExtractionStatus.STATUS_DONE,
            this.submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class));
        assertEquals("completed", file.getValueMap().get("parseStatus", String.class), "a finished reading stays");
    }

    @Test
    void refusesAnybodyButTheSubmitter()
    {
        this.submission.adaptTo(ModifiableValueMap.class).put("createdBy", "a-reviewer");

        assertThrows(NotAuthorizedException.class,
            () -> this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of())));
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

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of()));

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

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of()));

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
        this.runs.begin(this.submission.getPath());
        this.runs.setFiles(this.submission.getPath(), Set.of(read.getPath()));
        try {
            this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of()));
        } finally {
            this.runs.end();
            Thread.interrupted();
        }

        assertNull(read.getChild("markdownFile"));
        assertNotNull(other.getChild("markdownFile"));
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("stopProcessing", this.handler.getName());
    }

    @Test
    @SuppressWarnings("unchecked")
    void dropsAnOpenParseAndTheReadingWaitingOnIt() throws Exception
    {
        final Resource file = this.tree.createFile("queued");
        final ModifiableValueMap properties = file.adaptTo(ModifiableValueMap.class);
        properties.put(ParseDocumentsHandler.PARSE_JOB_ID_PROPERTY, JOB_ID);
        properties.put(ParseDocumentsHandler.SHARED_PATH_PROPERTY, STAGED);
        final Job answers = Mockito.mock(Job.class);
        Mockito.when(answers.getId()).thenReturn("answers-1");
        Mockito.when(this.jobManager.findJobs(Mockito.eq(JobManager.QueryType.QUEUED),
            Mockito.eq(MainExtractionJobConsumer.JOB_TOPIC), Mockito.eq(0L), Mockito.anyMap()))
            .thenReturn(List.of(answers));
        final Job validation = Mockito.mock(Job.class);
        Mockito.when(validation.getId()).thenReturn("validation-1");
        Mockito.when(this.jobManager.findJobs(Mockito.eq(JobManager.QueryType.QUEUED),
            Mockito.eq(DecisionJobConsumer.JOB_TOPIC), Mockito.eq(0L), Mockito.anyMap()))
            .thenReturn(List.of(validation));

        // The record still names the folder, so the sweep removes both together
        Mockito.when(this.parses.stopParse(JOB_ID)).thenReturn(false);

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of()));

        Mockito.verify(this.parses).stopParse(JOB_ID);
        Mockito.verify(this.parseService, Mockito.never()).discardStaging(Mockito.any());
        Mockito.verify(this.jobManager).removeJobById("answers-1");
        Mockito.verify(this.jobManager).removeJobById("validation-1");
        assertEquals("failed", file.getValueMap().get("parseStatus", String.class));
        assertNull(file.getValueMap().get(ParseDocumentsHandler.PARSE_JOB_ID_PROPERTY, String.class));
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
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.READING_CLAIMED_PROPERTY, Boolean.TRUE);

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of()));

        assertNotNull(file.getChild("markdownFile"));
        assertEquals("completed", file.getValueMap().get("parseStatus", String.class));
        assertNull(this.submission.getValueMap().get(ExtractionStatus.READING_CLAIMED_PROPERTY, Boolean.class));
        Mockito.verifyNoInteractions(this.parses);
    }

    // A reading that has not reached a model call yet has read nothing, so the text stays
    @Test
    void keepsTheTextWhenTheReadingHasNotAskedYet() throws Exception
    {
        final Resource file = this.tree.createFile("completed");
        this.tree.addMarkdown(file, "the protocol");
        this.runs.begin(this.submission.getPath());
        try {
            this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of()));
        } finally {
            this.runs.end();
            Thread.interrupted();
        }

        assertNotNull(file.getChild("markdownFile"));
    }

    @Test
    void leavesMarkdownThatHasNotBeenReadYet() throws Exception
    {
        final Resource file = this.tree.createFile("completed");
        this.tree.addMarkdown(file, "the protocol");

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of()));

        assertEquals("the protocol", file.getChild("markdownFile") == null ? null : "the protocol");
        assertEquals("completed", file.getValueMap().get("parseStatus", String.class));
    }

    @Test
    void releasesTheClaimButKeepsTheParseStatusWhenThereIsNoMarkdown() throws Exception
    {
        final Resource file = this.tree.createFile("completed");
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.READING_CLAIMED_PROPERTY, Boolean.TRUE);

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of()));

        assertEquals("completed", file.getValueMap().get("parseStatus", String.class));
        assertNull(this.submission.getValueMap().get(ExtractionStatus.READING_CLAIMED_PROPERTY, Boolean.class));
    }

    @Test
    void aStoppedThreadCannotBeginAnotherReading()
    {
        this.runs.begin(this.submission.getPath());
        this.runs.stop(this.submission.getPath());

        assertThrows(ReadingStoppedException.class, () -> this.runs.begin(this.submission.getPath()));
        this.runs.end();
    }

    @Test
    void tellsTheThreadThatIsReadingToStop() throws Exception
    {
        this.runs.begin(this.submission.getPath());
        try {
            this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of()));
            assertThrows(ReadingStoppedException.class, ReadingRuns::enterModelCall);
        } finally {
            this.runs.end();
            Thread.interrupted();
        }
    }

    private void inject(final String name, final Object value) throws Exception
    {
        final Field field = StopProcessingHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(this.handler, value);
    }
}

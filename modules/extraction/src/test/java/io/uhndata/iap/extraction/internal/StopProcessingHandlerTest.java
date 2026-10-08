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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link StopProcessingHandler}.
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

    private final ParsedDocuments documents = new ParsedDocuments();

    private final StopProcessingHandler handler = new StopProcessingHandler();

    private SubmissionTree tree;

    private Resource submission;

    @BeforeEach
    void setUp() throws Exception
    {
        this.tree = new SubmissionTree(this.context);
        this.tree.schemaVersion();
        this.submission = this.tree.submission();
        inject("parses", this.parses);
        inject("parseService", this.parseService);
        inject("runs", this.runs);
        inject("jobManager", this.jobManager);
        inject("documents", this.documents);
        // A stop only does anything while a reading is going
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.PROPERTY, ExtractionStatus.RUNNING);
        Mockito.when(this.parses.abandon(Mockito.any())).thenReturn(true);
        Mockito.when(this.parseService.discardStaging(Mockito.any())).thenReturn(true);
    }

    @Test
    void doesNothingWhenNoReadingIsGoing() throws Exception
    {
        final Resource file = this.tree.file("completed");
        this.tree.markdown(file, "the protocol");
        final ModifiableValueMap properties = this.submission.adaptTo(ModifiableValueMap.class);
        properties.put(ExtractionStatus.PROPERTY, ExtractionStatus.DONE);
        properties.put(ExtractionStatus.READING_CLAIMED, Boolean.TRUE);

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of()));

        assertEquals(ExtractionStatus.DONE, this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
        assertEquals("completed", file.getValueMap().get("parseStatus", String.class), "a finished reading stays");
    }

    @Test
    void refusesAnybodyButTheSubmitter()
    {
        this.submission.adaptTo(ModifiableValueMap.class).put("createdBy", "a-reviewer");

        assertThrows(NotAuthorizedException.class,
            () -> this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of())));
    }

    // The daemon already finished it, and the folder is being read in right now
    @Test
    void leavesTheFolderOfAParseThatAlreadyFinished() throws Exception
    {
        final Resource file = this.tree.file("queued");
        final ModifiableValueMap properties = file.adaptTo(ModifiableValueMap.class);
        properties.put(ParseDocumentsHandler.PARSE_JOB_ID, JOB_ID);
        properties.put(ParseDocumentsHandler.SHARED_PATH, STAGED);
        Mockito.when(this.parses.abandon(JOB_ID)).thenReturn(false);

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of()));

        Mockito.verify(this.parseService, Mockito.never()).discardStaging(Mockito.any());
    }

    // Only the text the call was reading goes; another document's stays for the next try
    @Test
    void deletesOnlyTheMarkdownTheStoppedStepWasReading() throws Exception
    {
        final Resource read = this.tree.file("completed");
        this.tree.markdown(read, "the questionnaire");
        final Resource other = this.tree.file("completed");
        this.tree.markdown(other, "the preamble");
        this.runs.begin(this.submission.getPath());
        this.runs.setFiles(this.submission.getPath(), Set.of(read.getPath()));
        try {
            this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of()));
        } finally {
            this.runs.end(this.submission.getPath());
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
        final Resource file = this.tree.file("queued");
        final ModifiableValueMap properties = file.adaptTo(ModifiableValueMap.class);
        properties.put(ParseDocumentsHandler.PARSE_JOB_ID, JOB_ID);
        properties.put(ParseDocumentsHandler.SHARED_PATH, STAGED);
        final Job waiting = Mockito.mock(Job.class);
        Mockito.when(waiting.getId()).thenReturn("reading-1");
        Mockito.when(this.jobManager.findJobs(Mockito.eq(JobManager.QueryType.QUEUED),
            Mockito.eq(ExtractAnswersJobConsumer.TOPIC), Mockito.eq(0L), Mockito.anyMap()))
            .thenReturn(List.of(waiting));

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of()));

        Mockito.verify(this.parses).abandon(JOB_ID);
        Mockito.verify(this.parseService).discardStaging(STAGED);
        Mockito.verify(this.jobManager).removeJobById("reading-1");
        assertEquals("failed", file.getValueMap().get("parseStatus", String.class));
        assertNull(file.getValueMap().get(ParseDocumentsHandler.PARSE_JOB_ID, String.class));
        assertEquals(ExtractionStatus.FAILED,
            this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
        assertEquals(ExtractionStatus.STOPPED,
            this.submission.getValueMap().get(ExtractionStatus.MESSAGE, String.class));
    }

    // A claim left behind by a reading that ended, or by a restart, is not a model reading anything
    @Test
    void keepsTheTextWhenNoReadingIsUnderWay() throws Exception
    {
        final Resource file = this.tree.file("completed");
        this.tree.markdown(file, "the protocol");
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.READING_CLAIMED, Boolean.TRUE);

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of()));

        assertNotNull(file.getChild("markdownFile"));
        assertEquals("completed", file.getValueMap().get("parseStatus", String.class));
        assertNull(this.submission.getValueMap().get(ExtractionStatus.READING_CLAIMED, Boolean.class));
        Mockito.verifyNoInteractions(this.parses);
    }

    // A reading that has not reached a model call yet has read nothing
    @Test
    void keepsTheTextWhenTheReadingHasNotAskedYet() throws Exception
    {
        final Resource file = this.tree.file("completed");
        this.tree.markdown(file, "the protocol");
        this.runs.begin(this.submission.getPath());
        try {
            this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of()));
        } finally {
            this.runs.end(this.submission.getPath());
            Thread.interrupted();
        }

        assertNotNull(file.getChild("markdownFile"));
    }

    @Test
    void leavesMarkdownThatHasNotBeenReadYet() throws Exception
    {
        final Resource file = this.tree.file("completed");
        this.tree.markdown(file, "the protocol");

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of()));

        assertEquals("the protocol", file.getChild("markdownFile") == null ? null : "the protocol");
        assertEquals("completed", file.getValueMap().get("parseStatus", String.class));
    }

    @Test
    void aClaimedReadingWithNoMarkdownIsLeftAsItWas() throws Exception
    {
        final Resource file = this.tree.file("completed");
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.READING_CLAIMED, Boolean.TRUE);

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of()));

        assertEquals("completed", file.getValueMap().get("parseStatus", String.class));
        assertNull(this.submission.getValueMap().get(ExtractionStatus.READING_CLAIMED, Boolean.class));
    }

    @Test
    void aStoppedThreadCannotBeginAnotherReading()
    {
        Thread.currentThread().interrupt();
        try {
            assertThrows(ReadingRuns.Stopped.class, () -> this.runs.begin(this.submission.getPath()));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptsTheThreadThatIsAskingTheModel() throws Exception
    {
        this.runs.begin(this.submission.getPath());
        try {
            this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of()));
            assertTrue(Thread.interrupted());
        } finally {
            this.runs.end(this.submission.getPath());
            this.runs.end(this.submission.getPath());
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

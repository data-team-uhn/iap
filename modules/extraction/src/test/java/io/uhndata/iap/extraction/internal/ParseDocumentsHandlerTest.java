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

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.event.jobs.JobManager;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.documents.api.ParseService;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.errortracking.api.ErrorLoggerService;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ParseDocumentsHandler}: which uploads are staged and queued for parsing.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ParseDocumentsHandlerTest
{
    private static final String STAGED = "/shared-docs/x/proposal.pdf";

    private static final String JOB_ID = "86a4c102-4b6a-4933-bc33-cc02e0e26eb7";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final ParseService parseService = Mockito.mock(ParseService.class);

    private final ParseDocumentsHandler handler = new ParseDocumentsHandler();

    private SubmissionTree tree;

    private Resource submission;

    @BeforeEach
    void setUp() throws Exception
    {
        this.tree = new SubmissionTree(this.context);
        this.tree.createSchemaVersion();
        this.submission = this.tree.createSubmission();
        TestComponents.inject(this.handler, "parseService", this.parseService);
        final DecisionChain chain = new DecisionChain();
        TestComponents.inject(this.handler, "requests",
            TestComponents.createRequests(Mockito.mock(JobManager.class), chain));
        TestComponents.inject(this.handler, "chain", chain);
        this.tree.createDocumentRequirement("proposal");
        this.tree.setFormDocuments(SubmissionTree.FORM_PATH, "proposal");
        Mockito.when(this.parseService.stage(Mockito.eq("proposal.pdf"), Mockito.any())).thenReturn(STAGED);
        Mockito.when(this.parseService.queue(Mockito.eq(STAGED), Mockito.anyString()))
            .thenReturn(JOB_ID);
    }

    private WorkflowTaskContext createTask(final Resource target)
    {
        return TaskContexts.createContext(target, Map.of(), new HashMap<>());
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("parseDocuments", this.handler.getName());
    }

    @Test
    void refusesAnybodyButTheSubmitter()
    {
        this.tree.createUnparsedFile();
        this.submission.adaptTo(ModifiableValueMap.class).put("createdBy", "a-reviewer");

        assertThrows(NotAuthorizedException.class, () -> this.handler.execute(createTask(this.submission)));
        Mockito.verifyNoInteractions(this.parseService);
    }

    @Test
    void refusesARequestAlreadySent()
    {
        this.tree.createUnparsedFile();
        this.submission.adaptTo(ModifiableValueMap.class).put("tags", new String[0]);

        assertThrows(InvalidStateException.class, () -> this.handler.execute(createTask(this.submission)));
        Mockito.verifyNoInteractions(this.parseService);
    }

    // A validation said no: parsing again would reopen a request the intake already ended
    @Test
    void refusesARejectedRequest()
    {
        this.tree.createUnparsedFile();
        this.tree.createVerdict(this.tree.createValidationRequirement("is_proposal", "proposal", "Is it one?"), "no");
        this.tree.createIntakeTask("is_proposal", "completed", IntakeTasks.READER_USER);

        final InvalidStateException refusal =
            assertThrows(InvalidStateException.class, () -> this.handler.execute(createTask(this.submission)));

        assertEquals("Unable to identify the proposal", refusal.getMessage());
        Mockito.verifyNoInteractions(this.parseService);
    }

    // A reading started now could only fail with "nothing is set up"
    @Test
    void parsesNothingWhenNothingReadsTheDocuments() throws Exception
    {
        this.tree.setFormDocuments(SubmissionTree.FORM_PATH);
        this.tree.createUnparsedFile();

        this.handler.execute(createTask(this.submission));

        Mockito.verifyNoInteractions(this.parseService);
        assertNull(this.submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class));
    }

    // The check may apply once the submitter answers, and its upload must be parsed by then
    @Test
    void parsesADocumentOnlyACheckNotApplyingYetReads() throws Exception
    {
        this.tree.setFormDocuments(SubmissionTree.FORM_PATH);
        this.tree.createValidationRequirement("is_proposal", "proposal", "Is it a proposal?");
        this.context.registerService(ConditionEvaluator.class, Mockito.mock(ConditionEvaluator.class));
        final Resource file = this.tree.createUnparsedFile();

        this.handler.execute(createTask(this.submission));

        Mockito.verify(this.parseService).queue(STAGED, file.getPath());
    }

    @Test
    void stagesAndQueuesEveryUploadNotParsedYet() throws Exception
    {
        final Resource file = this.tree.createUnparsedFile();

        this.handler.execute(createTask(this.submission));

        Mockito.verify(this.parseService).queue(STAGED, file.getPath());
        assertEquals("queued", file.getValueMap().get("parseStatus", String.class));
        assertEquals(JOB_ID, file.getValueMap().get(ParseDocumentsHandler.PARSE_JOB_ID_PROPERTY, String.class));
        assertEquals(STAGED, file.getValueMap().get(ParseDocumentsHandler.SHARED_PATH_PROPERTY, String.class));
        assertEquals("running", this.submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class),
            "the submission is now being read");
        assertNotNull(this.submission.getValueMap().get(ExtractionStatus.READING_ID_PROPERTY, String.class),
            "the checks this parse queues carry the new reading's id");
    }

    @Test
    void stagesAFileWithNoFileNameUnderItsDocumentTitle() throws Exception
    {
        final Resource file = this.tree.createUnparsedFile();
        file.adaptTo(ModifiableValueMap.class).remove(ParseDocumentsHandler.FILE_NAME_PROPERTY);
        file.getParent().getParent().adaptTo(ModifiableValueMap.class).put("title", "protocol.docx");
        Mockito.when(this.parseService.stage(Mockito.eq("protocol.docx"), Mockito.any())).thenReturn(STAGED);

        this.handler.execute(createTask(this.submission));

        Mockito.verify(this.parseService).stage(Mockito.eq("protocol.docx"), Mockito.any());
        Mockito.verify(this.parseService).queue(STAGED, file.getPath());
    }

    @Test
    void leavesAFileAlreadySentAlone() throws Exception
    {
        this.tree.createFile("completed");

        this.handler.execute(createTask(this.submission));

        Mockito.verifyNoInteractions(this.parseService);
        assertNull(this.submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class),
            "nothing was queued, so nothing is being read");
    }

    @Test
    void sendsAFailedParseAgain() throws Exception
    {
        final Resource file = this.tree.createFile("failed");
        file.adaptTo(ModifiableValueMap.class).put("parseError", "The daemon could not be reached");

        this.handler.execute(createTask(this.submission));

        Mockito.verify(this.parseService).queue(STAGED, file.getPath());
        assertEquals("queued", file.getValueMap().get("parseStatus", String.class));
        assertNull(file.getValueMap().get("parseError", String.class),
            "the last failure is not what is wrong with this file any more");
        assertEquals("running", this.submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class),
            "the submission is being read again");
    }

    @Test
    void leavesAnUploadWhoseRequirementSkipsParsingAlone() throws Exception
    {
        final Resource file = this.tree.createUnparsedFile();
        final Resource requirement = this.tree.createDocumentRequirement("signedForm");
        requirement.adaptTo(ModifiableValueMap.class).put("skipParsing", true);
        SubmissionTree.setReference(file.getParent().getParent(), "fulfills", requirement);

        this.handler.execute(createTask(this.submission));

        Mockito.verifyNoInteractions(this.parseService);
        assertNull(file.getValueMap().get("parseStatus", String.class));
        assertNull(this.submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class),
            "nothing was queued, so nothing is being read");
    }

    @Test
    void sendsAnUploadWhoseRequirementKeepsTheDefault() throws Exception
    {
        final Resource file = this.tree.createUnparsedFile();
        SubmissionTree.setReference(file.getParent().getParent(), "fulfills",
            this.tree.createDocumentRequirement("proposal"));

        this.handler.execute(createTask(this.submission));

        Mockito.verify(this.parseService).queue(STAGED, file.getPath());
    }

    @Test
    void skipsADocumentWithNoUpload() throws Exception
    {
        this.tree.createEmptyDocumentVersion();

        this.handler.execute(createTask(this.submission));

        Mockito.verifyNoInteractions(this.parseService);
    }

    // Counted as queued, it would start a reading that no parse ever ends
    @Test
    void skipsAFileWithNoUpload() throws Exception
    {
        final Resource version = this.tree.createEmptyDocumentVersion();
        final Resource file = this.context.create().resource(version.getPath() + "/file",
            Map.of("sling:resourceType", "sub/File"));
        final ErrorLoggerService errors = Mockito.mock(ErrorLoggerService.class);
        ErrorLogger.setService(errors);
        try {
            this.handler.execute(createTask(this.submission));
        } finally {
            ErrorLogger.unsetService(errors);
        }

        Mockito.verifyNoInteractions(this.parseService);
        assertNull(this.submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class),
            "nothing was queued, so nothing is being read");
        Mockito.verify(errors).logProblem(Mockito.anyString(),
            Mockito.argThat(errorContext -> file.getPath().equals(errorContext.getSubject())));
    }

    @Test
    void translatesAStagingFailureIntoAPersistenceFailure() throws Exception
    {
        this.tree.createUnparsedFile();
        Mockito.when(this.parseService.stage(Mockito.any(), Mockito.any())).thenThrow(new IOException("disk full"));

        final PersistenceException failure =
            assertThrows(PersistenceException.class, () -> this.handler.execute(createTask(this.submission)));

        assertTrue(failure.getMessage().contains("disk full"), failure.getMessage());
        // A stage that failed partway names no copy, so there is nothing to delete
        Mockito.verify(this.parseService).discardStaging(null);
    }

    // The copy would stay on the shared volume with no job node naming it, so no sweep would find it
    @Test
    void deletesTheStagedCopyWhenTheParseCannotBeQueued() throws Exception
    {
        final Resource file = this.tree.createUnparsedFile();
        Mockito.when(this.parseService.queue(Mockito.eq(STAGED), Mockito.anyString()))
            .thenThrow(new IOException("no job node"));

        assertThrows(PersistenceException.class, () -> this.handler.execute(createTask(this.submission)));

        Mockito.verify(this.parseService).discardStaging(STAGED);
        assertNull(file.getValueMap().get(ParsePropertyNames.PARSE_STATUS_PROPERTY, String.class));
        assertNull(file.getValueMap().get(ParseDocumentsHandler.PARSE_JOB_ID_PROPERTY, String.class));
    }

    @Test
    void refusesAFileItCannotWrite()
    {
        this.tree.createUnparsedFile();
        assertThrows(PersistenceException.class, () -> this.handler.execute(
            createTask(SubmissionTree.wrapResolverReadOnly(this.submission))));
    }
}

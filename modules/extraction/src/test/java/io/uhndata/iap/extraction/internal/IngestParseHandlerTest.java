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
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import javax.jcr.version.VersionManager;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link IngestParseHandler}: saving a finished parse on its file, or saving that it failed.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class IngestParseHandlerTest
{
    private static final String MARKDOWN = "/shared-docs/x/proposal.md";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final ParseResultIngester ingester = Mockito.mock(ParseResultIngester.class);

    private final IngestParseHandler handler = new IngestParseHandler();

    private Resource submission;

    private Resource file;

    @BeforeEach
    void setUp() throws Exception
    {
        final SubmissionTree tree = new SubmissionTree(this.context);
        tree.createSchemaVersion();
        this.submission = tree.createSubmission();
        this.file = tree.createFile(ParsePropertyNames.STATUS_QUEUED);
        TestComponents.inject(this.handler, "ingester", this.ingester);
        Mockito.doCallRealMethod().when(this.ingester).recordFailedParse(Mockito.any(), Mockito.any());
    }

    private Map<String, Object> buildParsedPayload(final String filePath)
    {
        final Map<String, Object> payload = new HashMap<>();
        payload.put(ParseCompletionHandler.FILE_KEY, filePath);
        payload.put(ParseCompletionHandler.SUCCEEDED_KEY, Boolean.TRUE);
        payload.put(ParseCompletionHandler.MARKDOWN_KEY, MARKDOWN);
        return payload;
    }

    private Map<String, Object> buildFailedPayload(final String filePath, final String error)
    {
        final Map<String, Object> payload = new HashMap<>();
        payload.put(ParseCompletionHandler.FILE_KEY, filePath);
        payload.put(ParseCompletionHandler.SUCCEEDED_KEY, Boolean.FALSE);
        if (error != null) {
            payload.put(ParseCompletionHandler.ERROR_KEY, error);
        }
        return payload;
    }

    private String getFileProperty(final String name)
    {
        return this.file.getValueMap().get(name, String.class);
    }

    private WorkflowTaskContext createTask(final Map<String, Object> payload)
    {
        return TaskContexts.createContext(this.submission, payload, new HashMap<>());
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("ingestParse", this.handler.getName());
    }

    @Test
    void readsAFinishedParseOntoTheFile() throws Exception
    {
        final Map<String, Object> payload = buildParsedPayload(this.file.getPath());
        payload.put(ParseCompletionHandler.PDF_KEY, "/shared-docs/x/proposal.pdf");
        payload.put(ParseCompletionHandler.TOKENS_KEY, 4200L);

        this.handler.execute(createTask(payload));

        Mockito.verify(this.ingester).ingest(
            Mockito.argThat(resource -> this.file.getPath().equals(resource.getPath())),
            Mockito.eq(Path.of(MARKDOWN)), Mockito.eq(Path.of("/shared-docs/x/proposal.pdf")),
            Mockito.eq(4200L));
    }

    @Test
    void passesNoPathsForWhatTheParseDidNotProduce() throws Exception
    {
        this.handler.execute(createTask(buildParsedPayload(this.file.getPath())));

        Mockito.verify(this.ingester).ingest(Mockito.any(), Mockito.eq(Path.of(MARKDOWN)), Mockito.isNull(),
            Mockito.isNull());
    }

    @Test
    void recordsAFailedParseOnTheFile() throws Exception
    {
        this.handler.execute(createTask(buildFailedPayload(this.file.getPath(), "No pages")));

        assertEquals(ParsePropertyNames.STATUS_FAILED, getFileProperty(ParsePropertyNames.PARSE_STATUS_PROPERTY));
        assertEquals("No pages", getFileProperty(ParsePropertyNames.PARSE_ERROR_PROPERTY));
        Mockito.verify(this.ingester, Mockito.never()).ingest(Mockito.any(), Mockito.any(), Mockito.any(),
            Mockito.any());
    }

    @Test
    void recordsAFailureWithoutDetailsAsSuch() throws Exception
    {
        this.handler.execute(createTask(buildFailedPayload(this.file.getPath(), null)));

        assertEquals("The parse failed without details", getFileProperty(ParsePropertyNames.PARSE_ERROR_PROPERTY));
    }

    @Test
    void refusesAnEventThatNamesNoFile()
    {
        final Map<String, Object> payload = buildParsedPayload(this.file.getPath());
        payload.remove(ParseCompletionHandler.FILE_KEY);

        assertThrows(InvalidPayloadException.class, () -> this.handler.execute(createTask(payload)));
    }

    @Test
    void refusesAFileThatIsNotThere()
    {
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(createTask(buildParsedPayload(SubmissionTree.SUBMISSION_PATH + "/d9/v1/file"))));
    }

    @Test
    void refusesANodeThatIsNotAFile()
    {
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(createTask(buildParsedPayload(this.submission.getPath()))));
    }

    @Test
    void refusesASuccessThatNamesNoMarkdown()
    {
        final Map<String, Object> payload = buildParsedPayload(this.file.getPath());
        payload.remove(ParseCompletionHandler.MARKDOWN_KEY);

        assertThrows(InvalidPayloadException.class, () -> this.handler.execute(createTask(payload)));
    }

    @Test
    void translatesAnUnreadableParseIntoAPersistenceFailure() throws Exception
    {
        final IOException gone = new IOException("the markdown is gone");
        Mockito.doThrow(gone).when(this.ingester).ingest(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());

        final PersistenceException failure = assertThrows(PersistenceException.class,
            () -> this.handler.execute(createTask(buildParsedPayload(this.file.getPath()))));

        assertSame(gone, failure.getCause());
    }

    @Test
    void refusesToRecordAFailureOnAFileItCannotWrite()
    {
        final Map<String, Object> payload = buildFailedPayload(this.file.getPath(), null);
        final Resource readOnlyTarget = SubmissionTree.wrapResolverReadOnly(this.submission);

        assertThrows(PersistenceException.class,
            () -> this.handler.execute(TaskContexts.createContext(readOnlyTarget, payload, new HashMap<>())));
    }

    // A checked-in submission is read-only, with every file under it
    @Test
    void checksOutACheckedInSubmissionBeforeWriting() throws Exception
    {
        final VersionManager versions = Mockito.mock(VersionManager.class);
        final Resource checkedIn = SubmissionTree.wrapCheckedIn(this.submission, versions);

        this.handler.execute(TaskContexts.createContext(checkedIn, buildFailedPayload(this.file.getPath(), "No pages"),
            new HashMap<>()));

        Mockito.verify(versions).checkout(this.submission.getPath());
    }
}

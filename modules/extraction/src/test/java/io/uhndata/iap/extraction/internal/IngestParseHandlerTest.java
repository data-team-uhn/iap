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
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link IngestParseHandler}.
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
        tree.schemaVersion();
        this.submission = tree.submission();
        this.file = tree.file("active");
        final Field field = IngestParseHandler.class.getDeclaredField("ingester");
        field.setAccessible(true);
        field.set(this.handler, this.ingester);
    }

    private Map<String, Object> parsed(final String filePath)
    {
        final Map<String, Object> payload = new HashMap<>();
        payload.put(ParseCompletionHandler.FILE, filePath);
        payload.put(ParseCompletionHandler.SUCCEEDED, Boolean.TRUE);
        payload.put(ParseCompletionHandler.MARKDOWN, MARKDOWN);
        return payload;
    }

    private WorkflowTaskContext task(final Map<String, Object> payload)
    {
        return TaskContexts.of(this.submission, payload, new HashMap<>());
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("ingestParse", this.handler.getName());
    }

    @Test
    void readsAFinishedParseOntoTheFile() throws Exception
    {
        final Map<String, Object> payload = parsed(this.file.getPath());
        payload.put(ParseCompletionHandler.PDF, "/shared-docs/x/proposal.pdf");
        payload.put(ParseCompletionHandler.TOKENS, 4200L);

        this.handler.execute(task(payload));

        Mockito.verify(this.ingester).ingest(
            Mockito.argThat(resource -> this.file.getPath().equals(resource.getPath())),
            Mockito.eq(Path.of(MARKDOWN)), Mockito.eq(Path.of("/shared-docs/x/proposal.pdf")),
            Mockito.eq(4200L));
    }

    @Test
    void passesNoPathsForWhatTheParseDidNotProduce() throws Exception
    {
        this.handler.execute(task(parsed(this.file.getPath())));

        Mockito.verify(this.ingester).ingest(Mockito.any(), Mockito.eq(Path.of(MARKDOWN)), Mockito.isNull(),
            Mockito.isNull());
    }

    @Test
    void recordsAFailedParseOnTheFile() throws Exception
    {
        final Map<String, Object> payload = new HashMap<>();
        payload.put(ParseCompletionHandler.FILE, this.file.getPath());
        payload.put(ParseCompletionHandler.SUCCEEDED, Boolean.FALSE);
        payload.put(ParseCompletionHandler.ERROR, "No pages");

        this.handler.execute(task(payload));

        assertEquals("failed", this.file.getValueMap().get("parseStatus", String.class));
        assertEquals("No pages", this.file.getValueMap().get("parseError", String.class));
        Mockito.verify(this.ingester, Mockito.never()).ingest(Mockito.any(), Mockito.any(), Mockito.any(),
            Mockito.any());
    }

    @Test
    void recordsAFailureWithoutDetailsAsSuch() throws Exception
    {
        final Map<String, Object> payload = new HashMap<>();
        payload.put(ParseCompletionHandler.FILE, this.file.getPath());
        payload.put(ParseCompletionHandler.SUCCEEDED, Boolean.FALSE);

        this.handler.execute(task(payload));

        assertEquals("The parse failed without details", this.file.getValueMap().get("parseError", String.class));
    }

    @Test
    void refusesAnEventThatNamesNoFile()
    {
        final Map<String, Object> payload = parsed(this.file.getPath());
        payload.remove(ParseCompletionHandler.FILE);

        assertThrows(InvalidPayloadException.class, () -> this.handler.execute(task(payload)));
    }

    @Test
    void refusesAFileThatIsNotThere()
    {
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(task(parsed(SubmissionTree.SUBMISSION_PATH + "/d9/v1/file"))));
    }

    @Test
    void refusesANodeThatIsNotAFile()
    {
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(task(parsed(this.submission.getPath()))));
    }

    @Test
    void refusesASuccessThatNamesNoMarkdown()
    {
        final Map<String, Object> payload = parsed(this.file.getPath());
        payload.remove(ParseCompletionHandler.MARKDOWN);

        assertThrows(InvalidPayloadException.class, () -> this.handler.execute(task(payload)));
    }

    @Test
    void translatesAnUnreadableParseIntoAPersistenceFailure() throws Exception
    {
        Mockito.doThrow(new IOException("the markdown is gone")).when(this.ingester)
            .ingest(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());

        final PersistenceException failure = assertThrows(PersistenceException.class,
            () -> this.handler.execute(task(parsed(this.file.getPath()))));

        assertTrue(failure.getMessage().contains("the markdown is gone"));
    }

    @Test
    void refusesToRecordAFailureOnAFileItCannotWrite()
    {
        final Map<String, Object> payload = new HashMap<>();
        payload.put(ParseCompletionHandler.FILE, this.file.getPath());
        payload.put(ParseCompletionHandler.SUCCEEDED, Boolean.FALSE);
        final Resource readOnlyTarget = new ResourceWrapper(this.submission)
        {
            @Override
            public ResourceResolver getResourceResolver()
            {
                return new ResourceResolverWrapper(super.getResourceResolver())
                {
                    @Override
                    public Resource getResource(final String path)
                    {
                        final Resource found = super.getResource(path);
                        return found == null ? null : new ResourceWrapper(found)
                        {
                            @Override
                            public <T> T adaptTo(final Class<T> type)
                            {
                                return ModifiableValueMap.class.equals(type) ? null : super.adaptTo(type);
                            }
                        };
                    }
                };
            }
        };

        assertThrows(PersistenceException.class,
            () -> this.handler.execute(TaskContexts.of(readOnlyTarget, payload, new HashMap<>())));
    }

    // Whether the parse worked or not, the volume is left alone here: it holds the only copy of what the
    // parse produced until this transaction commits, and the step after the commit is what clears it
    @Test
    void leavesTheStagingFolderToWhoeverCommits() throws Exception
    {
        this.file.adaptTo(ModifiableValueMap.class)
            .put(ParseDocumentsHandler.SHARED_PATH, "/shared-docs/abc123/proposal.pdf");
        final Map<String, Object> payload = new HashMap<>();
        payload.put(ParseCompletionHandler.FILE, this.file.getPath());
        payload.put(ParseCompletionHandler.SUCCEEDED, Boolean.FALSE);

        this.handler.execute(task(payload));

        Mockito.verify(this.ingester, Mockito.never()).discardStaging(Mockito.any());
    }
}

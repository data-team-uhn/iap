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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.uhndata.iap.documents.api.ParseOutcome;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowFailedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ParseCompletionHandler}: how a finished parse reaches the engine.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ParseCompletionHandlerTest
{
    private static final String JOB_ID = "86a4c102-4b6a-4933-bc33-cc02e0e26eb7";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final WorkflowEngine engine = Mockito.mock(WorkflowEngine.class);

    private final ParseCompletionHandler handler = new ParseCompletionHandler();

    @TempDir
    private Path volume;

    private Resource file;

    @BeforeEach
    void setUp() throws Exception
    {
        final SubmissionTree tree = new SubmissionTree(this.context);
        tree.schemaVersion();
        tree.submission();
        this.file = tree.file("active");
        inject("resolverFactory", new TestResolverFactory(this.context.resourceResolver()));
        inject("engine", this.engine);
    }

    private void inject(final String name, final Object value) throws Exception
    {
        final Field field = ParseCompletionHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(this.handler, value);
    }

    private ParseOutcome completed(final String target)
    {
        return new ParseOutcome(JOB_ID, target, true, null, "/shared-docs/x/proposal.md", "/shared-docs/x/Chunks");
    }

    @Test
    void firesTheEventOnTheSubmissionTheFileBelongsTo() throws Exception
    {
        assertTrue(this.handler.handle(completed(this.file.getPath())));

        final ArgumentCaptor<Resource> target = ArgumentCaptor.forClass(Resource.class);
        final ArgumentCaptor<WorkflowEvent> event = ArgumentCaptor.forClass(WorkflowEvent.class);
        Mockito.verify(this.engine).receiveEvent(target.capture(), event.capture());
        assertEquals(SubmissionTree.SUBMISSION_PATH, target.getValue().getPath());
        assertEquals(ParseCompletionHandler.EVENT, event.getValue().getName());
        assertEquals(this.file.getPath(), event.getValue().get(ParseCompletionHandler.FILE));
        assertEquals(Boolean.TRUE, event.getValue().get(ParseCompletionHandler.SUCCEEDED));
        assertEquals("/shared-docs/x/proposal.md", event.getValue().get(ParseCompletionHandler.MARKDOWN));
        assertEquals("/shared-docs/x/Chunks", event.getValue().get(ParseCompletionHandler.CHUNKS));
        assertNull(event.getValue().get(ParseCompletionHandler.ERROR));
    }

    @Test
    void carriesAFailureAsItWas() throws Exception
    {
        assertTrue(this.handler.handle(new ParseOutcome(JOB_ID, this.file.getPath(), false, "No pages", null, null)));

        final ArgumentCaptor<WorkflowEvent> event = ArgumentCaptor.forClass(WorkflowEvent.class);
        Mockito.verify(this.engine).receiveEvent(Mockito.any(), event.capture());
        assertEquals(Boolean.FALSE, event.getValue().get(ParseCompletionHandler.SUCCEEDED));
        assertEquals("No pages", event.getValue().get(ParseCompletionHandler.ERROR));
        assertNull(event.getValue().get(ParseCompletionHandler.MARKDOWN));
    }

    @Test
    void namesThePdfRenditionWhenTheDaemonLeftOneBesideTheMarkdown() throws IOException
    {
        final Path markdown = this.volume.resolve("proposal.md");
        Files.writeString(markdown, "# Proposal");
        final Path pdf = this.volume.resolve("proposal.pdf");
        Files.writeString(pdf, "%PDF");

        final Map<String, Object> payload = ParseCompletionHandler.payload(
            new ParseOutcome(JOB_ID, this.file.getPath(), true, null, markdown.toString(), null));

        assertEquals(pdf.toString(), payload.get(ParseCompletionHandler.PDF));
        assertFalse(payload.containsKey(ParseCompletionHandler.CHUNKS), "an unchunked parse names no chunk tree");
    }

    @Test
    void namesNoPdfWhenThereIsNone() throws IOException
    {
        final Path markdown = this.volume.resolve("proposal.md");
        Files.writeString(markdown, "# Proposal");

        final Map<String, Object> payload = ParseCompletionHandler.payload(
            new ParseOutcome(JOB_ID, this.file.getPath(), true, null, markdown.toString(), null));

        assertFalse(payload.containsKey(ParseCompletionHandler.PDF));
    }

    @Test
    void takesAnOutcomeForAFileThatIsGone() throws Exception
    {
        assertTrue(this.handler.handle(completed(SubmissionTree.SUBMISSION_PATH + "/d9/v1/file")),
            "nothing left to record it on, so nothing to keep the record for");

        Mockito.verifyNoInteractions(this.engine);
    }

    @Test
    void takesAnOutcomeForAFileOutsideAnySubmission() throws Exception
    {
        final Resource stray = this.context.create().resource("/var/stray", Map.of());

        assertTrue(this.handler.handle(completed(stray.getPath())));

        Mockito.verifyNoInteractions(this.engine);
    }

    @Test
    void leavesTheRecordWhenTheEngineRefuses() throws Exception
    {
        Mockito.when(this.engine.receiveEvent(Mockito.any(), Mockito.any()))
            .thenThrow(new WorkflowFailedException("broken definition"));

        assertFalse(this.handler.handle(completed(this.file.getPath())));
    }

    @Test
    void leavesTheRecordWithoutTheServiceUser() throws Exception
    {
        inject("resolverFactory", new TestResolverFactory(null));

        assertFalse(this.handler.handle(completed(this.file.getPath())));

        Mockito.verifyNoInteractions(this.engine);
    }
}

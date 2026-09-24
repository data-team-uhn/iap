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

import io.uhndata.iap.documents.api.ParseService;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link ParseDocumentsHandler}.
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
        this.tree.schemaVersion();
        this.submission = this.tree.submission();
        final Field field = ParseDocumentsHandler.class.getDeclaredField("parseService");
        field.setAccessible(true);
        field.set(this.handler, this.parseService);
        Mockito.when(this.parseService.stage(Mockito.eq("proposal.pdf"), Mockito.any())).thenReturn(STAGED);
        Mockito.when(this.parseService.queue(Mockito.eq(STAGED), Mockito.anyString()))
            .thenReturn(JOB_ID);
    }

    private WorkflowTaskContext task(final Resource target)
    {
        return TaskContexts.of(target, Map.of(), new HashMap<>());
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("parseDocuments", this.handler.getName());
    }

    @Test
    void stagesAndQueuesEveryUploadNotParsedYet() throws Exception
    {
        final Resource file = this.tree.file(null);

        this.handler.execute(task(this.submission));

        Mockito.verify(this.parseService).queue(STAGED, file.getPath());
        assertEquals("queued", file.getValueMap().get("parseStatus", String.class));
        assertEquals(JOB_ID, file.getValueMap().get(ParseDocumentsHandler.PARSE_JOB_ID, String.class));
        assertEquals(STAGED, file.getValueMap().get(ParseDocumentsHandler.SHARED_PATH, String.class));
        assertEquals("running", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class),
            "the submission is now being read");
    }

    @Test
    void leavesAFileAlreadySentAlone() throws Exception
    {
        this.tree.file("completed");

        this.handler.execute(task(this.submission));

        Mockito.verifyNoInteractions(this.parseService);
        assertNull(this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class),
            "nothing was queued, so nothing is being read");
    }

    @Test
    void sendsAFailedParseAgain() throws Exception
    {
        final Resource file = this.tree.file("failed");
        file.adaptTo(ModifiableValueMap.class).put("parseError", "The daemon could not be reached");

        this.handler.execute(task(this.submission));

        Mockito.verify(this.parseService).queue(STAGED, file.getPath());
        assertEquals("queued", file.getValueMap().get("parseStatus", String.class));
        assertNull(file.getValueMap().get("parseError", String.class),
            "the last failure is not what is wrong with this file any more");
        assertEquals("running", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class),
            "the submission is being read again");
    }

    @Test
    void skipsADocumentWithNoUpload() throws Exception
    {
        this.tree.emptyDocument();

        this.handler.execute(task(this.submission));

        Mockito.verifyNoInteractions(this.parseService);
    }

    @Test
    void skipsAFileWhoseUploadHoldsNoBytes() throws Exception
    {
        this.tree.emptyDocument();
        this.context.create().resource(SubmissionTree.SUBMISSION_PATH + "/d1/v1/file",
            Map.of("sling:resourceType", "sub/File"));

        this.handler.execute(task(this.submission));

        Mockito.verifyNoInteractions(this.parseService);
    }

    @Test
    void translatesAStagingFailureIntoAPersistenceFailure() throws Exception
    {
        this.tree.file(null);
        Mockito.when(this.parseService.stage(Mockito.any(), Mockito.any())).thenThrow(new IOException("disk full"));

        final PersistenceException failure =
            assertThrows(PersistenceException.class, () -> this.handler.execute(task(this.submission)));

        assertEquals(true, failure.getMessage().contains("disk full"));
    }

    @Test
    void refusesAFileItCannotWrite()
    {
        this.tree.file(null);
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

        assertThrows(PersistenceException.class, () -> this.handler.execute(task(readOnlyTarget)));
    }
}

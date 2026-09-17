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
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link ClassifyProposalHandler}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ClassifyProposalHandlerTest
{
    private static final String DATA = "/Categories/Retrospective/Data";

    private static final String TRIALS = "/Categories/Prospective/Trials";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final ProposalCategoryService classifier = Mockito.mock(ProposalCategoryService.class);

    private final ClassifyProposalHandler handler = new ClassifyProposalHandler();

    private final Map<String, Object> variables = new HashMap<>();

    private Resource submission;

    private Resource file;

    @BeforeEach
    void setUp() throws Exception
    {
        final SubmissionTree tree = new SubmissionTree(this.context);
        tree.schemaVersion();
        this.submission = tree.submission();
        this.file = tree.file("completed");
        final Field field = ClassifyProposalHandler.class.getDeclaredField("classifier");
        field.setAccessible(true);
        field.set(this.handler, this.classifier);
        this.variables.put(GateProposalHandler.VERDICT_VARIABLE, "PROPOSAL");
        this.variables.put(GateProposalHandler.FILE_VARIABLE, this.file.getPath());
    }

    private WorkflowTaskContext task(final Resource target)
    {
        return TaskContexts.of(target, Map.of(), this.variables);
    }

    private void unsure() throws PersistenceException
    {
        Mockito.when(this.classifier.needsSecondLook(Mockito.any())).thenReturn(true);
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("classifyProposal", this.handler.getName());
    }

    @Test
    void recordsTheSecondLooksPick() throws Exception
    {
        unsure();
        Mockito.when(this.classifier.classify(Mockito.any(), Mockito.anyList()))
            .thenReturn(new CategoryPick(TRIALS, 0.9));

        this.handler.execute(task(this.submission));

        assertEquals(TRIALS, this.submission.getValueMap().get(ExtractionStatus.CATEGORY, String.class));
    }

    @Test
    void doesNothingForADocumentTheGateTurnedAway() throws Exception
    {
        this.variables.put(GateProposalHandler.VERDICT_VARIABLE, "NOT_PROPOSAL");

        this.handler.execute(task(this.submission));

        Mockito.verifyNoInteractions(this.classifier);
    }

    @Test
    void doesNothingWhenTheGateWasSureEnough() throws Exception
    {
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.CATEGORY, DATA);
        this.variables.put(GateProposalHandler.CATEGORY_CONFIDENCE_VARIABLE, 0.9);

        this.handler.execute(task(this.submission));

        Mockito.verify(this.classifier, Mockito.never()).classify(Mockito.any(), Mockito.anyList());
        assertEquals(DATA, this.submission.getValueMap().get(ExtractionStatus.CATEGORY, String.class));
    }

    @Test
    void doesNothingWhenTheFileIsGone() throws Exception
    {
        unsure();
        this.variables.put(GateProposalHandler.FILE_VARIABLE, this.file.getPath() + "-gone");

        this.handler.execute(task(this.submission));

        Mockito.verify(this.classifier, Mockito.never()).classify(Mockito.any(), Mockito.anyList());
    }

    @Test
    void leavesTheGatesPickWhenTheSecondLookPicksNothing() throws Exception
    {
        unsure();
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.CATEGORY, DATA);
        Mockito.when(this.classifier.classify(Mockito.any(), Mockito.anyList())).thenReturn(null);

        this.handler.execute(task(this.submission));

        assertEquals(DATA, this.submission.getValueMap().get(ExtractionStatus.CATEGORY, String.class));
    }

    @Test
    void translatesAnUnreadableDocumentIntoAPersistenceFailure() throws Exception
    {
        unsure();
        Mockito.when(this.classifier.classify(Mockito.any(), Mockito.anyList())).thenThrow(new IOException("gone"));

        assertThrows(PersistenceException.class, () -> this.handler.execute(task(this.submission)));
    }

    @Test
    void refusesASubmissionItCannotWrite() throws Exception
    {
        unsure();
        Mockito.when(this.classifier.classify(Mockito.any(), Mockito.anyList()))
            .thenReturn(new CategoryPick(TRIALS, 0.9));
        final Resource readOnly = new ResourceWrapper(this.submission)
        {
            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return ModifiableValueMap.class.equals(type) ? null : super.adaptTo(type);
            }
        };

        assertThrows(PersistenceException.class, () -> this.handler.execute(task(readOnly)));
        assertNull(this.submission.getValueMap().get(ExtractionStatus.CATEGORY, String.class));
    }
}

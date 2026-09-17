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
import java.util.List;
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

import io.uhndata.iap.extraction.internal.ProposalGateService.GateDecision;
import io.uhndata.iap.extraction.internal.ProposalGateService.Verdict;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GateProposalHandler}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class GateProposalHandlerTest
{
    private static final String DATA = "/Categories/Retrospective/Data";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final ProposalGateService gate = Mockito.mock(ProposalGateService.class);

    private final GateProposalHandler handler = new GateProposalHandler();

    private final Map<String, Object> variables = new HashMap<>();

    private SubmissionTree tree;

    private Resource submission;

    @BeforeEach
    void setUp() throws Exception
    {
        this.tree = new SubmissionTree(this.context);
        this.tree.schemaVersion();
        this.submission = this.tree.submission();
        final Field field = GateProposalHandler.class.getDeclaredField("gate");
        field.setAccessible(true);
        field.set(this.handler, this.gate);
    }

    private WorkflowTaskContext task(final Resource target)
    {
        return TaskContexts.of(target, Map.of(), this.variables);
    }

    private void gateSays(final GateDecision decision) throws IOException
    {
        Mockito.when(this.gate.evaluate(Mockito.any(), Mockito.anyList())).thenReturn(decision);
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("gateProposal", this.handler.getName());
    }

    @Test
    void recordsAProposalAndItsCategoryAndLeavesThemForTheNextSteps() throws Exception
    {
        final Resource file = this.tree.file("completed");
        gateSays(new GateDecision(Verdict.PROPOSAL, 0.9, "Aims and methods.", List.of(),
            new CategoryPick(DATA, 0.8)));

        this.handler.execute(task(this.submission));

        Mockito.verify(this.gate).applyTags(Mockito.argThat(resource -> file.getPath().equals(resource.getPath())),
            Mockito.any());
        assertEquals("PROPOSAL", this.variables.get(GateProposalHandler.VERDICT_VARIABLE));
        assertEquals(file.getPath(), this.variables.get(GateProposalHandler.FILE_VARIABLE));
        assertEquals(0.8, this.variables.get(GateProposalHandler.CATEGORY_CONFIDENCE_VARIABLE));
        assertEquals("PROPOSAL", this.submission.getValueMap().get(ExtractionStatus.VERDICT, String.class));
        assertEquals(DATA, this.submission.getValueMap().get(ExtractionStatus.CATEGORY, String.class));
        assertEquals("running", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class),
            "still going: the answers are next");
    }

    @Test
    void recordsAProposalWithNoCategoryPicked() throws Exception
    {
        this.tree.file("completed");
        gateSays(new GateDecision(Verdict.PROPOSAL, 0.9, "", List.of()));

        this.handler.execute(task(this.submission));

        assertNull(this.variables.get(GateProposalHandler.CATEGORY_CONFIDENCE_VARIABLE));
        assertNull(this.submission.getValueMap().get(ExtractionStatus.CATEGORY, String.class));
    }

    @Test
    void stopsForADocumentThatIsNotAProposal() throws Exception
    {
        this.tree.file("completed");
        gateSays(new GateDecision(Verdict.NOT_PROPOSAL, 0.9, "It is a consent form.", List.of()));

        this.handler.execute(task(this.submission));

        assertEquals("not-proposal", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
        assertEquals("It is a consent form.",
            this.submission.getValueMap().get(ExtractionStatus.MESSAGE, String.class));
        assertEquals("NOT_PROPOSAL", this.variables.get(GateProposalHandler.VERDICT_VARIABLE));
    }

    @Test
    void stopsWithTheAgreedWordingWhenTheGateCouldNotTell() throws Exception
    {
        this.tree.file("completed");
        gateSays(GateDecision.undetermined());

        this.handler.execute(task(this.submission));

        assertEquals("undetermined", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
        assertEquals(ExtractionStatus.UNDETERMINED_MESSAGE,
            this.submission.getValueMap().get(ExtractionStatus.MESSAGE, String.class));
    }

    @Test
    void failsWhenNoDocumentCouldBeRead() throws Exception
    {
        this.tree.file("failed");

        this.handler.execute(task(this.submission));

        assertEquals("failed", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
        Mockito.verifyNoInteractions(this.gate);
    }

    @Test
    void translatesAnUnreadableDocumentIntoAPersistenceFailure() throws Exception
    {
        this.tree.file("completed");
        Mockito.when(this.gate.evaluate(Mockito.any(), Mockito.anyList())).thenThrow(new IOException("no content"));

        assertThrows(PersistenceException.class, () -> this.handler.execute(task(this.submission)));
    }

    @Test
    void refusesASubmissionItCannotWrite() throws Exception
    {
        this.tree.file("completed");
        gateSays(new GateDecision(Verdict.PROPOSAL, 0.9, "", List.of()));
        final Resource readOnly = new ResourceWrapper(this.submission)
        {
            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return ModifiableValueMap.class.equals(type) ? null : super.adaptTo(type);
            }
        };

        assertThrows(PersistenceException.class, () -> this.handler.execute(task(readOnly)));
    }

    @Test
    void theNextStepsReadTheVerdictBack()
    {
        assertFalse(GateProposalHandler.passed(task(this.submission)), "nothing decided yet");
        this.variables.put(GateProposalHandler.VERDICT_VARIABLE, "PROPOSAL");
        assertTrue(GateProposalHandler.passed(task(this.submission)));
    }

    @Test
    void theNextStepsReadTheFileBack()
    {
        final Resource file = this.tree.file("completed");
        assertNull(GateProposalHandler.gatedFile(task(this.submission)), "nothing recorded yet");
        this.variables.put(GateProposalHandler.FILE_VARIABLE, file.getPath());
        assertNotNull(GateProposalHandler.gatedFile(task(this.submission)));
        this.variables.put(GateProposalHandler.FILE_VARIABLE, file.getPath() + "-gone");
        assertNull(GateProposalHandler.gatedFile(task(this.submission)));
    }

    @Test
    void theNextStepsReadThePickBack() throws PersistenceException
    {
        assertNull(GateProposalHandler.pick(task(this.submission)), "no category recorded");
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.CATEGORY, DATA);
        assertNull(GateProposalHandler.pick(task(this.submission)), "a category with no confidence is not a pick");
        this.variables.put(GateProposalHandler.CATEGORY_CONFIDENCE_VARIABLE, 0.6);

        final CategoryPick pick = GateProposalHandler.pick(task(this.submission));

        assertNotNull(pick);
        assertEquals(DATA, pick.path());
        assertEquals(0.6, pick.confidence());
        assertEquals(pick, GateProposalHandler.recorded(task(this.submission)).category());
    }
}

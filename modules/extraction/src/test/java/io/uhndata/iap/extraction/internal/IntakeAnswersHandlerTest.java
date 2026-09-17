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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.uhndata.iap.extraction.internal.AnswerIntakeService.IntakeResult;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link IntakeAnswersHandler}: which questions go to the model, and how what it found is recorded.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class IntakeAnswersHandlerTest
{
    private static final String AIMS = "study/aims";

    private static final String TITLE = "study/title";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final AnswerIntakeService intake = Mockito.mock(AnswerIntakeService.class);

    private final IntakeAnswersHandler handler = new IntakeAnswersHandler();

    private final Map<String, Object> variables = new HashMap<>();

    private SubmissionTree tree;

    private Resource submission;

    private Resource file;

    private Resource aims;

    @BeforeEach
    void setUp() throws Exception
    {
        this.tree = new SubmissionTree(this.context);
        this.tree.schemaVersion();
        this.aims = this.tree.question("aims", "Find the primary aims.", "B.3");
        this.tree.question("title", "Find the title.");
        this.tree.question("comments", null);
        this.submission = this.tree.submission();
        this.file = this.tree.file("completed");
        final Field field = IntakeAnswersHandler.class.getDeclaredField("intake");
        field.setAccessible(true);
        field.set(this.handler, this.intake);
        this.variables.put(GateProposalHandler.VERDICT_VARIABLE, "PROPOSAL");
        this.variables.put(GateProposalHandler.FILE_VARIABLE, this.file.getPath());
    }

    private WorkflowTaskContext task()
    {
        return TaskContexts.of(this.submission, Map.of(), this.variables);
    }

    private void modelFinds(final FieldResult... results) throws IOException
    {
        final Map<String, FieldResult> fields = new HashMap<>();
        for (final FieldResult result : results) {
            fields.put(result.name(), result);
        }
        Mockito.when(this.intake.run(Mockito.any(), Mockito.anyList()))
            .thenReturn(new IntakeResult(fields, List.of(), List.of("Chunk-1"), false));
    }

    private List<Resource> answers()
    {
        final List<Resource> answers = new ArrayList<>();
        for (final Resource child : this.submission.getChildren()) {
            if ("sub:Answer".equals(child.getValueMap().get("jcr:primaryType", String.class))) {
                answers.add(child);
            }
        }
        return answers;
    }

    private static Resource onlyChild(final Resource parent)
    {
        final List<Resource> children = new ArrayList<>();
        parent.getChildren().forEach(children::add);
        assertEquals(1, children.size());
        return children.get(0);
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("intakeAnswers", this.handler.getName());
    }

    @Test
    void putsOnlyTheQuestionsWithAPromptToTheModelKeyedByPath() throws Exception
    {
        modelFinds();

        this.handler.execute(task());

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<List<ExtractionField>> fields = ArgumentCaptor.forClass(List.class);
        Mockito.verify(this.intake).run(Mockito.any(), fields.capture());
        assertEquals(List.of(AIMS, TITLE),
            fields.getValue().stream().map(ExtractionField::name).sorted().toList(),
            "the question with no prompt is the submitter's to answer");
        assertEquals("done", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
    }

    @Test
    void recordsWhatTheModelFoundWithItsEvidence() throws Exception
    {
        final Resource chunk = this.tree.chunk(this.file, "Chunk-1", "## Aims\n\nTo reduce readmissions.\n");
        modelFinds(new FieldResult(AIMS, true, 0.85, "To reduce readmissions", "Stated under Aims.",
            List.of(new FieldResult.Passage("To reduce readmissions", "Chunk-1", 3L),
                new FieldResult.Passage("also here", "document", null))));

        this.handler.execute(task());

        final Resource answer = answers().get(0);
        assertEquals(1, answers().size());
        assertEquals(SubmissionTree.identifierOf(this.aims), answer.getValueMap().get("question", String.class));
        assertArrayEquals(new String[] { "To reduce readmissions" }, answer.getValueMap().get("value", String[].class));
        final Resource extraction = onlyChild(answer);
        assertEquals("sub:Extraction", extraction.getValueMap().get("jcr:primaryType", String.class));
        assertEquals("To reduce readmissions", extraction.getValueMap().get("extractedAnswer", String.class));
        assertEquals(0.85, extraction.getValueMap().get("confidence", Double.class));
        assertEquals("Stated under Aims.", extraction.getValueMap().get("reasoning", String.class));
        assertArrayEquals(new String[] { SubmissionTree.identifierOf(this.file.getParent()) },
            extraction.getValueMap().get("sources", String[].class), "read from the version the file belongs to");
        final List<Resource> evidence = new ArrayList<>();
        extraction.getChildren().forEach(evidence::add);
        assertEquals(2, evidence.size());
        assertEquals("To reduce readmissions", evidence.get(0).getValueMap().get("quote", String.class));
        assertEquals(3L, evidence.get(0).getValueMap().get("page", Long.class));
        assertEquals(SubmissionTree.identifierOf(chunk), evidence.get(0).getValueMap().get("chunk", String.class));
        assertNull(evidence.get(1).getValueMap().get("chunk", String.class),
            "a quote from the whole document points at no chunk");
        assertNull(evidence.get(1).getValueMap().get("page", Long.class));
        Mockito.verify(this.intake).applyTags(
            Mockito.argThat(resource -> this.file.getPath().equals(resource.getPath())),
            Mockito.any());
    }

    @Test
    void leavesOutWhatTheModelDidNotFind() throws Exception
    {
        modelFinds(new FieldResult(AIMS, false, 0.0, null, "Not stated.", List.of()));

        this.handler.execute(task());

        assertTrue(answers().isEmpty());
        assertEquals("done", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
    }

    @Test
    void leavesAQuestionTheSubmitterAlreadyAnsweredAlone() throws Exception
    {
        final Resource theirs = this.context.create().resource(this.submission.getPath() + "/a1",
            Map.of("jcr:primaryType", "sub:Answer", "sling:resourceType", "sub/Answer",
                "value", new String[] { "What the person said" }));
        SubmissionTree.reference(theirs, "question", this.aims);
        modelFinds(new FieldResult(AIMS, true, 0.9, "What the model read", "", List.of()));

        this.handler.execute(task());

        assertEquals(1, answers().size());
        assertArrayEquals(new String[] { "What the person said" },
            answers().get(0).getValueMap().get("value", String[].class));
    }

    @Test
    void doesNothingForADocumentTheGateTurnedAway() throws Exception
    {
        this.variables.put(GateProposalHandler.VERDICT_VARIABLE, "UNDETERMINED");

        this.handler.execute(task());

        Mockito.verifyNoInteractions(this.intake);
        assertNull(this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
    }

    @Test
    void failsWhenTheFileTheGateReadIsGone() throws Exception
    {
        this.variables.put(GateProposalHandler.FILE_VARIABLE, this.file.getPath() + "-gone");

        this.handler.execute(task());

        assertEquals("failed", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
        Mockito.verifyNoInteractions(this.intake);
    }

    @Test
    void isDoneAtOnceWhenTheSchemaAsksNothingOfTheDocument() throws Exception
    {
        final SubmissionTree bare = new SubmissionTree(new SlingContext(ResourceResolverType.JCR_MOCK));
        // Rebuilt without prompts: only the question meant for the submitter
        this.context.resourceResolver().delete(this.aims);
        this.context.resourceResolver().delete(
            this.context.resourceResolver().getResource(SubmissionTree.FORM_PATH + "/title"));
        assertNotNull(bare);

        this.handler.execute(task());

        assertEquals("done", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
        assertEquals("The schema asks nothing of the document",
            this.submission.getValueMap().get(ExtractionStatus.MESSAGE, String.class));
        Mockito.verifyNoInteractions(this.intake);
    }

    @Test
    void failsWhenTheModelsAnswerCouldNotBeRead() throws Exception
    {
        Mockito.when(this.intake.run(Mockito.any(), Mockito.anyList()))
            .thenReturn(IntakeResult.degraded(List.of()));

        this.handler.execute(task());

        assertEquals("failed", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
        assertTrue(answers().isEmpty());
    }

    @Test
    void translatesAnUnreadableDocumentIntoAPersistenceFailure() throws Exception
    {
        Mockito.when(this.intake.run(Mockito.any(), Mockito.anyList())).thenThrow(new IOException("no content"));

        assertThrows(PersistenceException.class, () -> this.handler.execute(task()));
    }

    @Test
    void refusesToRecordAnAnswerWhoseQuestionCannotBeReferenced() throws Exception
    {
        modelFinds(new FieldResult(AIMS, true, 0.9, "Something", "", List.of()));
        // The question is read into the field list first, then gone by the time the answer is written
        Mockito.when(this.intake.run(Mockito.any(), Mockito.anyList())).thenAnswer(invocation -> {
            this.context.resourceResolver().delete(this.aims);
            return new IntakeResult(Map.of(AIMS, new FieldResult(AIMS, true, 0.9, "Something", "", List.of())),
                List.of(), List.of(), false);
        });

        assertThrows(PersistenceException.class, () -> this.handler.execute(task()));
    }
}

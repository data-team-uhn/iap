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

import org.apache.sling.api.resource.ModifiableValueMap;
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
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ClassifyDocumentHandler}: which classifications are sent to the model, what it is shown,
 * and which of its picks are saved.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ClassifyDocumentHandlerTest
{
    private static final String DECISION = "is_proposal.decision";

    private static final String PROMPT = "Decide whether the document is a research protocol.";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final AnswerIntakeService intake = Mockito.mock(AnswerIntakeService.class);

    private final ClassifyDocumentHandler handler = new ClassifyDocumentHandler();

    private SubmissionTree tree;

    private Resource submission;

    private Resource proposal;

    private Resource decision;

    @BeforeEach
    void setUp() throws Exception
    {
        this.tree = new SubmissionTree(this.context);
        this.tree.createSchemaVersion();
        this.submission = this.tree.createSubmission();
        this.proposal = this.tree.createDocumentRequirement("proposal");
        this.decision = this.tree.createClassificationQuestion("is_proposal", "proposal", PROMPT);
        inject("intake", this.intake);
        inject("documents", new ParsedDocuments());
        inject("runs", new ReadingRuns());
    }

    private void inject(final String name, final Object value) throws Exception
    {
        final Field field = ClassifyDocumentHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(this.handler, value);
    }

    private WorkflowTaskContext createTask()
    {
        return TaskContexts.of(this.submission, Map.of(), new HashMap<>());
    }

    private void stubModelPick(final String value, final boolean degraded) throws IOException
    {
        stubModelPick(value, 0.9, degraded);
    }

    private void stubModelPick(final String value, final double confidence, final boolean degraded) throws IOException
    {
        Mockito.when(this.intake.askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any()))
            .thenReturn(degraded ? IntakeResult.createNotRead(IntakeResult.REPLY_UNREADABLE_MESSAGE)
                : IntakeResult.createRead(Map.of(DECISION, new FieldResult(DECISION, List.of(value),
                    "It has objectives and methods.", confidence, List.of(), 0))));
    }

    /** The value the run under an answer suggested. This is what the submitter is shown. */
    private static String getPick(final Resource answer)
    {
        return answer.getChildren().iterator().next().getValueMap().get("extractedAnswer", String.class);
    }

    private String getStatus()
    {
        return this.submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class);
    }

    private List<Resource> getAnswers()
    {
        final List<Resource> answers = new ArrayList<>();
        for (final Resource child : this.submission.getChildren()) {
            if ("sub:Answer".equals(child.getValueMap().get("jcr:primaryType", String.class))) {
                answers.add(child);
            }
        }
        return answers;
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("classifyDocument", this.handler.getName());
    }

    @Test
    void putsTheClassificationToTheModelWithItsPromptOptionsAndReference() throws Exception
    {
        this.tree.createParsedFileFor(this.proposal, "# Objectives");
        this.tree.addTemplate("is_proposal", "B.1 General information");
        stubModelPick("yes", false);

        this.handler.execute(createTask());

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<List<ExtractionPrompt.Group>> groups = ArgumentCaptor.forClass(List.class);
        final ArgumentCaptor<String> reference = ArgumentCaptor.forClass(String.class);
        Mockito.verify(this.intake).askQuestions(Mockito.any(), Mockito.any(), groups.capture(), reference.capture());
        assertEquals(1, groups.getValue().size(), "one document's classifications are asked together");
        final ExtractionField field = groups.getValue().get(0).fields().get(0);
        assertEquals(DECISION, field.key());
        assertTrue(field.instructions().startsWith(PROMPT), "the instructions come from the requirement");
        assertEquals(List.of("yes", "no"),
            field.answer().options().stream().map(ExtractionField.Option::value).toList());
        assertTrue(reference.getValue().contains("B.1 General information"));
        assertEquals("yes", getPick(getAnswers().get(0)), "the pick is kept as a suggestion");
        assertNull(getAnswers().get(0).getValueMap().get("value", String[].class),
            "and is not the answer until the submitter acts on it");
        assertNull(getStatus(), "the reading goes on, so it is not marked done here");
    }

    // The gate never guesses: a pick below the confidence threshold is left for the submitter to make
    @Test
    void leavesTheQuestionUnansweredWhenThePickIsUnsure() throws Exception
    {
        this.tree.createParsedFileFor(this.proposal, "# Objectives");
        stubModelPick("yes", 0.5, false);

        this.handler.execute(createTask());

        assertTrue(getAnswers().isEmpty());
    }

    // Not every provider keeps the reply to the list of options, and a pick off the list is still a guess
    @Test
    void leavesTheQuestionUnansweredWhenThePickNamesNoCategory() throws Exception
    {
        this.tree.createParsedFileFor(this.proposal, "# Objectives");
        stubModelPick("Probably a protocol", false);

        this.handler.execute(createTask());

        assertTrue(getAnswers().isEmpty());
    }

    @Test
    void storesAPickAsSureAsTheThresholdTheSchemaSets() throws Exception
    {
        this.tree.createParsedFileFor(this.proposal, "# Objectives");
        this.context.resourceResolver().getResource(SubmissionTree.VERSION_PATH + "/is_proposal")
            .adaptTo(ModifiableValueMap.class).put("confidenceThreshold", 0.4);
        stubModelPick("yes", 0.5, false);

        this.handler.execute(createTask());

        assertEquals(1, getAnswers().size());
    }

    @Test
    void asksWithoutAReferenceWhenThereIsNone() throws Exception
    {
        this.tree.createParsedFileFor(this.proposal, "# Objectives");
        stubModelPick("no", false);

        this.handler.execute(createTask());

        Mockito.verify(this.intake).askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.isNull());
    }

    // A pick an earlier reading suggested is not asked for again, even if nobody has accepted it yet
    @Test
    void leavesAClassificationAlreadySuggestedAlone() throws Exception
    {
        this.tree.createParsedFileFor(this.proposal, "# Objectives");
        stubModelPick("yes", false);
        this.handler.execute(createTask());
        this.tree.stampTypes();

        this.handler.execute(createTask());

        Mockito.verify(this.intake, Mockito.times(1))
            .askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any());
    }

    // When the reading runs again, a question that already has an answer is not asked again
    @Test
    void leavesAClassificationAlreadyAnsweredAlone() throws Exception
    {
        this.tree.createParsedFileFor(this.proposal, "# Objectives");
        final Resource answer = this.context.create().resource(SubmissionTree.SUBMISSION_PATH + "/given",
            Map.of("jcr:primaryType", "sub:Answer", "sling:resourceType", "sub/Answer", "value",
                new String[] { "yes" }));
        SubmissionTree.reference(answer, "question", this.decision);

        this.handler.execute(createTask());

        Mockito.verifyNoInteractions(this.intake);
    }

    // The form saves a field when it loses focus, so an empty saved answer is not a decision
    @Test
    void stillAsksAClassificationTheFormSavedEmpty() throws Exception
    {
        this.tree.createParsedFileFor(this.proposal, "# Objectives");
        final Resource answer = this.context.create().resource(SubmissionTree.SUBMISSION_PATH + "/given",
            Map.of("jcr:primaryType", "sub:Answer", "sling:resourceType", "sub/Answer", "value",
                new String[] { "" }));
        SubmissionTree.reference(answer, "question", this.decision);
        stubModelPick("yes", false);

        this.handler.execute(createTask());

        assertEquals("yes", getPick(this.context.resourceResolver().getResource(answer.getPath())),
            "the run goes under the empty answer");
    }

    @Test
    void waitsForADocumentStillBeingParsed() throws Exception
    {
        final Resource file = this.tree.createParsedFileFor(this.proposal, "# Objectives");
        file.adaptTo(ModifiableValueMap.class).put("parseStatus", "queued");

        this.handler.execute(createTask());

        Mockito.verifyNoInteractions(this.intake);
    }

    @Test
    void asksNothingWhenTheDocumentWasNotUploaded() throws Exception
    {
        this.handler.execute(createTask());

        Mockito.verifyNoInteractions(this.intake);
        assertNull(getStatus());
    }

    @Test
    void failsWhenTheDocumentCouldNotBeParsed() throws Exception
    {
        final Resource file = this.tree.createParsedFileFor(this.proposal, "# Objectives");
        file.adaptTo(ModifiableValueMap.class).put("parseStatus", "failed");

        this.handler.execute(createTask());

        Mockito.verifyNoInteractions(this.intake);
        assertEquals(ExtractionStatus.STATUS_FAILED, getStatus());
    }

    @Test
    void failsWhenTheModelsAnswerCouldNotBeRead() throws Exception
    {
        this.tree.createParsedFileFor(this.proposal, "# Objectives");
        stubModelPick("yes", true);

        this.handler.execute(createTask());

        assertEquals(ExtractionStatus.STATUS_FAILED, getStatus());
        assertTrue(getAnswers().isEmpty());
    }

    // The lost pick stays open for the submitter, and they are told reading again is worth it
    @Test
    void leavesANoteWhenOnlySomeCallsWentThrough() throws Exception
    {
        this.tree.createParsedFileFor(this.proposal, "# Objectives");
        Mockito.when(this.intake.askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any()))
            .thenReturn(IntakeResult.createIncomplete(Map.of()));

        this.handler.execute(createTask());

        assertNull(getStatus(), "finishReading, not the gate, ends the reading");
        assertEquals(IntakeResult.INCOMPLETE_MESSAGE,
            this.submission.getValueMap().get(ExtractionStatus.MESSAGE_PROPERTY, String.class));
        assertTrue(getAnswers().isEmpty());
    }

    @Test
    void failsWhenTheModelCannotBeReached() throws Exception
    {
        this.tree.createParsedFileFor(this.proposal, "# Objectives");
        Mockito.when(this.intake.askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any()))
            .thenReturn(IntakeResult.createNotRead(IntakeResult.UNREACHABLE_MESSAGE));

        this.handler.execute(createTask());

        assertEquals(ExtractionStatus.STATUS_FAILED, getStatus());
        assertEquals(IntakeResult.UNREACHABLE_MESSAGE,
            this.submission.getValueMap().get(ExtractionStatus.MESSAGE_PROPERTY,
            String.class));
    }

    @Test
    void failsWhenTheTextCannotBeReadBack() throws Exception
    {
        this.tree.createParsedFileFor(this.proposal, "# Objectives");
        inject("documents", new ParsedDocuments()
        {
            @Override
            public DocumentScan scan(final File file) throws IOException
            {
                throw new IOException("the binary is gone");
            }
        });

        this.handler.execute(createTask());

        assertEquals(IntakeResult.TEXT_UNREADABLE_MESSAGE,
            this.submission.getValueMap().get(ExtractionStatus.MESSAGE_PROPERTY, String.class));
        Mockito.verifyNoInteractions(this.intake);
    }

    @Test
    void skipsAClassificationNamingNoDocument() throws Exception
    {
        this.tree.createParsedFileFor(this.proposal, "# Objectives");
        this.context.resourceResolver().getResource(SubmissionTree.VERSION_PATH + "/is_proposal")
            .adaptTo(ModifiableValueMap.class).remove("document");

        this.handler.execute(createTask());

        Mockito.verifyNoInteractions(this.intake);
    }

    @Test
    void aStopAbortsTheStep() throws Exception
    {
        this.tree.createParsedFileFor(this.proposal, "# Objectives");
        Mockito.when(this.intake.askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any()))
            .thenThrow(new ReadingRuns.Stopped());

        final PersistenceException failure =
            assertThrows(PersistenceException.class, () -> this.handler.execute(createTask()));

        assertEquals(ExtractionStatus.STOPPED_MESSAGE, failure.getMessage());
    }

    // A stop that comes after the model answered must still undo the step, and must not stay set on the thread
    @Test
    void aStopAfterTheModelAnsweredAbortsTheStep() throws Exception
    {
        this.tree.createParsedFileFor(this.proposal, "# Objectives");
        Mockito.when(this.intake.askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any()))
            .thenAnswer(invocation -> {
                new ReadingRuns().stop(this.submission.getPath());
                return IntakeResult.createRead(Map.of(DECISION,
                    new FieldResult(DECISION, List.of("yes"), "", 0.9, List.of(), 0)));
            });

        try {
            final PersistenceException failure =
                assertThrows(PersistenceException.class, () -> this.handler.execute(createTask()));
            assertTrue(ReadingRuns.isCausedByStop(failure.getCause()));
            assertFalse(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}

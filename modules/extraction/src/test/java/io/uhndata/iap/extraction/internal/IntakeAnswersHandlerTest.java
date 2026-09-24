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
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        this.aims = this.tree.question("aims", "Find the primary aims.");
        this.tree.question("title", "Find the title.");
        this.tree.question("comments", null);
        this.submission = this.tree.submission();
        this.file = this.tree.file("completed");
        final Field field = IntakeAnswersHandler.class.getDeclaredField("intake");
        field.setAccessible(true);
        field.set(this.handler, this.intake);
        final Field documents = IntakeAnswersHandler.class.getDeclaredField("documents");
        documents.setAccessible(true);
        documents.set(this.handler, new ParsedDocuments());
    }

    private WorkflowTaskContext task()
    {
        return TaskContexts.of(this.submission, Map.of(), this.variables);
    }

    /** A step that says which requirement it reads, the way a diagram names one. */
    private WorkflowTaskContext taskAsking(final String requirement)
    {
        return TaskContexts.of(this.submission, Map.of(), this.variables,
            Map.of(IntakeAnswersHandler.REQUIREMENT, requirement));
    }

    private void modelFinds(final FieldResult... results) throws IOException
    {
        final Map<String, FieldResult> fields = new HashMap<>();
        for (final FieldResult result : results) {
            fields.put(result.name(), result);
        }
        Mockito.when(this.intake.run(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any()))
            .thenReturn(new IntakeResult(fields, false));
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
        Mockito.verify(this.intake).run(Mockito.any(), Mockito.any(), fields.capture(), Mockito.any());
        assertEquals(List.of(AIMS, TITLE),
            fields.getValue().stream().map(ExtractionField::name).sorted().toList(),
            "the question with no prompt is the submitter's to answer");
        assertEquals("done", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
    }

    // A schema read in stages names a stage per step, so each call asks one requirement rather than
    // everything the schema version holds
    @Test
    void asksOnlyTheRequirementTheStepNames() throws Exception
    {
        modelFinds();

        this.handler.execute(taskAsking("study"));

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<List<ExtractionField>> fields = ArgumentCaptor.forClass(List.class);
        Mockito.verify(this.intake).run(Mockito.any(), Mockito.any(), fields.capture(), Mockito.any());
        assertEquals(List.of(AIMS, TITLE),
            fields.getValue().stream().map(ExtractionField::name).sorted().toList());
    }

    /** A step that writes only when one named field came back as a given value. */
    private WorkflowTaskContext taskRecordingWhen(final String gate)
    {
        return TaskContexts.of(this.submission, Map.of(), this.variables,
            Map.of(IntakeAnswersHandler.RECORD_WHEN, gate));
    }

    // The model is still asked everything. A document that is not a proposal must not leave those
    // answers behind, and the reading must still be marked done so the view does not spin.
    @Test
    void writesNothingWhenTheGatedAnswerIsNotTheExpectedValue() throws Exception
    {
        modelFinds(new FieldResult(AIMS, true, 0.9, "not-proposal", "", List.of()),
            new FieldResult(TITLE, true, 0.9, "A title", "", List.of()));

        this.handler.execute(taskRecordingWhen(AIMS + "=proposal"));

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<List<ExtractionField>> fields = ArgumentCaptor.forClass(List.class);
        Mockito.verify(this.intake).run(Mockito.any(), Mockito.any(), fields.capture(), Mockito.any());
        assertEquals(List.of(AIMS, TITLE),
            fields.getValue().stream().map(ExtractionField::name).sorted().toList());
        assertTrue(answers().isEmpty());
        assertEquals("done", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
    }

    @Test
    void writesNothingWhenTheGatedAnswerWasNotFound() throws Exception
    {
        modelFinds(new FieldResult(AIMS, false, 0.0, null, "Not stated.", List.of()),
            new FieldResult(TITLE, true, 0.9, "A title", "", List.of()));

        this.handler.execute(taskRecordingWhen(AIMS + "=proposal"));

        assertTrue(answers().isEmpty());
        assertEquals("done", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
    }

    @Test
    void recordsWhenTheGatedAnswerMatches() throws Exception
    {
        modelFinds(new FieldResult(AIMS, true, 0.9, "proposal", "", List.of()),
            new FieldResult(TITLE, true, 0.9, "A title", "", List.of()));

        this.handler.execute(taskRecordingWhen(AIMS + "=proposal"));

        assertEquals(2, answers().size());
        assertEquals("done", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
    }

    @Test
    void doesNotRecordWhenTheWriteGateCannotBeRead()
    {
        assertTrue(IntakeAnswersHandler.shouldRecord(null, Map.of()));
        assertTrue(IntakeAnswersHandler.shouldRecord(" ", Map.of()));
        assertFalse(IntakeAnswersHandler.shouldRecord("no-equals", Map.of()));
        assertFalse(IntakeAnswersHandler.shouldRecord("=proposal", Map.of()));
        assertFalse(IntakeAnswersHandler.shouldRecord(AIMS + "=", Map.of()));
        assertFalse(IntakeAnswersHandler.shouldRecord(AIMS + "= ", Map.of()));
        assertFalse(IntakeAnswersHandler.shouldRecord(" =proposal", Map.of()));
        assertFalse(IntakeAnswersHandler.shouldRecord(AIMS + "=proposal", Map.of()));
    }

    // A step whose requirement is not being asked has nothing to do, and must not say the schema asks
    // nothing of the document: another step is asking plenty
    @Test
    void doesNothingWhenTheRequirementItNamesIsNotThere() throws Exception
    {
        this.handler.execute(taskAsking("nothing-by-that-name"));

        Mockito.verifyNoInteractions(this.intake);
        assertNull(this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
    }

    @Test
    void recordsWhatTheModelFoundWithItsEvidence() throws Exception
    {
        modelFinds(new FieldResult(AIMS, true, 0.85, "To reduce readmissions", "Stated under Aims.",
            List.of(new FieldResult.Passage("To reduce readmissions", 3L).under("Aims"),
                new FieldResult.Passage("also here", null))));

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
        assertEquals("Aims", evidence.get(0).getValueMap().get("header", String.class));
        assertNull(evidence.get(1).getValueMap().get("page", Long.class));
        assertNull(evidence.get(1).getValueMap().get("header", String.class),
            "nothing titles a quote the document gave no heading for");
    }

    // A question that takes several answers is asked for them as one comma-separated string, so storing
    // that string whole would leave the answer as one value nobody asked for
    @Test
    void storesAMultiValuedAnswerAsOneValuePerName() throws Exception
    {
        this.tree.question("sites", "List the participating sites.", 0);
        modelFinds(new FieldResult("study/sites", true, 0.9, "Sunnybrook, St. Michael's , SickKids", "",
            List.of()));

        this.handler.execute(task());

        assertArrayEquals(new String[] { "Sunnybrook", "St. Michael's", "SickKids" },
            answers().get(0).getValueMap().get("value", String[].class));
        assertEquals("Sunnybrook, St. Michael's , SickKids",
            onlyChild(answers().get(0)).getValueMap().get("extractedAnswer", String.class),
            "the model's answer is kept as it came");
    }

    // Splitting it away would store the question as unanswered, losing what the model did say
    @Test
    void keepsAMultiValuedAnswerThatSplitsIntoNothing() throws Exception
    {
        this.tree.question("sites", "List the participating sites.", 0);
        modelFinds(new FieldResult("study/sites", true, 0.9, " , ", "", List.of()));

        this.handler.execute(task());

        assertArrayEquals(new String[] { " , " }, answers().get(0).getValueMap().get("value", String[].class));
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

    // A reading workflow need not gate: a schema whose document is only ever the one kind it asks for has
    // nothing to decide, and the step reads whatever the parse produced.
    @Test
    void readsTheParsedDocumentWhenNoGateRan() throws Exception
    {
        this.variables.clear();
        modelFinds(new FieldResult(AIMS, true, 0.9, "To reduce readmissions", "Stated under Aims.", List.of()));

        this.handler.execute(task());

        assertEquals("done", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
        assertEquals(SubmissionTree.identifierOf(this.aims),
            answers().get(0).getValueMap().get("question", String.class));
        assertArrayEquals(new String[] { "To reduce readmissions" },
            answers().get(0).getValueMap().get("value", String[].class));
    }

    /** A step that reads the named documents, in the order given. */
    private WorkflowTaskContext taskReading(final String... documents)
    {
        return TaskContexts.of(this.submission, Map.of(), this.variables,
            Map.of(IntakeAnswersHandler.DOCUMENTS, documents));
    }

    /** The text the model was sent. */
    private String sentText() throws IOException
    {
        final ArgumentCaptor<DocumentScan> sent = ArgumentCaptor.forClass(DocumentScan.class);
        Mockito.verify(this.intake).run(Mockito.any(), sent.capture(), Mockito.anyList(), Mockito.any());
        return sent.getValue().getText();
    }

    // A questionnaire comes with its preamble, and the model needs both to answer: what the questionnaire is
    // for is said in the preamble, what it asks is in the questionnaire. They go as one text, in the order the
    // step names them, each under a heading of its own.
    @Test
    void sendsTheNamedDocumentsAsOneTextInTheOrderNamed() throws Exception
    {
        this.variables.clear();
        final Resource questionnaire = this.tree.parsedFor(this.tree.documentRequirement("questionnaire"),
            "1. How was your visit?");
        final Resource preamble = this.tree.parsedFor(this.tree.documentRequirement("preamble"),
            "This survey asks about your care.");
        modelFinds(new FieldResult(AIMS, true, 0.9, "Care", "Stated in the preamble.", List.of(
            new FieldResult.Passage("This survey asks about your care.", 1L).in(0),
            new FieldResult.Passage("How was your visit?", 1L).in(1))));

        this.handler.execute(taskReading("preamble", "questionnaire"));

        assertEquals("# Preamble\n\nThis survey asks about your care.\n\n"
            + "# Questionnaire\n\n1. How was your visit?", sentText());
        assertArrayEquals(new String[] { SubmissionTree.identifierOf(preamble.getParent()),
            SubmissionTree.identifierOf(questionnaire.getParent()) },
            onlyChild(answers().get(0)).getValueMap().get("sources", String[].class),
            "read from both documents");
        // Each quote is stored with the document it came from, so its link opens that one
        final List<Resource> evidence = new ArrayList<>();
        onlyChild(answers().get(0)).getChildren().forEach(evidence::add);
        assertEquals(SubmissionTree.identifierOf(preamble.getParent()),
            evidence.get(0).getValueMap().get("source", String.class));
        assertEquals(SubmissionTree.identifierOf(questionnaire.getParent()),
            evidence.get(1).getValueMap().get("source", String.class));
    }

    // The preamble is optional. A step naming it reads what there is rather than refusing to read at all.
    @Test
    void readsTheNamedDocumentsThatWereUploaded() throws Exception
    {
        this.variables.clear();
        final Resource preamble = this.tree.documentRequirement("preamble");
        // Attached for it but nothing uploaded yet, and a document with no version at all
        SubmissionTree.reference(this.tree.emptyDocument().getParent(), "fulfills", preamble);
        SubmissionTree.reference(this.context.create().resource(SubmissionTree.SUBMISSION_PATH + "/bare",
            Map.of("sling:resourceType", "sub/Document")), "fulfills", preamble);
        this.tree.parsedFor(this.tree.documentRequirement("questionnaire"), "1. How was your visit?");
        modelFinds();

        this.handler.execute(taskReading("preamble", "questionnaire"));

        assertEquals("# Questionnaire\n\n1. How was your visit?", sentText());
    }

    // One of the named files failed to parse. Reading the other and calling the step done would hide that
    // failure: the view shows nothing once a reading is done, so the person would never be offered a retry.
    @Test
    void failsWhenANamedDocumentCouldNotBeRead() throws Exception
    {
        this.variables.clear();
        final Resource preamble = this.tree.documentRequirement("preamble");
        final Resource failed = this.tree.file("failed");
        final ModifiableValueMap properties = failed.adaptTo(ModifiableValueMap.class);
        assertNotNull(properties);
        properties.put("parseError", "the daemon refused");
        SubmissionTree.reference(failed.getParent().getParent(), "fulfills", preamble);
        this.tree.parsedFor(this.tree.documentRequirement("questionnaire"), "1. How was your visit?");

        this.handler.execute(taskReading("preamble", "questionnaire"));

        Mockito.verifyNoInteractions(this.intake);
        assertEquals("failed", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
        assertEquals("The document could not be read. the daemon refused",
            this.submission.getValueMap().get(ExtractionStatus.MESSAGE, String.class));
    }

    @Test
    void failsWhenNoneOfTheNamedDocumentsWasRead() throws Exception
    {
        this.variables.clear();
        this.tree.documentRequirement("questionnaire");

        this.handler.execute(taskReading("preamble", "questionnaire"));

        Mockito.verifyNoInteractions(this.intake);
        assertEquals("failed", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
    }

    // An answer points at the document it was read from. One whose document has gone cannot say that, and is
    // not written at all rather than written pointing at nothing.
    @Test
    void refusesToRecordAnAnswerWhoseDocumentIsGone() throws Exception
    {
        final File gone = this.file.adaptTo(File.class);
        this.context.resourceResolver().delete(this.file);

        assertThrows(PersistenceException.class, () -> ExtractedAnswers.write(this.context.resourceResolver(),
            this.submission, this.aims.adaptTo(Question.class),
            new FieldResult(AIMS, true, 0.9, "Care", null, List.of()), List.of(gone)));
    }

    @Test
    void failsWhenNothingHasBeenParsed() throws Exception
    {
        final SubmissionTree bare = new SubmissionTree(new SlingContext(ResourceResolverType.JCR_MOCK));
        bare.schemaVersion();
        bare.question("aims", "Find the primary aims.");

        this.handler.execute(TaskContexts.of(bare.submission(), Map.of(), this.variables));

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
        Mockito.when(this.intake.run(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any()))
            .thenReturn(IntakeResult.nothingRead());

        this.handler.execute(task());

        assertEquals("failed", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
        assertTrue(answers().isEmpty());
    }

    // Throwing would revert the whole reading and leave the committed `running` behind, with no step left
    // to move it on: the submission would wait for a reading that had already given up
    @Test
    void recordsAnUnreadableDocumentAsAFailedReading() throws Exception
    {
        Mockito.when(this.intake.run(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any()))
            .thenThrow(new IOException("no content"));

        this.handler.execute(task());

        assertEquals("failed", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
        assertTrue(answers().isEmpty());
    }

    @Test
    void refusesToRecordAnAnswerWhoseQuestionCannotBeReferenced() throws Exception
    {
        modelFinds(new FieldResult(AIMS, true, 0.9, "Something", "", List.of()));
        // The question is read into the field list first, then gone by the time the answer is written
        Mockito.when(this.intake.run(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any()))
            .thenAnswer(invocation -> {
                this.context.resourceResolver().delete(this.aims);
                return new IntakeResult(Map.of(AIMS, new FieldResult(AIMS, true, 0.9, "Something", "", List.of())),
                    false);
            });

        assertThrows(PersistenceException.class, () -> this.handler.execute(task()));
    }

    // Domain knowledge that is only worth sending for one schema sits on the step, not on every reading
    @Test
    void prependsThePromptTheStepNames() throws Exception
    {
        modelFinds();

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), this.variables,
            Map.of(IntakeAnswersHandler.PROMPT_FROM, Prompts.INTAKE_SYSTEM)));

        final ArgumentCaptor<String> extra = ArgumentCaptor.forClass(String.class);
        Mockito.verify(this.intake).run(Mockito.any(), Mockito.any(), Mockito.anyList(), extra.capture());
        assertTrue(extra.getValue().contains("intake extraction engine"), extra.getValue());
    }

    @Test
    void concatenatesEveryPromptTheStepNames()
    {
        final String extra = IntakeAnswersHandler.extraSystem(TaskContexts.of(this.submission, Map.of(),
            this.variables, Map.of(IntakeAnswersHandler.PROMPT_FROM,
                new String[] { Prompts.PROTOCOL_STRUCTURE, " ", Prompts.INTAKE_SYSTEM })));

        assertTrue(extra.contains("B.1 General information"), extra);
        assertTrue(extra.contains("intake extraction engine"), extra);
        assertTrue(extra.indexOf("B.1 General information") < extra.indexOf("intake extraction engine"), extra);
    }

    @Test
    void addsNoExtraPromptWhenTheStepNamesNone()
    {
        assertNull(IntakeAnswersHandler.extraSystem(task()));
        assertNull(IntakeAnswersHandler.extraSystem(TaskContexts.of(this.submission, Map.of(), this.variables,
            Map.of(IntakeAnswersHandler.PROMPT_FROM, " "))));
    }
}

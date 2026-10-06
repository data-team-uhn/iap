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
import org.apache.sling.api.resource.ResourceResolver;
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
import io.uhndata.iap.submissions.models.Submission;
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

    /** A prompt file only the test classpath carries, so a step naming two prompts has two to name. */
    private static final String REFERENCE_PROMPT = "test_reference.md";

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
        this.tree.createSchemaVersion();
        this.aims = this.tree.createQuestion("aims", "Find the primary aims.");
        this.tree.createQuestion("title", "Find the title.");
        this.tree.createQuestion("comments", null);
        this.submission = this.tree.createSubmission();
        this.file = this.tree.createFile("completed");
        final Field field = IntakeAnswersHandler.class.getDeclaredField("intake");
        field.setAccessible(true);
        field.set(this.handler, this.intake);
        final Field documents = IntakeAnswersHandler.class.getDeclaredField("documents");
        documents.setAccessible(true);
        documents.set(this.handler, new ParsedDocuments());
        final Field runs = IntakeAnswersHandler.class.getDeclaredField("runs");
        runs.setAccessible(true);
        runs.set(this.handler, new ReadingRuns());
    }

    private WorkflowTaskContext createTask()
    {
        return TaskContexts.of(this.submission, Map.of(), this.variables);
    }

    /** A step that says which requirement it reads, the way a diagram names one. */
    private WorkflowTaskContext createTaskAsking(final String requirement)
    {
        return TaskContexts.of(this.submission, Map.of(), this.variables,
            Map.of(IntakeAnswersHandler.REQUIREMENT, requirement));
    }

    private void stubModelFinds(final FieldResult... results) throws IOException
    {
        final Map<String, FieldResult> fields = new HashMap<>();
        for (final FieldResult result : results) {
            fields.put(result.name(), result);
        }
        Mockito.when(this.intake.run(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any()))
            .thenReturn(new IntakeResult(fields, false));
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

    private static Resource getOnlyChild(final Resource parent)
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
        stubModelFinds();

        this.handler.execute(createTask());

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
        stubModelFinds();

        this.handler.execute(createTaskAsking("study"));

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<List<ExtractionField>> fields = ArgumentCaptor.forClass(List.class);
        Mockito.verify(this.intake).run(Mockito.any(), Mockito.any(), fields.capture(), Mockito.any());
        assertEquals(List.of(AIMS, TITLE),
            fields.getValue().stream().map(ExtractionField::name).sorted().toList());
    }

    // A proposal is read once for what every proposal is asked and what its study type adds, in one call: the
    // document is the expensive part
    @Test
    void asksSeveralRequirementsInOneCall() throws Exception
    {
        createExtraRequirement();
        stubModelFinds();

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), this.variables,
            Map.of(IntakeAnswersHandler.REQUIREMENT, new String[] { "study", "extra" })));

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<List<ExtractionField>> fields = ArgumentCaptor.forClass(List.class);
        Mockito.verify(this.intake).run(Mockito.any(), Mockito.any(), fields.capture(), Mockito.any());
        assertEquals(List.of(AIMS, TITLE, "extra/sites"),
            fields.getValue().stream().map(ExtractionField::name).toList(), "in the order the requirements are named");
    }

    // A diagram writes a list into this attribute as one comma-separated string
    @Test
    void readsSeveralRequirementsFromACommaList() throws Exception
    {
        createExtraRequirement();
        stubModelFinds();

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), this.variables,
            Map.of(IntakeAnswersHandler.REQUIREMENT, "study, ,extra")));

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<List<ExtractionField>> fields = ArgumentCaptor.forClass(List.class);
        Mockito.verify(this.intake).run(Mockito.any(), Mockito.any(), fields.capture(), Mockito.any());
        assertEquals(List.of(AIMS, TITLE, "extra/sites"),
            fields.getValue().stream().map(ExtractionField::name).toList());
    }

    /** A second requirement, holding one question, to name beside {@code study}. */
    private void createExtraRequirement()
    {
        this.context.create().resource(SubmissionTree.VERSION_PATH + "/extra", Map.of(
            "sling:resourceType", "sch/FormRequirement", "sling:resourceSuperType", "sch/Requirement",
            "label", "Extra"));
        this.context.create().resource(SubmissionTree.VERSION_PATH + "/extra/sites", Map.of(
            "sling:resourceType", "sch/Question", "sling:resourceSuperType", "sch/FormItem",
            "text", "Which sites?", "dataType", "text", "extractionPrompt", "List the sites."));
    }

    /** A step that writes only when one named field came back as a given value. */
    private WorkflowTaskContext createTaskRecordingWhen(final String gate)
    {
        return TaskContexts.of(this.submission, Map.of(), this.variables,
            Map.of(IntakeAnswersHandler.RECORD_WHEN, gate));
    }

    // The model is still asked everything. A document that is not a proposal must not leave those
    // answers behind, and the reading must still be marked done so the view does not spin.
    @Test
    void writesNothingWhenTheGatedAnswerIsNotTheExpectedValue() throws Exception
    {
        stubModelFinds(new FieldResult(AIMS, true, 0.9, "not-proposal", "", List.of()),
            new FieldResult(TITLE, true, 0.9, "A title", "", List.of()));

        this.handler.execute(createTaskRecordingWhen(AIMS + "=proposal"));

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<List<ExtractionField>> fields = ArgumentCaptor.forClass(List.class);
        Mockito.verify(this.intake).run(Mockito.any(), Mockito.any(), fields.capture(), Mockito.any());
        assertEquals(List.of(AIMS, TITLE),
            fields.getValue().stream().map(ExtractionField::name).sorted().toList());
        assertTrue(getAnswers().isEmpty());
        assertEquals("done", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
    }

    @Test
    void writesNothingWhenTheGatedAnswerWasNotFound() throws Exception
    {
        stubModelFinds(new FieldResult(AIMS, false, 0.0, null, "Not stated.", List.of()),
            new FieldResult(TITLE, true, 0.9, "A title", "", List.of()));

        this.handler.execute(createTaskRecordingWhen(AIMS + "=proposal"));

        assertTrue(getAnswers().isEmpty());
        assertEquals("done", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
    }

    @Test
    void recordsWhenTheGatedAnswerMatches() throws Exception
    {
        stubModelFinds(new FieldResult(AIMS, true, 0.9, "proposal", "", List.of()),
            new FieldResult(TITLE, true, 0.9, "A title", "", List.of()));

        this.handler.execute(createTaskRecordingWhen(AIMS + "=proposal"));

        assertEquals(2, getAnswers().size());
        assertEquals("done", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
    }

    @Test
    void recordsWhenNoWriteGateIsSet()
    {
        final Submission read = this.submission.adaptTo(Submission.class);
        final ResourceResolver resolver = this.context.resourceResolver();
        assertTrue(IntakeAnswersHandler.shouldRecord(null, Map.of(), read, resolver));
        assertTrue(IntakeAnswersHandler.shouldRecord(" ", Map.of(), read, resolver));
    }

    @Test
    void doesNotRecordWhenTheWriteGateIsMalformedOrUnanswered()
    {
        final Submission read = this.submission.adaptTo(Submission.class);
        final ResourceResolver resolver = this.context.resourceResolver();
        assertFalse(IntakeAnswersHandler.shouldRecord("no-equals", Map.of(), read, resolver));
        assertFalse(IntakeAnswersHandler.shouldRecord("=proposal", Map.of(), read, resolver));
        assertFalse(IntakeAnswersHandler.shouldRecord(AIMS + "=", Map.of(), read, resolver));
        assertFalse(IntakeAnswersHandler.shouldRecord(AIMS + "= ", Map.of(), read, resolver));
        assertFalse(IntakeAnswersHandler.shouldRecord(" =proposal", Map.of(), read, resolver));
        assertFalse(IntakeAnswersHandler.shouldRecord(AIMS + "=proposal", Map.of(), read, resolver));
    }

    // A model names an option the way a person reads it; what counts is the value it stands for
    @Test
    void recordsWhenTheGatedAnswerNamesTheValueDifferently() throws Exception
    {
        stubModelFinds(new FieldResult(AIMS, true, 0.9, "Proposal", "", List.of()),
            new FieldResult(TITLE, true, 0.9, "A title", "", List.of()));

        this.handler.execute(createTaskRecordingWhen(AIMS + "=proposal"));

        assertEquals(2, getAnswers().size());
    }

    // Reading again does not ask an answered question, so the gate is read from the answer it already has
    @Test
    void readsTheGateFromAnAnswerItNoLongerAsks() throws Exception
    {
        final Resource gate = this.context.create().resource(this.submission.getPath() + "/a1",
            Map.of("jcr:primaryType", "sub:Answer", "sling:resourceType", "sub/Answer",
                "value", new String[] { "proposal" }));
        SubmissionTree.reference(gate, "question", this.aims);
        stubModelFinds(new FieldResult(TITLE, true, 0.9, "A title", "", List.of()));

        this.handler.execute(createTaskRecordingWhen(AIMS + "=proposal"));

        assertEquals(2, getAnswers().size(), "the gate answer it had, and the title it read");
    }

    // A step whose requirement is not being asked has nothing to do, and must not say the schema asks
    // nothing of the document: another step is asking plenty
    @Test
    void doesNothingWhenTheRequirementItNamesIsNotThere() throws Exception
    {
        this.handler.execute(createTaskAsking("nothing-by-that-name"));

        Mockito.verifyNoInteractions(this.intake);
        assertNull(this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
    }

    @Test
    void recordsWhatTheModelFoundWithItsEvidence() throws Exception
    {
        stubModelFinds(new FieldResult(AIMS, true, 0.85, "To reduce readmissions", "Stated under Aims.",
            List.of(new FieldResult.Passage("To reduce readmissions", 3L).placeUnder("Aims"),
                new FieldResult.Passage("also here", null))));

        this.handler.execute(createTask());

        final Resource answer = getAnswers().get(0);
        assertEquals(1, getAnswers().size());
        assertEquals(SubmissionTree.getIdentifier(this.aims), answer.getValueMap().get("question", String.class));
        assertArrayEquals(new String[] { "To reduce readmissions" }, answer.getValueMap().get("value", String[].class));
        final Resource extraction = getOnlyChild(answer);
        assertEquals("sub:Extraction", extraction.getValueMap().get("jcr:primaryType", String.class));
        assertEquals("To reduce readmissions", extraction.getValueMap().get("extractedAnswer", String.class));
        assertEquals(0.85, extraction.getValueMap().get("confidence", Double.class));
        assertEquals("Stated under Aims.", extraction.getValueMap().get("reasoning", String.class));
        assertArrayEquals(new String[] { SubmissionTree.getIdentifier(this.file.getParent()) },
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

    // The gate earlier in the same reading failed; the intake finishing must not hide that
    @Test
    void keepsAFailureAnEarlierStepRecorded() throws Exception
    {
        ExtractionStatus.record(this.submission, ExtractionStatus.FAILED, "The model could not be reached");
        stubModelFinds(new FieldResult(AIMS, true, 0.85, "To reduce readmissions", "", List.of()));

        this.handler.execute(createTask());

        assertEquals(1, getAnswers().size());
        assertEquals(ExtractionStatus.FAILED,
            this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
    }

    // A question that takes several answers is asked for them as one comma-separated string, so storing
    // that string whole would leave the answer as one value nobody asked for
    @Test
    void storesAMultiValuedAnswerAsOneValuePerName() throws Exception
    {
        this.tree.createQuestion("sites", "List the participating sites.", 0);
        stubModelFinds(new FieldResult("study/sites", true, 0.9, "Sunnybrook, St. Michael's , SickKids", "",
            List.of()));

        this.handler.execute(createTask());

        assertArrayEquals(new String[] { "Sunnybrook", "St. Michael's", "SickKids" },
            getAnswers().get(0).getValueMap().get("value", String[].class));
        assertEquals("Sunnybrook, St. Michael's , SickKids",
            getOnlyChild(getAnswers().get(0)).getValueMap().get("extractedAnswer", String.class),
            "the model's answer is kept as it came");
    }

    // Splitting it away would store the question as unanswered, losing what the model did say
    @Test
    void keepsAMultiValuedAnswerThatSplitsIntoNothing() throws Exception
    {
        this.tree.createQuestion("sites", "List the participating sites.", 0);
        stubModelFinds(new FieldResult("study/sites", true, 0.9, " , ", "", List.of()));

        this.handler.execute(createTask());

        assertArrayEquals(new String[] { " , " }, getAnswers().get(0).getValueMap().get("value", String[].class));
    }

    @Test
    void leavesOutWhatTheModelDidNotFind() throws Exception
    {
        stubModelFinds(new FieldResult(AIMS, false, 0.0, null, "Not stated.", List.of()));

        this.handler.execute(createTask());

        assertTrue(getAnswers().isEmpty());
        assertEquals("done", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
    }

    @Test
    void leavesAQuestionTheSubmitterAlreadyAnsweredAlone() throws Exception
    {
        final Resource theirs = this.context.create().resource(this.submission.getPath() + "/a1",
            Map.of("jcr:primaryType", "sub:Answer", "sling:resourceType", "sub/Answer",
                "value", new String[] { "What the person said" }));
        SubmissionTree.reference(theirs, "question", this.aims);
        stubModelFinds(new FieldResult(AIMS, true, 0.9, "What the model read", "", List.of()));

        this.handler.execute(createTask());

        assertEquals(1, getAnswers().size());
        assertArrayEquals(new String[] { "What the person said" },
            getAnswers().get(0).getValueMap().get("value", String[].class));
    }

    // The form saves a field when it loses focus, so an empty answer is often just a field somebody clicked through
    @Test
    void fillsAFieldTheFormSavedEmpty() throws Exception
    {
        final Resource empty = this.context.create().resource(this.submission.getPath() + "/a1",
            Map.of("jcr:primaryType", "sub:Answer", "sling:resourceType", "sub/Answer", "value", new String[] { "" }));
        SubmissionTree.reference(empty, "question", this.aims);
        stubModelFinds(new FieldResult(AIMS, true, 0.9, "What the model read", "", List.of()));

        this.handler.execute(createTask());

        assertEquals(1, getAnswers().size(), "the empty answer is filled, not joined by a second one");
        assertArrayEquals(new String[] { "What the model read" },
            getAnswers().get(0).getValueMap().get("value", String[].class));
        assertEquals("What the model read",
            getOnlyChild(getAnswers().get(0)).getValueMap().get("extractedAnswer", String.class));
    }

    // The submitter emptied what the model suggested, which says they do not want it back
    @Test
    void leavesASuggestionTheSubmitterClearedEmpty() throws Exception
    {
        final Resource cleared = this.context.create().resource(this.submission.getPath() + "/a1",
            Map.of("jcr:primaryType", "sub:Answer", "sling:resourceType", "sub/Answer", "value", new String[] { "" }));
        SubmissionTree.reference(cleared, "question", this.aims);
        this.context.create().resource(cleared.getPath() + "/e1", Map.of("jcr:primaryType", "sub:Extraction",
            "sling:resourceType", "sub/Extraction", "extractedAnswer", "What the model read before"));
        stubModelFinds(new FieldResult(AIMS, true, 0.9, "What the model read", "", List.of()));

        this.handler.execute(createTask());

        assertEquals(1, getAnswers().size());
        assertArrayEquals(new String[] { "" }, getAnswers().get(0).getValueMap().get("value", String[].class));
    }

    // Reading again fills the gaps, so a question already answered is not paid for a second time
    @Test
    void asksTheModelNothingWhenEveryQuestionIsAnswered() throws Exception
    {
        final Resource theirs = this.context.create().resource(this.submission.getPath() + "/a1",
            Map.of("jcr:primaryType", "sub:Answer", "sling:resourceType", "sub/Answer",
                "value", new String[] { "What the person said" }));
        SubmissionTree.reference(theirs, "question", this.aims);
        final Resource title = this.context.create().resource(this.submission.getPath() + "/a2",
            Map.of("jcr:primaryType", "sub:Answer", "sling:resourceType", "sub/Answer",
                "value", new String[] { "A title" }));
        SubmissionTree.reference(title, "question",
            this.context.resourceResolver().getResource(SubmissionTree.FORM_PATH + "/title"));

        this.handler.execute(createTask());

        Mockito.verifyNoInteractions(this.intake);
        assertEquals("done", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
        assertNull(this.submission.getValueMap().get(ExtractionStatus.MESSAGE, String.class));
    }

    // A reading workflow need not gate: a schema whose document is only ever the one kind it asks for has
    // nothing to decide, and the step reads whatever the parse produced.
    @Test
    void readsTheParsedDocumentWhenNoGateRan() throws Exception
    {
        this.variables.clear();
        stubModelFinds(new FieldResult(AIMS, true, 0.9, "To reduce readmissions", "Stated under Aims.", List.of()));

        this.handler.execute(createTask());

        assertEquals("done", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
        assertEquals(SubmissionTree.getIdentifier(this.aims),
            getAnswers().get(0).getValueMap().get("question", String.class));
        assertArrayEquals(new String[] { "To reduce readmissions" },
            getAnswers().get(0).getValueMap().get("value", String[].class));
    }

    /** A step that reads the named documents, in the order given. */
    private WorkflowTaskContext createTaskReading(final String... documents)
    {
        return TaskContexts.of(this.submission, Map.of(), this.variables,
            Map.of(IntakeAnswersHandler.DOCUMENTS, documents));
    }

    /** The text the model was sent. */
    private String getSentText() throws IOException
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
        final Resource questionnaire = this.tree.createParsedFileFor(
            this.tree.createDocumentRequirement("questionnaire"), "1. How was your visit?");
        final Resource preamble = this.tree.createParsedFileFor(this.tree.createDocumentRequirement("preamble"),
            "This survey asks about your care.");
        stubModelFinds(new FieldResult(AIMS, true, 0.9, "Care", "Stated in the preamble.", List.of(
            new FieldResult.Passage("This survey asks about your care.", 1L).placeIn(0),
            new FieldResult.Passage("How was your visit?", 1L).placeIn(1))));

        this.handler.execute(createTaskReading("preamble", "questionnaire"));

        assertEquals("# Preamble\n\nThis survey asks about your care.\n\n"
            + "# Questionnaire\n\n1. How was your visit?", getSentText());
        assertArrayEquals(new String[] { SubmissionTree.getIdentifier(preamble.getParent()),
            SubmissionTree.getIdentifier(questionnaire.getParent()) },
            getOnlyChild(getAnswers().get(0)).getValueMap().get("sources", String[].class),
            "read from both documents");
        // Each quote is stored with the document it came from, so its link opens that one
        final List<Resource> evidence = new ArrayList<>();
        getOnlyChild(getAnswers().get(0)).getChildren().forEach(evidence::add);
        assertEquals(SubmissionTree.getIdentifier(preamble.getParent()),
            evidence.get(0).getValueMap().get("source", String.class));
        assertEquals(SubmissionTree.getIdentifier(questionnaire.getParent()),
            evidence.get(1).getValueMap().get("source", String.class));
    }

    // The preamble is optional. A step naming it reads what there is rather than refusing to read at all.
    @Test
    void readsTheNamedDocumentsThatWereUploaded() throws Exception
    {
        this.variables.clear();
        final Resource preamble = this.tree.createDocumentRequirement("preamble");
        // Attached for it but nothing uploaded yet, and a document with no version at all
        SubmissionTree.reference(this.tree.createEmptyDocument().getParent(), "fulfills", preamble);
        SubmissionTree.reference(this.context.create().resource(SubmissionTree.SUBMISSION_PATH + "/bare",
            Map.of("sling:resourceType", "sub/Document")), "fulfills", preamble);
        this.tree.createParsedFileFor(this.tree.createDocumentRequirement("questionnaire"), "1. How was your visit?");
        stubModelFinds();

        this.handler.execute(createTaskReading("preamble", "questionnaire"));

        assertEquals("# Questionnaire\n\n1. How was your visit?", getSentText());
    }

    // One of the named files failed to parse. Reading the other and calling the step done would hide that
    // failure: the view shows nothing once a reading is done, so the person would never be offered a retry.
    @Test
    void failsWhenANamedDocumentCouldNotBeRead() throws Exception
    {
        this.variables.clear();
        final Resource preamble = this.tree.createDocumentRequirement("preamble");
        final Resource failed = this.tree.createFile("failed");
        final ModifiableValueMap properties = failed.adaptTo(ModifiableValueMap.class);
        assertNotNull(properties);
        properties.put("parseError", "the daemon refused");
        SubmissionTree.reference(failed.getParent().getParent(), "fulfills", preamble);
        this.tree.createParsedFileFor(this.tree.createDocumentRequirement("questionnaire"), "1. How was your visit?");

        this.handler.execute(createTaskReading("preamble", "questionnaire"));

        Mockito.verifyNoInteractions(this.intake);
        assertEquals("failed", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
        assertEquals("The document could not be read. the daemon refused",
            this.submission.getValueMap().get(ExtractionStatus.MESSAGE, String.class));
    }

    @Test
    void failsWhenNoneOfTheNamedDocumentsWasRead() throws Exception
    {
        this.variables.clear();
        this.tree.createDocumentRequirement("questionnaire");

        this.handler.execute(createTaskReading("preamble", "questionnaire"));

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
            this.submission, null, this.aims.adaptTo(Question.class),
            new FieldResult(AIMS, true, 0.9, "Care", null, List.of()), List.of(gone)));
    }

    @Test
    void failsWhenNothingHasBeenParsed() throws Exception
    {
        // Remove the one parsed document, so the submission has none
        this.context.resourceResolver().delete(this.file.getParent().getParent());

        this.handler.execute(createTask());

        Mockito.verifyNoInteractions(this.intake);
        assertEquals("failed", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
        assertEquals("No document has been uploaded",
            this.submission.getValueMap().get(ExtractionStatus.MESSAGE, String.class));
    }

    @Test
    void isDoneAtOnceWhenTheSchemaAsksNothingOfTheDocument() throws Exception
    {
        // Delete the questions with prompts, leaving only the one meant for the submitter
        this.context.resourceResolver().delete(this.aims);
        this.context.resourceResolver().delete(
            this.context.resourceResolver().getResource(SubmissionTree.FORM_PATH + "/title"));

        this.handler.execute(createTask());

        assertEquals("done", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
        assertEquals("The schema asks nothing of the document",
            this.submission.getValueMap().get(ExtractionStatus.MESSAGE, String.class));
        Mockito.verifyNoInteractions(this.intake);
    }

    @Test
    void failsWhenTheModelsAnswerCouldNotBeRead() throws Exception
    {
        Mockito.when(this.intake.run(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any()))
            .thenReturn(IntakeResult.createNothingRead());

        this.handler.execute(createTask());

        assertEquals("failed", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
        assertTrue(getAnswers().isEmpty());
    }

    // Recorded, not thrown: throwing would revert the answers earlier steps wrote and replace this reason with a
    // generic one
    @Test
    void recordsAnUnreachableModelAsAFailedReading() throws Exception
    {
        Mockito.when(this.intake.run(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any()))
            .thenThrow(new IOException("connection refused"));

        this.handler.execute(createTask());

        assertEquals("failed", this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
        assertEquals(IntakeResult.UNREACHABLE,
            this.submission.getValueMap().get(ExtractionStatus.MESSAGE, String.class));
        assertTrue(getAnswers().isEmpty());
    }

    @Test
    void recordsTextItCannotReadBackAsAFailedReading() throws Exception
    {
        final Field documents = IntakeAnswersHandler.class.getDeclaredField("documents");
        documents.setAccessible(true);
        documents.set(this.handler, new ParsedDocuments()
        {
            @Override
            public DocumentScan scan(final File file) throws IOException
            {
                throw new IOException("the binary is gone");
            }
        });

        this.handler.execute(createTask());

        assertEquals(IntakeResult.TEXT_UNREADABLE,
            this.submission.getValueMap().get(ExtractionStatus.MESSAGE, String.class));
        Mockito.verifyNoInteractions(this.intake);
    }

    @Test
    void aStopAbortsTheStep() throws Exception
    {
        Mockito.when(this.intake.run(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any()))
            .thenThrow(new ReadingRuns.Stopped());

        final PersistenceException failure =
            assertThrows(PersistenceException.class, () -> this.handler.execute(createTask()));

        assertEquals(ExtractionStatus.STOPPED, failure.getMessage());
    }

    // A stop that lands after the model answered must still undo the step, and not stay set on the thread
    @Test
    void aStopAfterTheModelAnsweredAbortsTheStep() throws Exception
    {
        Mockito.when(this.intake.run(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any()))
            .thenAnswer(invocation -> {
                Thread.currentThread().interrupt();
                return new IntakeResult(Map.of(AIMS, new FieldResult(AIMS, true, 0.9, "Something", "", List.of())),
                    false);
            });

        try {
            final PersistenceException failure =
                assertThrows(PersistenceException.class, () -> this.handler.execute(createTask()));
            assertTrue(ReadingRuns.isStopped(failure.getCause()));
            assertFalse(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void anInterruptedCallAbortsTheStep() throws Exception
    {
        Mockito.when(this.intake.run(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any()))
            .thenAnswer(invocation -> {
                Thread.currentThread().interrupt();
                throw new IOException("closed");
            });

        try {
            final PersistenceException failure =
                assertThrows(PersistenceException.class, () -> this.handler.execute(createTask()));
            assertEquals(ExtractionStatus.STOPPED, failure.getMessage());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void leavesNoAnswerWhenItsQuestionIsGoneByTheTimeItIsWritten() throws Exception
    {
        stubModelFinds(new FieldResult(AIMS, true, 0.9, "Something", "", List.of()));
        // The question is read into the field list first, then gone by the time the answer is written
        Mockito.when(this.intake.run(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any()))
            .thenAnswer(invocation -> {
                this.context.resourceResolver().delete(this.aims);
                return new IntakeResult(Map.of(AIMS, new FieldResult(AIMS, true, 0.9, "Something", "", List.of())),
                    false);
            });

        this.handler.execute(createTask());

        assertTrue(getAnswers().isEmpty(), "nothing half made is left for the commit to refuse");
    }

    // Domain knowledge that is only worth sending for one schema sits on the step, not on every reading
    @Test
    void prependsThePromptTheStepNames() throws Exception
    {
        stubModelFinds();

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), this.variables,
            Map.of(IntakeAnswersHandler.PROMPT_FROM, Prompts.INTAKE_SYSTEM)));

        final ArgumentCaptor<String> extra = ArgumentCaptor.forClass(String.class);
        Mockito.verify(this.intake).run(Mockito.any(), Mockito.any(), Mockito.anyList(), extra.capture());
        assertTrue(extra.getValue().contains("intake extraction engine"), extra.getValue());
    }

    @Test
    void concatenatesEveryPromptTheStepNames()
    {
        final String extra = IntakeAnswersHandler.getExtraSystem(TaskContexts.of(this.submission, Map.of(),
            this.variables, Map.of(IntakeAnswersHandler.PROMPT_FROM,
                new String[] { REFERENCE_PROMPT, " ", Prompts.INTAKE_SYSTEM })));

        assertTrue(extra.contains("A reference only the tests carry"), extra);
        assertTrue(extra.contains("intake extraction engine"), extra);
        assertTrue(extra.indexOf("A reference only the tests carry") < extra.indexOf("intake extraction engine"),
            extra);
    }

    @Test
    void addsNoExtraPromptWhenTheStepNamesNone()
    {
        assertNull(IntakeAnswersHandler.getExtraSystem(createTask()));
        assertNull(IntakeAnswersHandler.getExtraSystem(TaskContexts.of(this.submission, Map.of(), this.variables,
            Map.of(IntakeAnswersHandler.PROMPT_FROM, " "))));
    }
}

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;

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
import io.uhndata.iap.submissions.models.Answer;
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
 * Unit tests for {@link IntakeAnswersHandler}: which questions go to the model, and how its answers are saved.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class IntakeAnswersHandlerTest
{
    private static final String AIMS = "study.aims";

    private static final String TITLE = "study.title";

    private static final String VALUE = "value";

    /** A prompt file that only the tests have, so a test can name two prompts on one step. */
    private static final String REFERENCE_PROMPT = "test_reference.md";

    /** What {@link #REFERENCE_PROMPT} says. */
    private static final String REFERENCE_TEXT = "A reference only the tests carry";

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

    /** A step that asks the {@code study} requirement, which holds the aims and the title. */
    private WorkflowTaskContext createTask()
    {
        return createTaskAsking("study");
    }

    /** A step that names the requirement it reads, the way a diagram does. */
    private WorkflowTaskContext createTaskAsking(final String requirement)
    {
        return TaskContexts.of(this.submission, Map.of(), this.variables,
            Map.of(IntakeAnswersHandler.REQUIREMENT_PARAMETER, requirement));
    }

    private void stubModelFinds(final FieldResult... results) throws IOException
    {
        final Map<String, FieldResult> fields = new HashMap<>();
        for (final FieldResult result : results) {
            fields.put(result.key(), result);
        }
        Mockito.when(this.intake.askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any()))
            .thenReturn(IntakeResult.createRead(fields));
    }

    /** The groups of questions the one reading asked, as group name to question keys, in the order asked. */
    private Map<String, List<String>> getAskedGroups()
    {
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<List<ExtractionPrompt.Group>> groups = ArgumentCaptor.forClass(List.class);
        Mockito.verify(this.intake).askQuestions(Mockito.any(), Mockito.any(), groups.capture(), Mockito.any());
        final Map<String, List<String>> asked = new LinkedHashMap<>();
        groups.getValue().forEach(group -> asked.put(group.name(),
            group.fields().stream().map(ExtractionField::key).toList()));
        return asked;
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

        assertEquals(Map.of("study", List.of(AIMS, TITLE)), getAskedGroups(),
            "the question with no prompt is the submitter's to answer");
        assertNull(this.submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class),
            "finishReading, not the intake, ends the reading");
    }

    // A schema read in stages names one requirement per step, so each call asks about that requirement only,
    // not the whole schema version
    @Test
    void asksOnlyTheRequirementTheStepNames() throws Exception
    {
        stubModelFinds();

        this.handler.execute(createTaskAsking("study"));

        assertEquals(Map.of("study", List.of(AIMS, TITLE)), getAskedGroups());
    }

    // Each requirement is asked in calls of its own, in the order named; the document is cached after the first
    @Test
    void asksSeveralRequirementsEachAsItsOwnGroup() throws Exception
    {
        createExtraRequirement();
        stubModelFinds();

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), this.variables,
            Map.of(IntakeAnswersHandler.REQUIREMENT_PARAMETER, new String[] { "study", "extra" })));

        final Map<String, List<String>> asked = getAskedGroups();
        assertEquals(List.of("study", "extra"), List.copyOf(asked.keySet()), "in the order the requirements are named");
        assertEquals(List.of(AIMS, TITLE), asked.get("study"));
        assertEquals(List.of("extra.sites"), asked.get("extra"));
    }

    // A diagram writes a list in this attribute as one comma-separated string
    @Test
    void readsSeveralRequirementsFromACommaList() throws Exception
    {
        createExtraRequirement();
        stubModelFinds();

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), this.variables,
            Map.of(IntakeAnswersHandler.REQUIREMENT_PARAMETER, "study, ,extra")));

        assertEquals(List.of("study", "extra"), List.copyOf(getAskedGroups().keySet()));
    }

    /** Creates a second requirement with one question, to name next to {@code study}. */
    private void createExtraRequirement()
    {
        this.context.create().resource(SubmissionTree.VERSION_PATH + "/extra", Map.of(
            "sling:resourceType", "sch/FormRequirement", "sling:resourceSuperType", "sch/Requirement",
            "label", "Extra"));
        this.context.create().resource(SubmissionTree.VERSION_PATH + "/extra/sites", Map.of(
            "sling:resourceType", "sch/Question", "sling:resourceSuperType", "sch/FormItem",
            "text", "Which sites?", "dataType", "text", "extractionPrompt", "List the sites."));
    }

    // A misspelled name, or the name of a document requirement, is a mistake in the workflow
    @Test
    void failsWhenARequirementItNamesIsNotAFormOfTheSchema() throws Exception
    {
        this.tree.createDocumentRequirement("protocol");
        for (final String wrong : List.of("nothing-by-that-name", "study, stdy", "protocol")) {
            this.handler.execute(createTaskAsking(wrong));

            assertEquals("failed", this.submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class),
                wrong);
            assertEquals(IntakeAnswersHandler.SET_UP_WRONG_MESSAGE,
                this.submission.getValueMap().get(ExtractionStatus.MESSAGE_PROPERTY, String.class));
        }
        Mockito.verifyNoInteractions(this.intake);
    }

    @Test
    void namesARequirementByItsFullPathToo() throws Exception
    {
        stubModelFinds(new FieldResult(AIMS, List.of(), "", 0.0, List.of(), 0));

        this.handler.execute(createTaskAsking(SubmissionTree.FORM_PATH));

        assertEquals(List.of("study"), List.copyOf(getAskedGroups().keySet()).stream()
            .map(name -> name.substring(name.lastIndexOf('/') + 1)).toList());
    }

    @Test
    void recordsWhatTheModelFoundWithItsEvidence() throws Exception
    {
        stubModelFinds(new FieldResult(AIMS, List.of("To reduce readmissions"), "Stated under Aims.", 0.85,
            List.of(new FieldResult.Passage("To reduce readmissions", 3L, "Aims", -1),
                new FieldResult.Passage("also here", null, "", -1)), 2));

        this.handler.execute(createTask());

        final Resource answer = getAnswers().get(0);
        assertEquals(1, getAnswers().size());
        assertEquals(SubmissionTree.getIdentifier(this.aims), answer.getValueMap().get("question", String.class));
        assertNull(answer.getValueMap().get(VALUE, String[].class), "a suggestion is not an answer");
        final Resource extraction = getOnlyChild(answer);
        assertEquals("sub:Extraction", extraction.getValueMap().get("jcr:primaryType", String.class));
        assertEquals("To reduce readmissions", extraction.getValueMap().get("extractedValues", String.class));
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

    // The gate failed earlier in the same reading; the intake finishing must not hide that
    @Test
    void keepsAFailureAnEarlierStepRecorded() throws Exception
    {
        ExtractionStatus.recordStatus(this.submission, ExtractionStatus.STATUS_FAILED,
            "The model could not be reached");
        stubModelFinds(new FieldResult(AIMS, List.of("To reduce readmissions"), "", 0.85, List.of(), 0));

        this.handler.execute(createTask());

        assertEquals(1, getAnswers().size());
        assertEquals(ExtractionStatus.STATUS_FAILED,
            this.submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class));
    }

    // A value holding a comma stays one value, since the run saves each value on its own
    @Test
    void storesAMultiValuedAnswerAsOneValueEach() throws Exception
    {
        this.tree.createQuestion("sites", "List the participating sites.", 0);
        final List<String> sites = List.of("Sunnybrook Health Sciences Centre, Toronto", "SickKids");
        stubModelFinds(new FieldResult("study.sites", sites, "", 0.9, List.of(), 0));

        this.handler.execute(createTask());

        this.tree.stampTypes();
        assertEquals(sites, getAnswers().get(0).adaptTo(Answer.class).getSuggestedValues());
        assertArrayEquals(sites.toArray(new String[0]),
            getOnlyChild(getAnswers().get(0)).getValueMap().get("extractedValues", String[].class));
    }

    // A run that found nothing still records that the question was asked of this file
    @Test
    void recordsWhatTheModelDidNotFindAsARunWithNoAnswer() throws Exception
    {
        stubModelFinds(new FieldResult(AIMS, List.of(), "Not stated.", 0.0, List.of(), 0));

        this.handler.execute(createTask());

        final Resource run = getOnlyChild(getAnswers().get(0));
        assertNull(run.getValueMap().get("extractedValues", String.class));
        assertEquals("Not stated.", run.getValueMap().get("reasoning", String.class));
        assertNull(this.submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class),
            "finishReading, not the intake, ends the reading");
    }

    @Test
    void leavesAQuestionTheSubmitterAlreadyAnsweredAlone() throws Exception
    {
        final Resource theirs = this.context.create().resource(this.submission.getPath() + "/a1",
            Map.of("jcr:primaryType", "sub:Answer", "sling:resourceType", "sub/Answer",
                VALUE, new String[] { "What the person said" }));
        SubmissionTree.reference(theirs, "question", this.aims);
        stubModelFinds(new FieldResult(AIMS, List.of("What the model read"), "", 0.9, List.of(), 0));

        this.handler.execute(createTask());

        assertEquals(1, getAnswers().size());
        assertArrayEquals(new String[] { "What the person said" },
            getAnswers().get(0).getValueMap().get(VALUE, String[].class));
    }

    // The form saves a field when it loses focus, so an empty answer is often a field somebody just clicked past
    @Test
    void fillsAFieldTheFormSavedEmpty() throws Exception
    {
        final Resource empty = this.context.create().resource(this.submission.getPath() + "/a1",
            Map.of("jcr:primaryType", "sub:Answer", "sling:resourceType", "sub/Answer", VALUE, new String[] { "" }));
        SubmissionTree.reference(empty, "question", this.aims);
        stubModelFinds(new FieldResult(AIMS, List.of("What the model read"), "", 0.9, List.of(), 0));

        this.handler.execute(createTask());

        assertEquals(1, getAnswers().size(), "the run goes under the empty answer, not under a second one");
        assertArrayEquals(new String[] { "" }, getAnswers().get(0).getValueMap().get(VALUE, String[].class),
            "a suggestion is not an answer until the submitter acts on it");
        assertEquals("What the model read",
            getOnlyChild(getAnswers().get(0)).getValueMap().get("extractedValues", String.class));
    }

    // Only the submitter's own value counts as an answer, so a question with only a suggestion is asked again,
    // even one whose value the submitter emptied. The new run is the one shown.
    @Test
    void asksAgainAQuestionThatOnlyHasASuggestion() throws Exception
    {
        final Resource suggested = this.context.create().resource(this.submission.getPath() + "/a1",
            Map.of("jcr:primaryType", "sub:Answer", "sling:resourceType", "sub/Answer", VALUE, new String[] { "" }));
        SubmissionTree.reference(suggested, "question", this.aims);
        this.context.create().resource(suggested.getPath() + "/e1", Map.of("jcr:primaryType", "sub:Extraction",
            "sling:resourceType", "sub/Extraction", "extractedValues", "What the model read before"));
        stubModelFinds(new FieldResult(AIMS, List.of("What the model read"), "", 0.9, List.of(), 0));

        this.handler.execute(createTask());

        assertEquals(1, getAnswers().size());
        assertEquals(2, getExtractions(getAnswers().get(0)).size(), "a new run beside the old one");
        assertArrayEquals(new String[] { "" }, getAnswers().get(0).getValueMap().get(VALUE, String[].class));
    }

    // Reading again only fills the gaps, so a question that already has an answer is not asked again
    @Test
    void asksTheModelNothingWhenEveryQuestionIsAnswered() throws Exception
    {
        final Resource theirs = this.context.create().resource(this.submission.getPath() + "/a1",
            Map.of("jcr:primaryType", "sub:Answer", "sling:resourceType", "sub/Answer",
                VALUE, new String[] { "What the person said" }));
        SubmissionTree.reference(theirs, "question", this.aims);
        final Resource title = this.context.create().resource(this.submission.getPath() + "/a2",
            Map.of("jcr:primaryType", "sub:Answer", "sling:resourceType", "sub/Answer",
                VALUE, new String[] { "A title" }));
        SubmissionTree.reference(title, "question",
            this.context.resourceResolver().getResource(SubmissionTree.FORM_PATH + "/title"));

        this.handler.execute(createTask());

        Mockito.verifyNoInteractions(this.intake);
        assertNull(this.submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class),
            "finishReading, not the intake, ends the reading");
        assertNull(this.submission.getValueMap().get(ExtractionStatus.MESSAGE_PROPERTY, String.class));
    }

    /**
     * Creates an answer to the aims question with a value, and a run under it when {@code read} is given.
     *
     * @param value what the answer holds
     * @param read what the reading suggested, or {@code null} for an answer no reading backs
     * @param version the version the reading cites
     * @return the answer
     */
    private Resource answerAims(final String value, final String read, final Resource version)
    {
        final Resource answer = this.context.create().resource(this.submission.getPath() + "/a1",
            Map.of("jcr:primaryType", "sub:Answer", "sling:resourceType", "sub/Answer",
                VALUE, new String[] { value }));
        SubmissionTree.reference(answer, "question", this.aims);
        if (read != null) {
            final Resource extraction = this.context.create().resource(answer.getPath() + "/e1",
                Map.of("jcr:primaryType", "sub:Extraction", "sling:resourceType", "sub/Extraction",
                    "extractedValues", read, "confidence", 0.9));
            try {
                extraction.adaptTo(Node.class).setProperty("sources",
                    new Value[] { this.context.resourceResolver().adaptTo(Session.class).getValueFactory()
                        .createValue(version.adaptTo(Node.class)) });
                this.context.resourceResolver().commit();
            } catch (final RepositoryException | PersistenceException e) {
                throw new IllegalStateException(e);
            }
        }
        return answer;
    }

    private static List<Resource> getExtractions(final Resource answer)
    {
        final List<Resource> extractions = new ArrayList<>();
        answer.getChildren().forEach(extractions::add);
        return extractions;
    }

    // A newer file is read for every question. What it says is a new suggestion, never a new answer.
    @Test
    void addsARunForANewerRevisionAndLeavesTheAnswerAlone() throws Exception
    {
        final Resource answer = answerAims("What the person said", "What the model read", this.file.getParent());
        final Resource newer = this.tree.addVersion(this.file);
        stubModelFinds(new FieldResult(AIMS, List.of("What the new file says"), "", 0.8, List.of(), 0));

        this.handler.execute(createTask());

        assertArrayEquals(new String[] { "What the person said" },
            answer.getValueMap().get(VALUE, String[].class));
        final List<Resource> extractions = getExtractions(answer);
        assertEquals(2, extractions.size(), "the old reading stays");
        assertEquals("What the new file says", extractions.get(1).getValueMap().get("extractedValues", String.class));
        assertArrayEquals(new String[] { SubmissionTree.getIdentifier(newer.getParent()) },
            extractions.get(1).getValueMap().get("sources", String[].class));
    }

    // A run that agrees with the answer still records that the newer file was read, so it is not asked again
    @Test
    void readsANewerRevisionOnceEvenWhenItAgreesWithTheAnswer() throws Exception
    {
        final Resource answer = answerAims("What the person said", "What the model read", this.file.getParent());
        this.tree.addVersion(this.file);
        stubModelFinds(new FieldResult(AIMS, List.of("What the person said"), "", 0.8, List.of(), 0));

        this.handler.execute(createTask());
        this.tree.stampTypes();
        this.handler.execute(createTask());

        assertEquals(2, getExtractions(answer).size(), "one run for the newer file, not one per reading");
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<List<ExtractionPrompt.Group>> asked = ArgumentCaptor.forClass(List.class);
        Mockito.verify(this.intake, Mockito.atLeastOnce()).askQuestions(Mockito.any(), Mockito.any(), asked.capture(),
            Mockito.any());
        assertEquals(1, asked.getAllValues().stream().filter(groups -> groups.stream()
            .flatMap(group -> group.fields().stream()).anyMatch(field -> AIMS.equals(field.key()))).count(),
            "the aims are asked of the newer file once");
    }

    // Finding nothing in the newer file is still a run: the file was read for this question
    @Test
    void recordsANewerRevisionThatSaysNothing() throws Exception
    {
        final Resource answer = answerAims("What the person said", "What the model read", this.file.getParent());
        this.tree.addVersion(this.file);
        stubModelFinds(new FieldResult(AIMS, List.of(), "Not stated.", 0.0, List.of(), 0));

        this.handler.execute(createTask());

        assertArrayEquals(new String[] { "What the person said" },
            answer.getValueMap().get(VALUE, String[].class));
        assertEquals(2, getExtractions(answer).size());
        assertNull(getExtractions(answer).get(1).getValueMap().get("extractedValues", String.class));
    }

    // Each newer version counts on its own: one already read does not hide another one not read yet
    @Test
    void countsANewerVersionOfAnotherDocumentAsNewToo() throws Exception
    {
        final Resource newerProtocol = this.tree.addVersion(this.file);
        answerAims("What the person said", "What the model read", newerProtocol.getParent());
        final Resource newerConsent = this.tree.addVersion(this.tree.createFile("completed"));
        final List<Question> asked = List.of(this.aims.adaptTo(Question.class));

        assertEquals(Set.of(this.aims.getPath()), ExtractionFields.getUnreadInNewerVersions(
            SubmissionFiles.getSubmission(this.submission), asked,
            List.of(newerProtocol.adaptTo(File.class), newerConsent.adaptTo(File.class))));
        assertEquals(Set.of(), ExtractionFields.getUnreadInNewerVersions(
            SubmissionFiles.getSubmission(this.submission), asked, List.of(newerProtocol.adaptTo(File.class))),
            "the version already read is not new");
    }

    // A reading of a newer version where one call failed read only some questions from it; the next reading
    // asks the others again, even though the submitter answered them
    @Test
    void asksAgainTheAnsweredQuestionsANewerVersionWasNotReadFor() throws Exception
    {
        final Resource newer = this.tree.addVersion(this.file);
        answerAims("What the person said", "What the model read", newer.getParent());
        final Resource title = this.context.create().resource(this.submission.getPath() + "/a2",
            Map.of("jcr:primaryType", "sub:Answer", "sling:resourceType", "sub/Answer",
                VALUE, new String[] { "A title" }));
        SubmissionTree.reference(title, "question",
            this.context.resourceResolver().getResource(SubmissionTree.FORM_PATH + "/title"));
        stubModelFinds(new FieldResult(TITLE, List.of("A newer title"), "", 0.8, List.of(), 0));

        this.handler.execute(createTask());

        assertEquals(Map.of("study", List.of(TITLE)), getAskedGroups(), "the aims were read from it already");
    }

    // The newest version was already read for the aims, so only the gaps are filled and the answer stays
    @Test
    void readingANewerRevisionAgainOnlyFillsTheGaps() throws Exception
    {
        final Resource newer = this.tree.addVersion(this.file);
        final Resource answer = answerAims("What the person said", "What the model read", newer.getParent());
        stubModelFinds(new FieldResult(AIMS, List.of("What the new file says"), "", 0.8, List.of(), 0),
            new FieldResult(TITLE, List.of("A title"), "", 0.8, List.of(), 0));

        this.handler.execute(createTask());

        assertArrayEquals(new String[] { "What the person said" },
            answer.getValueMap().get(VALUE, String[].class));
        assertEquals(2, getAnswers().size(), "the title was a gap, and is filled");
    }

    // A reading workflow does not need a gate. If a schema only ever gets one kind of document, there is
    // nothing to decide, and the step reads whatever the parse produced.
    @Test
    void readsTheParsedDocumentWhenNoGateRan() throws Exception
    {
        this.variables.clear();
        stubModelFinds(new FieldResult(AIMS, List.of("To reduce readmissions"), "Stated under Aims.", 0.9,
            List.of(), 0));

        this.handler.execute(createTask());

        assertNull(this.submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class),
            "finishReading, not the intake, ends the reading");
        assertEquals(SubmissionTree.getIdentifier(this.aims),
            getAnswers().get(0).getValueMap().get("question", String.class));
        assertEquals("To reduce readmissions",
            getOnlyChild(getAnswers().get(0)).getValueMap().get("extractedValues", String.class));
    }

    /** A step that reads the named documents, in the given order. */
    private WorkflowTaskContext createTaskReading(final String... documents)
    {
        return TaskContexts.of(this.submission, Map.of(), this.variables,
            Map.of(IntakeAnswersHandler.REQUIREMENT_PARAMETER, "study", IntakeAnswersHandler.DOCUMENTS_PARAMETER,
                documents));
    }

    /** Returns the document text the model was sent. */
    private String getSentText() throws IOException
    {
        final ArgumentCaptor<DocumentScan> sent = ArgumentCaptor.forClass(DocumentScan.class);
        Mockito.verify(this.intake).askQuestions(Mockito.any(), sent.capture(), Mockito.anyList(), Mockito.any());
        return sent.getValue().getText();
    }

    // The model needs both documents: the preamble says what the questionnaire is for, and the questionnaire
    // holds the questions. They are sent as one text, in the order the step names them, each under its own
    // heading.
    @Test
    void sendsTheNamedDocumentsAsOneTextInTheOrderNamed() throws Exception
    {
        this.variables.clear();
        final Resource questionnaire = this.tree.createParsedFileFor(
            this.tree.createDocumentRequirement("questionnaire"), "1. How was your visit?");
        final Resource preamble = this.tree.createParsedFileFor(this.tree.createDocumentRequirement("preamble"),
            "This survey asks about your care.");
        stubModelFinds(new FieldResult(AIMS, List.of("Care"), "Stated in the preamble.", 0.9, List.of(
            new FieldResult.Passage("This survey asks about your care.", 1L, "", 0),
            new FieldResult.Passage("How was your visit?", 1L, "", 1)), 0));

        this.handler.execute(createTaskReading("preamble", "questionnaire"));

        assertEquals("# Preamble\n\nThis survey asks about your care.\n\n"
            + "# Questionnaire\n\n1. How was your visit?", getSentText());
        assertArrayEquals(new String[] { SubmissionTree.getIdentifier(preamble.getParent()),
            SubmissionTree.getIdentifier(questionnaire.getParent()) },
            getOnlyChild(getAnswers().get(0)).getValueMap().get("sources", String[].class),
            "read from both documents");
        // Each quote is saved with the document it came from, so its link opens that document
        final List<Resource> evidence = new ArrayList<>();
        getOnlyChild(getAnswers().get(0)).getChildren().forEach(evidence::add);
        assertEquals(SubmissionTree.getIdentifier(preamble.getParent()),
            evidence.get(0).getValueMap().get("source", String.class));
        assertEquals(SubmissionTree.getIdentifier(questionnaire.getParent()),
            evidence.get(1).getValueMap().get("source", String.class));
    }

    // The preamble is optional. A step that names it reads what was uploaded instead of refusing to read.
    @Test
    void readsTheNamedDocumentsThatWereUploaded() throws Exception
    {
        this.variables.clear();
        final Resource preamble = this.tree.createDocumentRequirement("preamble");
        // One document for the preamble with no upload yet, and one with no version at all
        SubmissionTree.reference(this.tree.createEmptyDocumentVersion().getParent(), "fulfills", preamble);
        SubmissionTree.reference(this.context.create().resource(SubmissionTree.SUBMISSION_PATH + "/bare",
            Map.of("sling:resourceType", "sub/Document")), "fulfills", preamble);
        this.tree.createParsedFileFor(this.tree.createDocumentRequirement("questionnaire"), "1. How was your visit?");
        stubModelFinds();

        this.handler.execute(createTaskReading("preamble", "questionnaire"));

        assertEquals("# Questionnaire\n\n1. How was your visit?", getSentText());
    }

    // One named file failed to parse. Reading the other and saying done would hide that: the view shows nothing
    // once a reading is done, so the person would never be offered a retry.
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
        assertEquals("failed", this.submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class));
        assertEquals("The document could not be read. Please try again",
            this.submission.getValueMap().get(ExtractionStatus.MESSAGE_PROPERTY, String.class));
    }

    @Test
    void failsWhenNoneOfTheNamedDocumentsWasRead() throws Exception
    {
        this.variables.clear();
        this.tree.createDocumentRequirement("questionnaire");

        this.handler.execute(createTaskReading("preamble", "questionnaire"));

        Mockito.verifyNoInteractions(this.intake);
        assertEquals("failed", this.submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class));
    }

    // An answer points at the document it was read from. If that document is gone, the answer is not written
    // at all, instead of pointing at nothing.
    @Test
    void refusesToRecordAnAnswerWhoseDocumentIsGone() throws Exception
    {
        final File gone = this.file.adaptTo(File.class);
        this.context.resourceResolver().delete(this.file);

        assertThrows(PersistenceException.class, () -> ExtractedAnswers.write(this.context.resourceResolver(),
            this.submission, null, this.aims.adaptTo(Question.class),
            new FieldResult(AIMS, List.of("Care"), null, 0.9, List.of(), 0), List.of(gone)));
    }

    @Test
    void failsWhenNothingHasBeenParsed() throws Exception
    {
        // Remove the one parsed document, so the submission has none
        this.context.resourceResolver().delete(this.file.getParent().getParent());

        this.handler.execute(createTask());

        Mockito.verifyNoInteractions(this.intake);
        assertEquals("failed", this.submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class));
        assertEquals(SubmissionFiles.NO_DOCUMENT_MESSAGE,
            this.submission.getValueMap().get(ExtractionStatus.MESSAGE_PROPERTY, String.class));
    }

    // Every step asks only its own questions, so a step that names none is a mistake in the workflow, not a
    // request to ask the whole schema
    @Test
    void failsWhenTheStepNamesNoRequirement() throws Exception
    {
        for (final Object none : new Object[] { null, " ", new String[] { "", " , " } }) {
            this.handler.execute(none == null ? TaskContexts.of(this.submission, Map.of(), this.variables)
                : TaskContexts.of(this.submission, Map.of(), this.variables,
                    Map.of(IntakeAnswersHandler.REQUIREMENT_PARAMETER, none)));

            assertEquals("failed", this.submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class));
            assertEquals(IntakeAnswersHandler.SET_UP_WRONG_MESSAGE,
                this.submission.getValueMap().get(ExtractionStatus.MESSAGE_PROPERTY, String.class));
        }
        Mockito.verifyNoInteractions(this.intake);
    }

    @Test
    void failsWhenTheModelsAnswerCouldNotBeRead() throws Exception
    {
        Mockito.when(this.intake.askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any()))
            .thenReturn(IntakeResult.createNotRead(IntakeResult.REPLY_UNREADABLE_MESSAGE));

        this.handler.execute(createTask());

        assertEquals("failed", this.submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class));
        assertTrue(getAnswers().isEmpty());
    }

    // Saved, not thrown: throwing would undo the answers earlier steps wrote and replace this reason with a
    // generic one
    @Test
    void recordsAnUnreachableModelAsAFailedReading() throws Exception
    {
        Mockito.when(this.intake.askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any()))
            .thenReturn(IntakeResult.createNotRead(IntakeResult.UNREACHABLE_MESSAGE));

        this.handler.execute(createTask());

        assertEquals("failed", this.submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class));
        assertEquals(IntakeResult.UNREACHABLE_MESSAGE,
            this.submission.getValueMap().get(ExtractionStatus.MESSAGE_PROPERTY, String.class));
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

        assertEquals(IntakeResult.TEXT_UNREADABLE_MESSAGE,
            this.submission.getValueMap().get(ExtractionStatus.MESSAGE_PROPERTY, String.class));
        Mockito.verifyNoInteractions(this.intake);
    }

    @Test
    void aStopAbortsTheStep() throws Exception
    {
        Mockito.when(this.intake.askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any()))
            .thenThrow(new ReadingStoppedException());

        final PersistenceException failure =
            assertThrows(PersistenceException.class, () -> this.handler.execute(createTask()));

        assertEquals(ExtractionStatus.STOPPED_MESSAGE, failure.getMessage());
    }

    // A stop that comes after the model answered must still undo the step, and must not stay set on the thread
    @Test
    void aStopAfterTheModelAnsweredAbortsTheStep() throws Exception
    {
        Mockito.when(this.intake.askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any()))
            .thenAnswer(invocation -> {
                new ReadingRuns().stop(this.submission.getPath());
                return IntakeResult.createRead(Map.of(AIMS,
                    new FieldResult(AIMS, List.of("Something"), "", 0.9, List.of(), 0)));
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

    // Some calls went through and others did not: what was read is kept, and the submitter is told the rest
    // can be read again
    @Test
    void keepsWhatWasReadAndSaysSoWhenSomeCallsFailed() throws Exception
    {
        Mockito.when(this.intake.askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any()))
            .thenReturn(IntakeResult.createIncomplete(Map.of(AIMS,
                new FieldResult(AIMS, List.of("Something"), "", 0.9, List.of(), 0))));

        this.handler.execute(createTask());

        assertEquals(1, getAnswers().size());
        assertNull(this.submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class),
            "finishReading, not the intake, ends the reading");
        assertEquals(IntakeResult.INCOMPLETE_MESSAGE,
            this.submission.getValueMap().get(ExtractionStatus.MESSAGE_PROPERTY, String.class));
    }

    @Test
    void leavesNoAnswerWhenItsQuestionIsGoneByTheTimeItIsWritten() throws Exception
    {
        stubModelFinds(new FieldResult(AIMS, List.of("Something"), "", 0.9, List.of(), 0));
        // The question is read into the field list first, then gone by the time the answer is written
        Mockito.when(this.intake.askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any()))
            .thenAnswer(invocation -> {
                this.context.resourceResolver().delete(this.aims);
                return IntakeResult.createRead(Map.of(AIMS,
                    new FieldResult(AIMS, List.of("Something"), "", 0.9, List.of(), 0)));
            });

        this.handler.execute(createTask());

        assertTrue(getAnswers().isEmpty(), "nothing half made is left for the commit to refuse");
    }

    // Knowledge that only one schema needs is named on the step, so it is not sent with every reading
    @Test
    void sendsTheReferenceTheStepNames() throws Exception
    {
        stubModelFinds();

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), this.variables,
            Map.of(IntakeAnswersHandler.REQUIREMENT_PARAMETER, "study", IntakeAnswersHandler.REFERENCES_PARAMETER,
                REFERENCE_PROMPT)));

        final ArgumentCaptor<String> references = ArgumentCaptor.forClass(String.class);
        Mockito.verify(this.intake).askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), references.capture());
        assertTrue(references.getValue().contains(REFERENCE_TEXT), references.getValue());
    }

    @Test
    void joinsEveryReferenceTheStepNamesInOrder()
    {
        final String references = IntakeAnswersHandler.getReferences(TaskContexts.of(this.submission, Map.of(),
            this.variables, Map.of(IntakeAnswersHandler.REFERENCES_PARAMETER,
                new String[] { REFERENCE_PROMPT, " ", Prompts.EXTRACTION_SYSTEM_FILE_NAME })));

        final String other = "You read the document submitted with a request";
        assertTrue(references.contains(REFERENCE_TEXT), references);
        assertTrue(references.contains(other), references);
        assertTrue(references.indexOf(REFERENCE_TEXT) < references.indexOf(other), references);
    }

    @Test
    void sendsNoReferenceWhenTheStepNamesNone()
    {
        assertNull(IntakeAnswersHandler.getReferences(createTask()));
        assertNull(IntakeAnswersHandler.getReferences(TaskContexts.of(this.submission, Map.of(), this.variables,
            Map.of(IntakeAnswersHandler.REFERENCES_PARAMETER, " "))));
    }
}

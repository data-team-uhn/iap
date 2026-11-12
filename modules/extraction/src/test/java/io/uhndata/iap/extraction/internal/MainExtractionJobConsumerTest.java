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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.consumer.JobConsumer.JobResult;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.extraction.internal.AnswerIntakeService.IntakeResult;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MainExtractionJobConsumer}: which forms it reads, from which documents, and how it saves
 * what the model read.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class MainExtractionJobConsumerTest
{
    private static final String JOB_ID = "job-1";

    private static final String WITHDRAWAL_PATH = SubmissionTree.VERSION_PATH + "/withdrawal";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final WorkflowEngine engine = Mockito.mock(WorkflowEngine.class);

    private final AnswerIntakeService intake = Mockito.mock(AnswerIntakeService.class);

    private final Job job = Mockito.mock(Job.class);

    private final MainExtractionJobConsumer consumer = new MainExtractionJobConsumer();

    private final FormReader reader = new FormReader();

    /** What the stand-in engine does for each event, by name. */
    private final Map<String, Answer<Object>> steps = new HashMap<>();

    private SubmissionTree tree;

    private Resource submission;

    private Resource consentFile;

    @BeforeEach
    void setUp() throws Exception
    {
        this.tree = new SubmissionTree(this.context);
        this.tree.createSchemaVersion();
        this.submission = this.tree.createSubmission();
        this.tree.createParsedFileFor(this.tree.createDocumentRequirement("proposal"),
            "A study of sleep in shift workers.");
        this.consentFile = this.tree.createParsedFileFor(this.tree.createDocumentRequirement("consent"),
            "You may withdraw at any time.");
        setDocuments(SubmissionTree.FORM_PATH, "proposal");
        this.tree.createQuestion("title", "The study's full title.");
        this.tree.createQuestion("sample", "How many participants it enrols.");
        this.context.create().resource(WITHDRAWAL_PATH, Map.of("sling:resourceType", FormRequirement.RESOURCE_TYPE,
            "sling:resourceSuperType", "sch/Requirement", "label", "Withdrawal",
            "documents", new String[] { "consent" }));
        this.context.create().resource(WITHDRAWAL_PATH + "/can_withdraw", Map.of("sling:resourceType", "sch/Question",
            "sling:resourceSuperType", "sch/FormItem", "text", "May a participant withdraw?", "dataType", "text",
            "maxAnswers", 1L, "extractionPrompt", "Whether a participant may withdraw at any time."));
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.STATUS_PROPERTY,
            ExtractionStatus.STATUS_RUNNING);
        final ReadingRuns runs = new ReadingRuns();
        final ReadingJobs jobs = new ReadingJobs();
        inject(jobs, "resolverFactory", new TestResolverFactory(this.context.resourceResolver()));
        inject(jobs, "engine", this.engine);
        inject(jobs, "runs", runs);
        inject(this.reader, "intake", this.intake);
        inject(this.reader, "documents", new ParsedDocuments());
        inject(this.reader, "runs", runs);
        inject(this.consumer, "jobs", jobs);
        inject(this.consumer, "engine", this.engine);
        inject(this.consumer, "reader", this.reader);
        Mockito.when(this.job.getId()).thenReturn(JOB_ID);
        Mockito.when(this.job.getProperty(ReadingJobs.SUBMISSION_PROPERTY, String.class))
            .thenReturn(this.submission.getPath());
        this.steps.put(ReadingJobs.READING_STARTED_EVENT, call -> runStep(new ClaimReadingHandler(), call));
        this.steps.put(ReadingJobs.READING_FAILED_EVENT, call -> runStep(new GiveUpReadingHandler(), call));
        this.steps.put(MainExtractionJobConsumer.EXTRACTED_EVENT,
            call -> runStep(new RecordExtractionHandler(), call));
        Mockito.when(this.engine.receiveEvent(Mockito.any(), Mockito.any())).thenAnswer(
            call -> this.steps.get(call.<WorkflowEvent>getArgument(1).getName()).answer(call));
        Mockito.when(this.intake.askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any()))
            .thenAnswer(MainExtractionJobConsumerTest::answer);
    }

    private static void inject(final Object component, final String name, final Object value) throws Exception
    {
        final Field field = component.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(component, value);
    }

    /** Runs a step as the engine would: through its own session, committed when the step succeeds. */
    private Object runStep(final ServiceTaskHandler handler, final InvocationOnMock call) throws Exception
    {
        final Resource target = SubmissionTree.withTypedCreates(
            this.context.resourceResolver().getResource(call.<Resource>getArgument(0).getPath()));
        handler.execute(TaskContexts.of(target, call.<WorkflowEvent>getArgument(1).getPayload(), new HashMap<>()));
        this.context.resourceResolver().commit();
        return null;
    }

    /** The stand-in model: every question asked is answered with its own key. */
    private static IntakeResult answer(final InvocationOnMock call)
    {
        final List<ExtractionPrompt.Group> groups = call.getArgument(2);
        final Map<String, FieldResult> fields = new LinkedHashMap<>();
        groups.get(0).fields().forEach(field -> fields.put(field.key(),
            new FieldResult(field.key(), List.of(field.key()), "It says so.", 0.9, List.of(), 0)));
        return IntakeResult.createRead(fields);
    }

    private void setDocuments(final String form, final String... documents)
    {
        this.context.resourceResolver().getResource(form).adaptTo(ModifiableValueMap.class)
            .put("documents", documents);
    }

    private void answerQuestion(final String question, final String value)
    {
        final Resource answer = this.context.create().resource(SubmissionTree.SUBMISSION_PATH + "/given",
            Map.of("jcr:primaryType", "sub:Answer", "sling:resourceType", "sub/Answer", "value",
                new String[] { value }));
        SubmissionTree.reference(answer, "question", this.context.resourceResolver().getResource(question));
    }

    private List<String> getAskedForms()
    {
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<List<ExtractionPrompt.Group>> groups = ArgumentCaptor.forClass(List.class);
        Mockito.verify(this.intake, Mockito.atLeast(0)).askQuestions(Mockito.any(), Mockito.any(), groups.capture(),
            Mockito.any());
        return groups.getAllValues().stream().map(asked -> asked.get(0).name()).toList();
    }

    private List<String> getAskedKeys()
    {
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<List<ExtractionPrompt.Group>> groups = ArgumentCaptor.forClass(List.class);
        Mockito.verify(this.intake, Mockito.atLeast(0)).askQuestions(Mockito.any(), Mockito.any(), groups.capture(),
            Mockito.any());
        return groups.getAllValues().stream()
            .flatMap(asked -> asked.get(0).fields().stream())
            .map(ExtractionField::key)
            .toList();
    }

    private List<String> getFiredEventNames() throws Exception
    {
        final ArgumentCaptor<WorkflowEvent> events = ArgumentCaptor.forClass(WorkflowEvent.class);
        Mockito.verify(this.engine, Mockito.atLeast(0)).receiveEvent(Mockito.any(), events.capture());
        return events.getAllValues().stream().map(WorkflowEvent::getName).toList();
    }

    /** The values of the newest run on the question's answer, or {@code null} when it has none. */
    private List<String> getSuggestion(final String question)
    {
        return SubmissionFiles.getSubmission(getSubmissionResource()).getAnswers().stream()
            .filter(answer -> answer.getQuestion() != null && question.equals(answer.getQuestion().getPath())
                && answer.getLatestExtraction() != null)
            .map(answer -> answer.getLatestExtraction().getExtractedValues())
            .findFirst()
            .orElse(null);
    }

    private Resource getSubmissionResource()
    {
        return this.context.resourceResolver().getResource(this.submission.getPath());
    }

    private ValueMap getProperties()
    {
        return getSubmissionResource().getValueMap();
    }

    @Test
    void readsEachFormFromTheDocumentsItNames() throws Exception
    {
        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("study", "withdrawal"), getAskedForms());
        final ArgumentCaptor<File> files = ArgumentCaptor.forClass(File.class);
        Mockito.verify(this.intake, Mockito.times(2)).askQuestions(files.capture(), Mockito.any(),
            Mockito.anyList(), Mockito.any());
        assertEquals(this.consentFile.getPath(), files.getAllValues().get(1).getPath());
        assertEquals(List.of("readingStarted", "extracted"), getFiredEventNames());
        assertEquals(List.of("study.title"), getSuggestion(SubmissionTree.FORM_PATH + "/title"));
        assertEquals(List.of("withdrawal.can_withdraw"), getSuggestion(WITHDRAWAL_PATH + "/can_withdraw"));
        assertEquals(ExtractionStatus.STATUS_DONE, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertNull(getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
        assertNull(getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY));
    }

    @Test
    void joinsSeveralDocumentsUnderTheirLabels()
    {
        setDocuments(SubmissionTree.FORM_PATH, "proposal", "consent");

        this.consumer.process(this.job);

        final ArgumentCaptor<DocumentScan> scans = ArgumentCaptor.forClass(DocumentScan.class);
        Mockito.verify(this.intake, Mockito.times(2)).askQuestions(Mockito.any(), scans.capture(),
            Mockito.anyList(), Mockito.any());
        final String text = scans.getAllValues().get(0).getText();
        assertTrue(text.contains("# proposal") && text.contains("# consent"), text);
    }

    @Test
    void showsTheModelTheFormsTemplate()
    {
        this.tree.addTemplate("study", "The sample size is usually in Methods.");

        this.consumer.process(this.job);

        final ArgumentCaptor<String> references = ArgumentCaptor.forClass(String.class);
        Mockito.verify(this.intake, Mockito.times(2)).askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(),
            references.capture());
        assertTrue(references.getAllValues().get(0).contains("The sample size is usually in Methods."));
        assertNull(references.getAllValues().get(1));
    }

    // Its questions stay for the submitter
    @Test
    void leavesOutAFormThatNamesNoDocuments()
    {
        this.context.resourceResolver().getResource(WITHDRAWAL_PATH).adaptTo(ModifiableValueMap.class)
            .remove("documents");

        this.consumer.process(this.job);

        assertEquals(List.of("study"), getAskedForms());
    }

    @Test
    void leavesOutAFormThatDoesNotApply()
    {
        final ConditionEvaluator conditions = Mockito.mock(ConditionEvaluator.class);
        Mockito.when(conditions.applies(Mockito.any(), Mockito.any())).thenReturn(true);
        Mockito.when(conditions.applies(Mockito.argThat(item -> item instanceof final FormRequirement form
            && WITHDRAWAL_PATH.equals(form.getPath())), Mockito.any())).thenReturn(false);
        this.context.registerService(ConditionEvaluator.class, conditions);

        this.consumer.process(this.job);

        assertEquals(List.of("study"), getAskedForms());
    }

    @Test
    void asksOnlyTheQuestionsNobodyAnswered()
    {
        answerQuestion(SubmissionTree.FORM_PATH + "/title", "Sleep in shift workers");

        this.consumer.process(this.job);

        assertEquals(List.of("study.sample", "withdrawal.can_withdraw"), getAskedKeys());
    }

    // A question read once is not asked again, found or not
    @Test
    void asksNothingAlreadyReadAndStillEndsTheReading() throws Exception
    {
        this.consumer.process(this.job);
        getSubmissionResource().adaptTo(ModifiableValueMap.class).put(ExtractionStatus.STATUS_PROPERTY,
            ExtractionStatus.STATUS_RUNNING);
        this.context.resourceResolver().commit();
        Mockito.clearInvocations(this.intake, this.engine);

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        Mockito.verifyNoInteractions(this.intake);
        assertEquals(List.of("readingStarted", "extracted"), getFiredEventNames());
        assertEquals(ExtractionStatus.STATUS_DONE, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
    }

    // The categorisation fills it, and the submitter confirmed it
    @Test
    void leavesTheCategoryQuestionOut()
    {
        final Resource category = this.tree.createClassificationQuestion("study_kind", "proposal",
            "What kind of study?", "interventional", "observational");
        category.adaptTo(ModifiableValueMap.class).put("extractionPrompt", "The kind of study.");
        setDocuments(SubmissionTree.VERSION_PATH + "/classification", "proposal");

        this.consumer.process(this.job);

        assertFalse(getAskedKeys().contains("classification.study_kind"));
    }

    @Test
    void givesUpWhenNothingCouldBeRead() throws Exception
    {
        Mockito.doReturn(IntakeResult.createNotRead(IntakeResult.UNREACHABLE_MESSAGE)).when(this.intake)
            .askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any());

        this.consumer.process(this.job);

        assertEquals(List.of("readingStarted", "readingFailed"), getFiredEventNames());
        assertEquals(ExtractionStatus.STATUS_FAILED, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertEquals(IntakeResult.UNREACHABLE_MESSAGE, getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    // The model is likely down for the forms after it too, and Read again asks what was not read
    @Test
    void savesWhatWasReadWhenALaterCallFails() throws Exception
    {
        Mockito.doAnswer(MainExtractionJobConsumerTest::answer)
            .doReturn(IntakeResult.createNotRead(IntakeResult.UNREACHABLE_MESSAGE))
            .when(this.intake).askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any());
        setDocuments(WITHDRAWAL_PATH, "consent");
        this.context.create().resource(SubmissionTree.VERSION_PATH + "/later", Map.of(
            "sling:resourceType", FormRequirement.RESOURCE_TYPE, "sling:resourceSuperType", "sch/Requirement",
            "label", "Later", "documents", new String[] { "proposal" }));
        this.context.create().resource(SubmissionTree.VERSION_PATH + "/later/q", Map.of(
            "sling:resourceType", "sch/Question", "sling:resourceSuperType", "sch/FormItem", "text", "Q?",
            "dataType", "text", "maxAnswers", 1L, "extractionPrompt", "Something."));

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("study", "withdrawal"), getAskedForms());
        assertEquals(List.of("study.title"), getSuggestion(SubmissionTree.FORM_PATH + "/title"));
        assertNull(getSuggestion(WITHDRAWAL_PATH + "/can_withdraw"));
        assertEquals(ExtractionStatus.STATUS_DONE, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertEquals(IntakeResult.INCOMPLETE_MESSAGE, getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    @Test
    void endsWithANoteWhenSomeQuestionsCouldNotBeRead()
    {
        Mockito.doReturn(IntakeResult.createIncomplete(Map.of())).when(this.intake)
            .askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any());

        this.consumer.process(this.job);

        assertEquals(IntakeResult.INCOMPLETE_MESSAGE, getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    @Test
    void doesNotStartWhenADocumentItNamesFailedToParse()
    {
        this.consentFile.adaptTo(ModifiableValueMap.class).put(ParsePropertyNames.PARSE_STATUS_PROPERTY,
            ParsePropertyNames.STATUS_FAILED);

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals("The consent is missing, please upload it",
            getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
        Mockito.verifyNoInteractions(this.intake);
    }

    // The submission view should not have let the submitter on without it
    @Test
    void doesNotStartWhenARequiredDocumentWasNeverUploaded()
    {
        this.tree.createDocumentRequirement("ethics");
        setDocuments(WITHDRAWAL_PATH, "consent", "ethics");

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals("The ethics is missing, please upload it",
            getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    @Test
    void readsWithoutAnOptionalDocumentLeftOut()
    {
        this.tree.createDocumentRequirement("ethics").adaptTo(ModifiableValueMap.class).put("required", false);
        setDocuments(WITHDRAWAL_PATH, "ethics");

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("study"), getAskedForms());
    }

    @Test
    void readsWithoutADocumentTheSchemaDoesNotKnow()
    {
        setDocuments(WITHDRAWAL_PATH, "budget");

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("study"), getAskedForms());
    }

    @Test
    void givesUpWhenTheTextCannotBeRead() throws Exception
    {
        final ParsedDocuments documents = Mockito.mock(ParsedDocuments.class);
        Mockito.when(documents.scan(Mockito.any())).thenThrow(new IOException("The repository is down"));
        inject(this.reader, "documents", documents);

        this.consumer.process(this.job);

        assertEquals(IntakeResult.TEXT_UNREADABLE_MESSAGE, getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
        Mockito.verifyNoInteractions(this.intake);
    }

    // A Stop or a Read again took the reading while the model was answering
    @Test
    void dropsTheAnswersWhenTheReadingIsNoLongerItsOwn() throws Exception
    {
        this.steps.put(MainExtractionJobConsumer.EXTRACTED_EVENT, call -> {
            throw new InvalidStateException("The reading is no longer this job's");
        });

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertNull(getSuggestion(SubmissionTree.FORM_PATH + "/title"));
    }
}

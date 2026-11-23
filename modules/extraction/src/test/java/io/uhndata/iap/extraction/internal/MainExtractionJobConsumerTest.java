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

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.extraction.internal.AnswerIntakeService.IntakeResult;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.DocumentVersion;
import io.uhndata.iap.submissions.models.File;

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
    private static final String WITHDRAWAL_PATH = SubmissionTree.VERSION_PATH + "/withdrawal";

    /** A failure of one form alone, which reading again would not fix. */
    private static final String NO_ROOM_MESSAGE = "The questions leave no room to show the model the document";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final MainExtractionJobConsumer consumer = new MainExtractionJobConsumer();

    private ReadingHarness harness;

    private AnswerIntakeService intake;

    private Job job;

    private SubmissionTree tree;

    private Resource submission;

    private Resource proposalFile;

    private Resource consentFile;

    @BeforeEach
    void setUp()
    {
        this.tree = new SubmissionTree(this.context);
        this.tree.createSchemaVersion();
        this.submission = this.tree.createSubmission();
        this.proposalFile = this.tree.createParsedFileFor(this.tree.createDocumentRequirement("proposal"),
            "A study of sleep in shift workers.");
        this.consentFile = this.tree.createParsedFileFor(this.tree.createDocumentRequirement("consent"),
            "You may withdraw at any time.");
        this.tree.setFormDocuments(SubmissionTree.FORM_PATH, "proposal");
        this.tree.createQuestion("title", "The study's full title.");
        this.tree.createQuestion("sample", "How many participants it enrols.");
        this.tree.createQuestion(this.tree.createForm("withdrawal", "Withdrawal", "consent"), "can_withdraw",
            "Whether a participant may withdraw at any time.");
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.STATUS_PROPERTY,
            ExtractionStatus.STATUS_RUNNING);
        this.harness = new ReadingHarness(this.context);
        this.intake = this.harness.getIntake();
        this.job = ReadingHarness.createJob(this.submission.getPath());
        TestComponents.inject(this.consumer, "jobs", this.harness.getJobs());
        TestComponents.inject(this.consumer, "reader", this.harness.getReader());
        TestComponents.inject(this.consumer, "formsToRead", new FormsToRead());
        this.harness.putStep(MainExtractionJobConsumer.EXTRACTED_EVENT, new EndReadingHandler());
        Mockito.when(this.intake.askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any()))
            .thenAnswer(MainExtractionJobConsumerTest::answer);
    }

    /** The stand-in model: every question asked is answered with its own key. */
    private static IntakeResult answer(final InvocationOnMock call)
    {
        final List<ExtractionPrompt.Group> groups = call.getArgument(2);
        final Map<String, FieldResult> fields = new LinkedHashMap<>();
        groups.get(0).fields().forEach(field -> fields.put(field.key(),
            new FieldResult(field.key(), List.of(field.key()), "It says so.", SubmissionTree.SURE, SubmissionTree.SURE,
                List.of(), 0)));
        return IntakeResult.createRead(fields);
    }

    private void answerQuestion(final String question, final String value)
    {
        this.tree.createAnswer(this.context.resourceResolver().getResource(question), value);
    }

    private List<String> getAskedForms()
    {
        return this.harness.getAskedGroups().stream().map(asked -> asked.get(0).name()).toList();
    }

    private List<String> getAskedKeys()
    {
        return this.harness.getAskedGroups().stream()
            .flatMap(asked -> asked.get(0).fields().stream())
            .map(ExtractionField::key)
            .toList();
    }

    private List<String> getFiredEventNames() throws Exception
    {
        return this.harness.getFiredEventNames();
    }

    /** The values of the newest run on the question's answer, or {@code null} when it has none. */
    private List<String> getSuggestion(final String question)
    {
        final Answer answer = this.tree.findAnswer(question);
        return answer == null || answer.getLatestExtraction() == null ? null
            : answer.getLatestExtraction().getExtractedValues();
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
        assertEquals(this.proposalFile.getPath(), files.getAllValues().get(0).getPath());
        assertEquals(this.consentFile.getPath(), files.getAllValues().get(1).getPath());
    }

    @Test
    void savesWhatTheModelReadAsSuggestions() throws Exception
    {
        this.consumer.process(this.job);

        assertEquals(List.of("study.title"), getSuggestion(SubmissionTree.FORM_PATH + "/title"));
        assertEquals(List.of("withdrawal.can_withdraw"), getSuggestion(WITHDRAWAL_PATH + "/can_withdraw"));
    }

    @Test
    void endsTheReadingWhenEveryFormWasRead() throws Exception
    {
        this.consumer.process(this.job);

        assertEquals(List.of("readingStarted", "extracted"), getFiredEventNames());
        assertEquals(ExtractionStatus.STATUS_DONE, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertNull(getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
        assertNull(getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY));
    }

    @Test
    void joinsSeveralDocumentsUnderTheirLabels()
    {
        this.tree.setFormDocuments(SubmissionTree.FORM_PATH, "proposal", "consent");

        this.consumer.process(this.job);

        final ArgumentCaptor<DocumentScan> scans = ArgumentCaptor.forClass(DocumentScan.class);
        Mockito.verify(this.intake, Mockito.times(2)).askQuestions(Mockito.any(), scans.capture(),
            Mockito.anyList(), Mockito.any());
        final String text = scans.getAllValues().get(0).getText();
        assertTrue(text.contains("# proposal") && text.contains("# consent"), text);
        // A new upload of either document makes the question worth reading again
        assertEquals(List.of(this.proposalFile.getParent().getPath(), this.consentFile.getParent().getPath()),
            this.tree.findAnswer(SubmissionTree.FORM_PATH + "/title").getLatestExtraction().getSources().stream()
                .map(DocumentVersion::getPath).toList());
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
        this.tree.setFormDocuments(WITHDRAWAL_PATH);

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

    // Autosave keeps an empty value for a field the submitter only clicked past
    @Test
    void asksAQuestionWhoseAnswerIsBlank()
    {
        answerQuestion(SubmissionTree.FORM_PATH + "/title", " ");

        this.consumer.process(this.job);

        assertTrue(getAskedKeys().contains("study.title"));
    }

    // A question read once is not asked again, found or not
    @Test
    void asksNothingAlreadyReadAndStillEndsTheReading() throws Exception
    {
        this.consumer.process(this.job);
        this.harness.restartReading();

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
        this.tree.setFormDocuments(SubmissionTree.VERSION_PATH + "/classification", "proposal");

        this.consumer.process(this.job);

        assertFalse(getAskedKeys().contains("classification.study_kind"));
    }

    @Test
    void givesUpWhenNothingCouldBeRead() throws Exception
    {
        Mockito.doReturn(IntakeResult.createModelFailure(IntakeResult.UNREACHABLE_MESSAGE)).when(this.intake)
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
            .doReturn(IntakeResult.createModelFailure(IntakeResult.UNREACHABLE_MESSAGE))
            .when(this.intake).askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any());
        this.tree.createQuestion(this.tree.createForm("later", "Later", "proposal"), "q", "Something.");

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("study", "withdrawal"), getAskedForms());
        assertEquals(List.of("study.title"), getSuggestion(SubmissionTree.FORM_PATH + "/title"));
        assertNull(getSuggestion(WITHDRAWAL_PATH + "/can_withdraw"));
        assertEquals(ExtractionStatus.STATUS_DONE, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertEquals(IntakeResult.INCOMPLETE_MESSAGE, getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    // A model that cannot answer in the required shape would fail every form after it the same way
    @Test
    void stopsAskingWhenNoReplyOfAFormCouldBeRead() throws Exception
    {
        Mockito.doReturn(IntakeResult.createModelFailure(IntakeResult.REPLY_UNREADABLE_MESSAGE))
            .when(this.intake).askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any());

        this.consumer.process(this.job);

        assertEquals(List.of("study"), getAskedForms());
        assertEquals(IntakeResult.REPLY_UNREADABLE_MESSAGE, getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    // The outage began partway through the form; the next form would wait out the timeout for nothing
    @Test
    void stopsAskingWhenTheModelWentDownPartwayThroughAForm() throws Exception
    {
        Mockito.doAnswer(call -> IntakeResult.createCutShort(answer(call).fields()))
            .when(this.intake).askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any());

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("study"), getAskedForms());
        assertEquals(List.of("study.title"), getSuggestion(SubmissionTree.FORM_PATH + "/title"));
        assertEquals(IntakeResult.INCOMPLETE_MESSAGE, getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    // Some calls failed for their own reply, not the model: the forms after it are still read
    @Test
    void readsTheFormsAfterOneReadOnlyInPart() throws Exception
    {
        Mockito.doAnswer(call -> IntakeResult.createIncomplete(answer(call).fields()))
            .doAnswer(MainExtractionJobConsumerTest::answer)
            .when(this.intake).askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any());

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("study", "withdrawal"), getAskedForms());
        assertEquals(IntakeResult.INCOMPLETE_MESSAGE, getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    // Read again would fail the same way, so the forms after it are still read now
    @Test
    void readsTheFormsAfterOneThatFailsForItsOwnReason() throws Exception
    {
        Mockito.doReturn(IntakeResult.createNotRead(NO_ROOM_MESSAGE))
            .doAnswer(MainExtractionJobConsumerTest::answer)
            .when(this.intake).askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any());

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("study", "withdrawal"), getAskedForms());
        assertNull(getSuggestion(SubmissionTree.FORM_PATH + "/title"));
        assertEquals(List.of("withdrawal.can_withdraw"), getSuggestion(WITHDRAWAL_PATH + "/can_withdraw"));
        assertEquals(ExtractionStatus.STATUS_DONE, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertEquals(NO_ROOM_MESSAGE, getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    @Test
    void givesUpWithTheFirstReasonWhenNoFormCouldBeRead() throws Exception
    {
        Mockito.doReturn(IntakeResult.createNotRead(NO_ROOM_MESSAGE))
            .doReturn(IntakeResult.createNotRead(IntakeResult.REPLY_UNREADABLE_MESSAGE))
            .when(this.intake).askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any());

        this.consumer.process(this.job);

        assertEquals(List.of("study", "withdrawal"), getAskedForms());
        assertEquals(ExtractionStatus.STATUS_FAILED, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertEquals(NO_ROOM_MESSAGE, getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
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

        assertEquals("The consent could not be read, please try again",
            getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
        Mockito.verifyNoInteractions(this.intake);
    }

    // The submission view should not have let the submitter on without it
    @Test
    void doesNotStartWhenARequiredDocumentWasNeverUploaded()
    {
        this.tree.createDocumentRequirement("ethics");
        this.tree.setFormDocuments(WITHDRAWAL_PATH, "consent", "ethics");

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals("The ethics is missing, please upload it",
            getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    @Test
    void readsWithoutAnOptionalDocumentLeftOut()
    {
        this.tree.createDocumentRequirement("ethics").adaptTo(ModifiableValueMap.class).put("required", false);
        this.tree.setFormDocuments(WITHDRAWAL_PATH, "ethics");

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("study"), getAskedForms());
    }

    @Test
    void readsWithoutADocumentTheSchemaDoesNotKnow()
    {
        this.tree.setFormDocuments(WITHDRAWAL_PATH, "budget");

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("study"), getAskedForms());
    }

    @Test
    void givesUpWhenTheTextCannotBeRead() throws Exception
    {
        final ParsedDocuments documents = Mockito.mock(ParsedDocuments.class);
        Mockito.when(documents.scan(Mockito.any())).thenThrow(new IOException("The repository is down"));
        TestComponents.inject(this.harness.getReader(), "documents", documents);

        this.consumer.process(this.job);

        assertEquals(IntakeResult.TEXT_UNREADABLE_MESSAGE, getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
        Mockito.verifyNoInteractions(this.intake);
    }

    // A Read again took the reading while the model was answering
    @Test
    void dropsTheAnswersWhenTheReadingIsNoLongerItsOwn() throws Exception
    {
        Mockito.doAnswer(call -> {
            getSubmissionResource().adaptTo(ModifiableValueMap.class).put(
                ExtractionStatus.READING_CLAIMED_BY_PROPERTY, ReadingHarness.OTHER_JOB_ID);
            this.context.resourceResolver().commit();
            return answer(call);
        }).when(this.intake).askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any());

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("readingStarted", "extracted"), getFiredEventNames());
        assertNull(getSuggestion(SubmissionTree.FORM_PATH + "/title"));
        assertEquals(ExtractionStatus.STATUS_RUNNING, getProperties().get(ExtractionStatus.STATUS_PROPERTY),
            "the reading that took over ends it");
        assertEquals(ReadingHarness.OTHER_JOB_ID, getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY));
    }
}

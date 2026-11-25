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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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
import io.uhndata.iap.schemas.models.ClassificationRequirement;
import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.ValidationRequirement;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.models.Validation;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowFailedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DecisionJobConsumer}: what it asks the model, how it saves the verdict, and how it hands
 * the submission on to the intake.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class DecisionJobConsumerTest
{
    private static final String PROMPT = "Decide whether the document is a research proposal.";

    private static final String STARTED = ReadingJobs.READING_STARTED_EVENT;

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final DecisionJobConsumer consumer = new DecisionJobConsumer();

    private ReadingHarness harness;

    private AnswerIntakeService intake;

    private Job job;

    private SubmissionTree tree;

    private String verdict = "yes";

    private double verdictConfidence = SubmissionTree.SURE;

    private String pick = "observational";

    private double pickConfidence = SubmissionTree.SURE;

    private Resource submission;

    private Resource document;

    @BeforeEach
    void setUp() throws Exception
    {
        this.tree = new SubmissionTree(this.context);
        this.tree.createSchemaVersion();
        this.submission = this.tree.createSubmission();
        this.document = this.tree.createDocumentRequirement("proposal");
        this.tree.createValidationRequirement("is_proposal", "proposal", PROMPT);
        this.tree.createParsedFileFor(this.document, "A study of sleep in shift workers.");
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.STATUS_PROPERTY,
            ExtractionStatus.STATUS_RUNNING);
        this.harness = new ReadingHarness(this.context);
        this.intake = this.harness.getIntake();
        this.job = ReadingHarness.createJob(this.submission.getPath());
        final DocumentValidator validator = new DocumentValidator();
        TestComponents.inject(validator, "jobs", this.harness.getJobs());
        TestComponents.inject(validator, "engine", this.harness.getEngine());
        TestComponents.inject(validator, "reader", this.harness.getReader());
        final DecisionChain chain = new DecisionChain();
        TestComponents.inject(validator, "chain", chain);
        final DocumentCategoriser categoriser = new DocumentCategoriser();
        TestComponents.inject(categoriser, "jobs", this.harness.getJobs());
        TestComponents.inject(categoriser, "reader", this.harness.getReader());
        TestComponents.inject(this.consumer, "jobs", this.harness.getJobs());
        TestComponents.inject(this.consumer, "validator", validator);
        TestComponents.inject(this.consumer, "categoriser", categoriser);
        TestComponents.inject(this.consumer, "chain", chain);
        this.harness.putStep(DocumentValidator.VALIDATED_EVENT, new RecordValidationHandler());
        this.harness.putStep(DocumentValidator.COMPLETE_EVENT, this.harness::closeTask);
        this.harness.putStep(DocumentCategoriser.CATEGORISED_EVENT, new RecordCategoryHandler());
        this.harness.putStep(DecisionJobConsumer.DECIDED_EVENT, new EndReadingHandler());
        Mockito.when(this.intake.askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any()))
            .thenAnswer(this::answer);
    }

    private void deleteProposalValidation() throws Exception
    {
        this.context.resourceResolver().delete(getProposalValidation());
    }

    private Resource getProposalValidation()
    {
        return this.context.resourceResolver().getResource(SubmissionTree.VERSION_PATH + "/is_proposal");
    }

    /** Adds a consent form the schema asks for after the proposal, and its validation. */
    private Resource createConsentValidation()
    {
        final Resource consent = this.tree.createDocumentRequirement("consent");
        this.tree.createValidationRequirement("is_consent", "consent", "Decide whether it is a consent form.");
        return consent;
    }

    private void createUploadedConsent()
    {
        this.tree.createParsedFileFor(createConsentValidation(), "I agree to take part.");
    }

    /** The consent is optional, so leaving it out is allowed. */
    private void createOptionalConsentLeftOut()
    {
        createConsentValidation().adaptTo(ModifiableValueMap.class).put("required", false);
    }

    private Resource createRequiredConsentLeftOut()
    {
        return createConsentValidation();
    }

    /** Makes the intake refuse to take the verdict, as a broken workflow would. */
    private void failTheHandOver()
    {
        this.harness.putStep(DocumentValidator.COMPLETE_EVENT, call -> {
            throw new WorkflowFailedException("The intake broke");
        });
    }

    /**
     * Runs the job once with the hand-over failing, so the verdict is saved and its task stays open, then starts
     * a new reading with the hand-over working again.
     */
    private void processOnceWithALostHandOver() throws Exception
    {
        failTheHandOver();
        this.consumer.process(this.job);
        assertEquals(ExtractionStatus.STATUS_FAILED, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        this.harness.putStep(DocumentValidator.COMPLETE_EVENT, this.harness::closeTask);
        this.harness.restartReading();
    }

    private List<WorkflowEvent> getVerdicts() throws Exception
    {
        return getFiredEvents().stream()
            .filter(event -> DocumentValidator.VALIDATED_EVENT.equals(event.getName()))
            .toList();
    }

    private static String getRequirementName(final WorkflowEvent event)
    {
        final String path = (String) event.get(ReadingEvents.REQUIREMENT_KEY);
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** The stand-in model: the verdict for a validation, the pick for a categorisation. */
    private IntakeResult answer(final InvocationOnMock call)
    {
        final List<ExtractionPrompt.Group> groups = call.getArgument(2);
        final String key = groups.get(0).fields().get(0).key();
        final boolean validation = DocumentValidator.VERDICT_KEY.equals(key);
        final double confidence = validation ? this.verdictConfidence : this.pickConfidence;
        return IntakeResult.createRead(Map.of(key, new FieldResult(key,
            List.of(validation ? this.verdict : this.pick), "It says so.", confidence, confidence, List.of(), 0)));
    }

    private void stubVerdict(final String answer, final double confidence)
    {
        this.verdict = answer;
        this.verdictConfidence = confidence;
    }

    private void stubPick(final String answer, final double confidence)
    {
        this.pick = answer;
        this.pickConfidence = confidence;
    }

    /** Adds the classification of the proposal: what kind of study it is. */
    private Resource createStudyKind()
    {
        return this.tree.createClassificationQuestion("study_kind", "proposal", "Decide what kind of study it is.",
            "interventional", "observational");
    }

    /** The values of the newest run on the question's answer, or {@code null} when it has no answer. */
    private List<String> getSuggestion(final Resource question)
    {
        final Answer answer = this.tree.findAnswer(question.getPath());
        return answer == null ? null : answer.getLatestExtraction().getExtractedValues();
    }

    private List<String> getAskedKeys()
    {
        return this.harness.getAskedGroups().stream().map(asked -> asked.get(0).fields().get(0).key()).toList();
    }

    private Resource createWaitingTask()
    {
        return this.tree.createIntakeTask("is_proposal", "created", IntakeTasks.READER_USER);
    }

    private List<WorkflowEvent> getFiredEvents() throws Exception
    {
        return this.harness.getFiredEvents();
    }

    private List<String> getFiredEventNames() throws Exception
    {
        return this.harness.getFiredEventNames();
    }

    private ValueMap getProperties()
    {
        return this.context.resourceResolver().getResource(this.submission.getPath()).getValueMap();
    }

    @Test
    void savesTheVerdictThenCompletesTheTaskTheIntakeWaitsAt() throws Exception
    {
        final Resource task = createWaitingTask();

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of(STARTED, "validated", "complete", "decided"), getFiredEventNames());
        assertEquals(Validation.OUTCOME_YES, getValidationModel().getOutcome());
        assertEquals(SubmissionTree.SUBMISSION_PATH + "/d1/v1",
            getValidationModel().getLatestExtraction().getSources().get(0).getPath());
        assertEquals("completed", task.getValueMap().get("status", String.class));
        // Nothing was categorised, so there is nothing to confirm
        assertEquals(ExtractionStatus.STATUS_DONE, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertNull(getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
        assertNull(getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY));
    }

    @Test
    void asksEachValidationInTheOrderOfItsDocument() throws Exception
    {
        // The validations are listed consent first; the documents, proposal first
        deleteProposalValidation();
        createUploadedConsent();
        this.tree.createValidationRequirement("is_proposal", "proposal", PROMPT);
        createWaitingTask();
        this.tree.createIntakeTask("is_consent", "created", IntakeTasks.READER_USER);

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of(STARTED, "validated", "complete", "validated", "complete", "decided"),
            getFiredEventNames());
        assertEquals(List.of("is_proposal", "is_consent"),
            getVerdicts().stream().map(DecisionJobConsumerTest::getRequirementName).toList());
    }

    @Test
    void asksNothingAfterANo() throws Exception
    {
        createUploadedConsent();
        createWaitingTask();
        stubVerdict("no", SubmissionTree.SURE);

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of(STARTED, "validated", "complete", "decided"), getFiredEventNames());
        Mockito.verify(this.intake, Mockito.times(1)).askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(),
            Mockito.any());
        assertEquals(ExtractionStatus.STATUS_DONE, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertEquals("Unable to identify the proposal", getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    // The model must not guess: a no it is not sure of does not reject the request
    @Test
    void carriesOnAfterANoItIsNotSureOf() throws Exception
    {
        createUploadedConsent();
        createWaitingTask();
        this.tree.createIntakeTask("is_consent", "created", IntakeTasks.READER_USER);
        Mockito.doAnswer(call -> {
            final IntakeResult result = answer(call);
            stubVerdict("yes", SubmissionTree.SURE);
            return result;
        }).when(this.intake).askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any());
        stubVerdict("no", SubmissionTree.UNSURE);

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("is_proposal", "is_consent"),
            getVerdicts().stream().map(DecisionJobConsumerTest::getRequirementName).toList());
        assertEquals(ExtractionStatus.STATUS_DONE, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertNull(getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    // A document too long to read is answered by hand, not a reason to stop: the checks after it are still asked
    @Test
    void carriesOnAfterADocumentTooLongToRead() throws Exception
    {
        final Resource category = createStudyKind();
        createWaitingTask();
        Mockito.doAnswer(call -> isAskingForAVerdict(call) ? IntakeResult.createTooLong() : answer(call))
            .when(this.intake).askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any());

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("verdict", "classification.study_kind"), getAskedKeys());
        assertEquals(Validation.OUTCOME_UNSURE, getValidationModel().getOutcome());
        assertEquals(List.of("observational"), getSuggestion(category));
    }

    private static boolean isAskingForAVerdict(final InvocationOnMock call)
    {
        final List<ExtractionPrompt.Group> groups = call.getArgument(2);
        return DocumentValidator.VERDICT_KEY.equals(groups.get(0).fields().get(0).key());
    }

    // The no stays a pending hand-over, so Read again carries the intake on instead of waiting at its task forever
    @Test
    void handsOverANoWhoseHandOverWasLost() throws Exception
    {
        final Resource task = createWaitingTask();
        stubVerdict("no", SubmissionTree.SURE);
        processOnceWithALostHandOver();

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of(STARTED, "complete", "decided"), getFiredEventNames());
        Mockito.verifyNoInteractions(this.intake);
        assertEquals("completed", task.getValueMap().get("status", String.class));
        assertEquals("Unable to identify the proposal", getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    @Test
    void skipsAValidationWhoseDocumentWasNotUploaded() throws Exception
    {
        createOptionalConsentLeftOut();
        createWaitingTask();

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("is_proposal"),
            getVerdicts().stream().map(DecisionJobConsumerTest::getRequirementName).toList());
    }

    @Test
    void asksNothingMoreAfterAStop() throws Exception
    {
        createUploadedConsent();
        createWaitingTask();
        this.harness.putStep(DocumentValidator.COMPLETE_EVENT, call -> {
            this.harness.closeTask(call);
            getSubmissionResource().adaptTo(ModifiableValueMap.class).put(ExtractionStatus.STATUS_PROPERTY,
                ExtractionStatus.STATUS_FAILED);
            this.context.resourceResolver().commit();
            return null;
        });

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("is_proposal"),
            getVerdicts().stream().map(DecisionJobConsumerTest::getRequirementName).toList());
    }

    @Test
    void asksNothingMoreWhenTheSubmissionWentAway() throws Exception
    {
        createUploadedConsent();
        createWaitingTask();
        this.harness.putStep(DocumentValidator.COMPLETE_EVENT, call -> {
            this.context.resourceResolver().delete(getSubmissionResource());
            this.context.resourceResolver().commit();
            return null;
        });

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(1, getVerdicts().size());
    }

    // The intake ended on the no, so a later reading asks nothing after it and says why again
    @Test
    void asksNothingInALaterReadingAfterANo() throws Exception
    {
        createUploadedConsent();
        createWaitingTask();
        stubVerdict("no", SubmissionTree.SURE);
        this.consumer.process(this.job);
        this.harness.restartReading();

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        Mockito.verifyNoInteractions(this.intake);
        assertEquals(List.of(STARTED, "decided"), getFiredEventNames());
        assertEquals(ExtractionStatus.STATUS_DONE, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertEquals("Unable to identify the proposal", getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    // Each parse queues a job: once the first ended the reading, the second must not ask again, nor complete a
    // task the intake reached since
    @Test
    void stopsWithoutClaimingOnceTheReadingEnded() throws Exception
    {
        createWaitingTask();
        this.consumer.process(this.job);
        Mockito.clearInvocations(this.harness.getEngine(), this.intake);
        createWaitingTask();

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals(List.of(), getFiredEventNames());
        Mockito.verifyNoInteractions(this.intake);
    }

    @Test
    void validatesWhenAnEarlierValidationSavedNoRun() throws Exception
    {
        createWaitingTask();
        this.consumer.process(this.job);
        final Resource validation = getValidation();
        for (final Resource run : validation.getChildren()) {
            this.context.resourceResolver().delete(run);
        }
        // A new parse starts a new reading
        this.harness.restartReading();
        createWaitingTask();

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of(STARTED, "validated", "complete", "decided"), getFiredEventNames());
    }

    private Resource getValidation()
    {
        return this.context.resourceResolver().getResource(getValidationModel().getPath());
    }

    private Validation getValidationModel()
    {
        return getSubmissionResource().adaptTo(Submission.class)
            .findValidation(getProposalValidation().adaptTo(ValidationRequirement.class));
    }

    private Resource getSubmissionResource()
    {
        return this.context.resourceResolver().getResource(this.submission.getPath());
    }

    @Test
    void asksOneYesOrNoQuestionWithTheTemplateAsReference() throws Exception
    {
        this.tree.addTemplate("is_proposal", "A proposal states its aims and methods.");
        createWaitingTask();

        this.consumer.process(this.job);

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<List<ExtractionPrompt.Group>> groups = ArgumentCaptor.forClass(List.class);
        final ArgumentCaptor<String> references = ArgumentCaptor.forClass(String.class);
        Mockito.verify(this.intake).askQuestions(Mockito.any(File.class), Mockito.any(), groups.capture(),
            references.capture());
        final ExtractionField field = groups.getValue().get(0).fields().get(0);
        assertEquals("proposal", groups.getValue().get(0).name());
        assertEquals(PROMPT, field.instructions());
        assertEquals(List.of("yes", "no"), field.answer().options().stream().map(ExtractionField.Option::value)
            .toList());
        assertTrue(references.getValue().contains("A proposal states its aims and methods."));
    }

    // A no ends the reading only once the intake took it, so a missing intake is reported as for any verdict
    @Test
    void givesUpOnANoWhenNoWorkflowWaits() throws Exception
    {
        stubVerdict("no", SubmissionTree.SURE);

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of(STARTED, "validated", "readingFailed"), getFiredEventNames());
        assertEquals(ReadingJobs.NOT_SET_UP_MESSAGE, getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    // Left running, the submission would spin forever: nothing else carries the intake on
    @Test
    void givesUpWhenNoWorkflowWaits() throws Exception
    {
        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of(STARTED, "validated", "readingFailed"), getFiredEventNames());
        assertEquals(ExtractionStatus.STATUS_FAILED, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertEquals(ReadingJobs.NOT_SET_UP_MESSAGE, getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    @Test
    void givesUpWhenTheTaskOfTheValidationIsClosed() throws Exception
    {
        this.tree.createIntakeTask("is_proposal", "completed", IntakeTasks.READER_USER);

        this.consumer.process(this.job);

        assertEquals(List.of(STARTED, "validated", "readingFailed"), getFiredEventNames());
    }

    @Test
    void givesUpWhenTheOpenTaskOfTheValidationIsSomebodyElses() throws Exception
    {
        this.tree.createIntakeTask("is_proposal", "created", "demo-researcher");

        this.consumer.process(this.job);

        assertEquals(List.of(STARTED, "validated", "readingFailed"), getFiredEventNames());
    }

    // The consent was left out, so the intake must not take the proposal's verdict as the consent's
    @Test
    void completesOnlyTheTaskThatWaitsForTheValidation() throws Exception
    {
        createOptionalConsentLeftOut();
        final Resource consentTask =
            this.tree.createIntakeTask("is_consent", "created", IntakeTasks.READER_USER);
        final Resource proposalTask = createWaitingTask();

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals("created", consentTask.getValueMap().get("status", String.class));
        assertEquals("completed", proposalTask.getValueMap().get("status", String.class));
    }

    // A person's task that happens to share the validation's name is not the intake's
    @Test
    void completesTheIntakeTaskAndNotAPersonsTaskOfTheSameName() throws Exception
    {
        final Resource personsTask = this.tree.createIntakeTask("is_proposal", "created", "demo-researcher");
        final String instances = SubmissionTree.SUBMISSION_PATH + "/wf:instances";
        this.context.create().resource(instances + "/intakeFlow", Map.of("sling:resourceType", "wf/WorkflowInstance",
            "status", "active"));
        final Resource intakeTask = this.context.create().resource(instances + "/intakeFlow/is_proposal", Map.of(
            "sling:resourceType", "wf/TaskInstance", "status", "created", "performers",
            new String[] { IntakeTasks.READER_USER }, "taskDefinitionId", "is_proposal"));

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals("created", personsTask.getValueMap().get("status", String.class));
        assertEquals("completed", intakeTask.getValueMap().get("status", String.class));
    }

    // Read again or a rerun after a restart carries the intake on, and the model is not paid twice
    @Test
    void handsOverASavedVerdictWhoseTaskIsStillOpen() throws Exception
    {
        final Resource task = createWaitingTask();
        processOnceWithALostHandOver();

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of(STARTED, "complete", "decided"), getFiredEventNames());
        Mockito.verifyNoInteractions(this.intake);
        assertEquals("completed", task.getValueMap().get("status", String.class));
        assertEquals(ExtractionStatus.STATUS_DONE, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
    }

    @Test
    void givesUpWhenTheTaskCannotBeCompleted() throws Exception
    {
        createWaitingTask();
        failTheHandOver();

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(ExtractionStatus.STATUS_FAILED, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertEquals(ReadingJobs.COULD_NOT_READ_MESSAGE, getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    @Test
    void givesUpNothingWhenTheSubmissionWentAwayMeanwhile() throws Exception
    {
        createWaitingTask();
        this.harness.putStep(DocumentValidator.COMPLETE_EVENT, call -> {
            this.context.resourceResolver().delete(this.context.resourceResolver().getResource(
                this.submission.getPath()));
            this.context.resourceResolver().commit();
            throw new WorkflowFailedException("The submission is gone");
        });

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of(STARTED, "validated", "complete"), getFiredEventNames());
    }

    // A Stop or a Read again took the reading while the model was answering
    @Test
    void dropsTheVerdictWhenTheReadingIsNoLongerItsOwn() throws Exception
    {
        createWaitingTask();
        Mockito.doAnswer(call -> {
            takeTheReadingOver();
            return answer(call);
        }).when(this.intake).askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any());

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of(STARTED, "validated"), getFiredEventNames());
        assertNull(getValidationModel(), "the late verdict is not saved");
        assertEquals(ReadingHarness.OTHER_JOB_ID,
            getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY));
    }

    /** Hands the reading to another job, as a Read again does while this job waits for the model. */
    private void takeTheReadingOver() throws Exception
    {
        getSubmissionResource().adaptTo(ModifiableValueMap.class).put(ExtractionStatus.READING_CLAIMED_BY_PROPERTY,
            ReadingHarness.OTHER_JOB_ID);
        this.context.resourceResolver().commit();
    }

    @Test
    void givesUpWhenTheModelCouldNotBeAsked() throws Exception
    {
        Mockito.doReturn(IntakeResult.createModelFailure(IntakeResult.UNREACHABLE_MESSAGE)).when(this.intake)
            .askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any());

        this.consumer.process(this.job);

        assertEquals(List.of(STARTED, "readingFailed"), getFiredEventNames());
        assertEquals(IntakeResult.UNREACHABLE_MESSAGE, getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
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

    @Test
    void doesNotStartWithoutAValidation() throws Exception
    {
        deleteProposalValidation();

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals(List.of("readingFailed"), getFiredEventNames());
        assertEquals(ReadingJobs.NOT_SET_UP_MESSAGE, getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    @Test
    void doesNotStartWithAValidationThatNamesNoDocument() throws Exception
    {
        getProposalValidation().adaptTo(ModifiableValueMap.class).remove("document");

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals(ReadingJobs.NOT_SET_UP_MESSAGE, getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    // Left as it is, `running` would never end: the job gives the reading up, and Sling does not retry it
    @Test
    void givesUpWhenACheckFailsBeforeTheClaim() throws Exception
    {
        final ConditionEvaluator conditions = Mockito.mock(ConditionEvaluator.class);
        Mockito.when(conditions.applies(Mockito.any(), Mockito.any()))
            .thenThrow(new IllegalStateException("A condition nobody can evaluate"));
        this.context.registerService(ConditionEvaluator.class, conditions);

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals(List.of("readingFailed"), getFiredEventNames());
        assertEquals(ReadingJobs.COULD_NOT_READ_MESSAGE, getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    // The check is set up and only waits for an answer; Read again runs it once it applies
    @Test
    void endsTheReadingAtOnceWhenTheValidationDoesNotApplyYet() throws Exception
    {
        final ConditionEvaluator conditions = Mockito.mock(ConditionEvaluator.class);
        this.context.registerService(ConditionEvaluator.class, conditions);

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        Mockito.verifyNoInteractions(this.intake);
        assertEquals(ExtractionStatus.STATUS_DONE, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertNull(getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    @Test
    void doesNotStartWhenNoValidatedDocumentWasUploaded() throws Exception
    {
        this.tree.createDocumentRequirement("consent").adaptTo(ModifiableValueMap.class).put("required", false);
        this.tree.createValidationRequirement("is_signed", "consent", PROMPT);
        deleteProposalValidation();

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals(SubmissionFiles.NO_DOCUMENT_MESSAGE, getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    @Test
    void doesNotStartWhenTheUploadCouldNotBeParsed() throws Exception
    {
        final Resource failed = this.tree.createFile("failed");
        SubmissionTree.setReference(failed.getParent().getParent(), "fulfills",
            this.tree.createDocumentRequirement("consent"));
        this.tree.createValidationRequirement("is_signed", "consent", PROMPT);
        deleteProposalValidation();

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals("The consent could not be read, please try again",
            getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    // The schema asks to check a document it never has parsed, so no upload helps
    @Test
    void doesNotStartWhenTheDocumentIsNeverParsed() throws Exception
    {
        final Resource unparsed = this.tree.createUnparsedFile();
        final Resource consent = this.tree.createDocumentRequirement("consent");
        consent.adaptTo(ModifiableValueMap.class).put("skipParsing", true);
        SubmissionTree.setReference(unparsed.getParent().getParent(), "fulfills", consent);
        this.tree.createValidationRequirement("is_signed", "consent", PROMPT);
        deleteProposalValidation();

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals("The consent is not set up to be read", getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    // The submission view should not have let the submitter on without it
    @Test
    void doesNotStartWhenARequiredDocumentIsMissing() throws Exception
    {
        createRequiredConsentLeftOut();

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals("The consent is missing, please upload it",
            getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
        Mockito.verifyNoInteractions(this.intake);
    }

    @Test
    void skipsARequiredDocumentWhoseConditionDoesNotHold() throws Exception
    {
        final Resource consent = createRequiredConsentLeftOut();
        final ConditionEvaluator conditions = Mockito.mock(ConditionEvaluator.class);
        Mockito.when(conditions.applies(Mockito.any(), Mockito.any())).thenReturn(true);
        Mockito.when(conditions.applies(Mockito.argThat(item -> item instanceof final DocumentRequirement requirement
            && consent.getPath().equals(requirement.getPath())), Mockito.any())).thenReturn(false);
        this.context.registerService(ConditionEvaluator.class, conditions);
        createWaitingTask();

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("is_proposal"),
            getVerdicts().stream().map(DecisionJobConsumerTest::getRequirementName).toList());
    }

    // Asked back to back, the two calls about the proposal share the prompt cache
    @Test
    void categorisesEachDocumentRightAfterItsValidation() throws Exception
    {
        final Resource category = createStudyKind();
        createUploadedConsent();
        createWaitingTask();
        this.tree.createIntakeTask("is_consent", "created", IntakeTasks.READER_USER);

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("verdict", "classification.study_kind", "verdict"), getAskedKeys());
        assertEquals(List.of(STARTED, "validated", "complete", "categorised", "validated", "complete",
            "decided"), getFiredEventNames());
        assertEquals(List.of("observational"), getSuggestion(category));
        assertEquals(ExtractionStatus.STATUS_DONE, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertEquals(DecisionJobConsumer.CONFIRM_CATEGORY_MESSAGE,
            getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
        assertNull(getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY));
    }

    @Test
    void validatesADocumentBeforeCategorisingItWhateverTheSchemaOrder() throws Exception
    {
        deleteProposalValidation();
        createStudyKind();
        this.tree.createValidationRequirement("is_proposal", "proposal", PROMPT);
        createWaitingTask();

        this.consumer.process(this.job);

        assertEquals(List.of("verdict", "classification.study_kind"), getAskedKeys());
    }

    // The model must not guess: the submitter picks
    @Test
    void savesNoPickTheModelIsUnsureOf() throws Exception
    {
        final Resource category = createStudyKind();
        createWaitingTask();
        stubPick("observational", SubmissionTree.UNSURE);

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of(), getSuggestion(category));
        assertEquals(DecisionJobConsumer.CONFIRM_CATEGORY_MESSAGE,
            getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    @Test
    void savesNoPickOutsideTheCategories() throws Exception
    {
        final Resource category = createStudyKind();
        createWaitingTask();
        stubPick("chart_review", SubmissionTree.SURE);

        this.consumer.process(this.job);

        assertEquals(List.of(), getSuggestion(category));
        assertEquals(DecisionJobConsumer.CONFIRM_CATEGORY_MESSAGE,
            getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    @Test
    void doesNotCategoriseADocumentItRejected() throws Exception
    {
        final Resource category = createStudyKind();
        createWaitingTask();
        stubVerdict("no", SubmissionTree.SURE);

        this.consumer.process(this.job);

        assertEquals(List.of("verdict"), getAskedKeys());
        assertNull(getSuggestion(category));
    }

    @Test
    void categorisesADocumentWithoutAValidation() throws Exception
    {
        deleteProposalValidation();
        final Resource category = createStudyKind();

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of(STARTED, "categorised", "decided"), getFiredEventNames());
        assertEquals(List.of("observational"), getSuggestion(category));
    }

    // A category the submitter already gave is not asked again, but the reading still has to end
    @Test
    void asksNothingWhenTheCategoryIsAlreadyAnswered() throws Exception
    {
        deleteProposalValidation();
        final Resource category = createStudyKind();
        this.tree.createAnswer(category, "interventional");

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of(STARTED, "decided"), getFiredEventNames());
        Mockito.verifyNoInteractions(this.intake);
        assertEquals(ExtractionStatus.STATUS_DONE, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertNull(getProperties().get(ExtractionStatus.MESSAGE_PROPERTY), "the category is confirmed");
    }

    // Parsing a document again starts a reading that nothing but this job would end
    @Test
    void endsAReadingStartedByAParseOfADocumentAlreadyChecked() throws Exception
    {
        createStudyKind();
        createWaitingTask();
        this.consumer.process(this.job);
        this.harness.restartReading();

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of(STARTED, "decided"), getFiredEventNames());
        Mockito.verifyNoInteractions(this.intake);
        assertEquals(ExtractionStatus.STATUS_DONE, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertEquals(DecisionJobConsumer.CONFIRM_CATEGORY_MESSAGE,
            getProperties().get(ExtractionStatus.MESSAGE_PROPERTY), "the category is still to be confirmed");
    }

    // Ended as done, the failed parse of a re-upload would never reach the submitter
    @Test
    void givesUpWhenADocumentAlreadyCheckedFailsToParseAgain() throws Exception
    {
        createWaitingTask();
        this.consumer.process(this.job);
        final File checked =
            SubmissionFiles.findCurrentFile(SubmissionFiles.getSubmission(getSubmissionResource()), "proposal");
        this.tree.addVersion(this.context.resourceResolver().getResource(checked.getPath()))
            .adaptTo(ModifiableValueMap.class).put(ParsePropertyNames.PARSE_STATUS_PROPERTY,
                ParsePropertyNames.STATUS_FAILED);
        this.harness.restartReading();

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        Mockito.verifyNoInteractions(this.intake);
        assertEquals(ExtractionStatus.STATUS_FAILED, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertEquals("The proposal could not be read, please try again",
            getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    @Test
    void givesUpWhenADocumentOnlyAFormReadsFailedToParse() throws Exception
    {
        deleteProposalValidation();
        this.tree.createParsedFileFor(this.tree.createDocumentRequirement("protocol"), "The protocol.")
            .adaptTo(ModifiableValueMap.class).put(ParsePropertyNames.PARSE_STATUS_PROPERTY,
                ParsePropertyNames.STATUS_FAILED);
        this.tree.setFormDocuments(SubmissionTree.FORM_PATH, "protocol");

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals("The protocol could not be read, please try again",
            getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    // The proposal must be one before its kind of study is asked
    @Test
    void asksADecisionWhoseConditionReadsAnEarlierVerdict() throws Exception
    {
        final Resource category = createStudyKind();
        final AtomicBoolean validated = new AtomicBoolean();
        final ConditionEvaluator conditions = Mockito.mock(ConditionEvaluator.class);
        Mockito.when(conditions.applies(Mockito.any(), Mockito.any())).thenAnswer(
            call -> !(call.getArgument(0) instanceof ClassificationRequirement) || validated.get());
        this.context.registerService(ConditionEvaluator.class, conditions);
        this.harness.putStep(DocumentValidator.VALIDATED_EVENT, call -> {
            this.harness.runStep(new RecordValidationHandler(), call);
            validated.set(true);
            return null;
        });
        createWaitingTask();

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("verdict", "classification.study_kind"), getAskedKeys());
        assertEquals(List.of("observational"), getSuggestion(category));
    }

    // Nothing to check, so the reading ends at once and the forms are read on Next
    @Test
    void endsTheReadingAtOnceWhenOnlyAFormReadsTheDocuments() throws Exception
    {
        deleteProposalValidation();
        this.tree.setFormDocuments(SubmissionTree.FORM_PATH, "proposal");

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of(STARTED, "decided"), getFiredEventNames());
        Mockito.verifyNoInteractions(this.intake);
        assertEquals(ExtractionStatus.STATUS_DONE, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertNull(getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    // The checked consent is optional and was left out, and the proposal is still there for the forms
    @Test
    void endsTheReadingAtOnceWhenOnlyAFormsDocumentWasUploaded() throws Exception
    {
        createOptionalConsentLeftOut();
        deleteProposalValidation();
        this.tree.setFormDocuments(SubmissionTree.FORM_PATH, "proposal");

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        Mockito.verifyNoInteractions(this.intake);
        assertEquals(ExtractionStatus.STATUS_DONE, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertNull(getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    @Test
    void givesUpWhenTheModelCouldNotBeAskedTheCategory() throws Exception
    {
        createStudyKind();
        createWaitingTask();
        Mockito.doAnswer(this::answer).doReturn(IntakeResult.createModelFailure(IntakeResult.UNREACHABLE_MESSAGE))
            .when(this.intake).askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any());

        this.consumer.process(this.job);

        assertEquals(List.of(STARTED, "validated", "complete", "readingFailed"), getFiredEventNames());
        assertEquals(IntakeResult.UNREACHABLE_MESSAGE, getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    @Test
    void dropsThePickWhenTheReadingIsNoLongerItsOwn() throws Exception
    {
        final Resource category = createStudyKind();
        createWaitingTask();
        this.harness.putStep(DocumentValidator.COMPLETE_EVENT, call -> {
            this.harness.closeTask(call);
            takeTheReadingOver();
            return null;
        });

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of(STARTED, "validated", "complete", "categorised"), getFiredEventNames());
        assertNull(getSuggestion(category), "the late pick is not saved");
    }

    @Test
    void leavesTheEndToTheReadingThatTookOver() throws Exception
    {
        createWaitingTask();
        this.harness.putStep(DocumentValidator.COMPLETE_EVENT, call -> {
            this.harness.closeTask(call);
            takeTheReadingOver();
            return null;
        });

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of(STARTED, "validated", "complete", "decided"), getFiredEventNames());
        assertEquals(ExtractionStatus.STATUS_RUNNING, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertEquals(ReadingHarness.OTHER_JOB_ID,
            getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY));
    }
}

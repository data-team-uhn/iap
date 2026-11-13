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
import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.ValidationRequirement;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;

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
    private static final String JOB_ID = "job-1";

    private static final String PROMPT = "Decide whether the document is a research proposal.";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final WorkflowEngine engine = Mockito.mock(WorkflowEngine.class);

    private final AnswerIntakeService intake = Mockito.mock(AnswerIntakeService.class);

    private final Job job = Mockito.mock(Job.class);

    private final DecisionJobConsumer consumer = new DecisionJobConsumer();

    private final RequirementReader reader = new RequirementReader();

    /** What the stand-in engine does for each event, by name. */
    private final Map<String, Answer<Object>> steps = new HashMap<>();

    private SubmissionTree tree;

    private String verdict = "yes";

    private double verdictConfidence = 0.9;

    private String pick = "observational";

    private double pickConfidence = 0.9;

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
        final ReadingRuns runs = new ReadingRuns();
        final ReadingJobs jobs = new ReadingJobs();
        inject(jobs, "resolverFactory", new TestResolverFactory(this.context.resourceResolver()));
        inject(jobs, "engine", this.engine);
        inject(jobs, "runs", runs);
        inject(this.reader, "intake", this.intake);
        inject(this.reader, "documents", new ParsedDocuments());
        inject(this.reader, "runs", runs);
        final DocumentValidator validator = new DocumentValidator();
        inject(validator, "jobs", jobs);
        inject(validator, "engine", this.engine);
        inject(validator, "reader", this.reader);
        final DocumentCategoriser categoriser = new DocumentCategoriser();
        inject(categoriser, "jobs", jobs);
        inject(categoriser, "reader", this.reader);
        inject(this.consumer, "jobs", jobs);
        inject(this.consumer, "validator", validator);
        inject(this.consumer, "categoriser", categoriser);
        Mockito.when(this.job.getId()).thenReturn(JOB_ID);
        Mockito.when(this.job.getProperty(ReadingJobs.SUBMISSION_PROPERTY, String.class))
            .thenReturn(this.submission.getPath());
        this.steps.put(ReadingJobs.READING_STARTED_EVENT, call -> runStep(new ClaimReadingHandler(), call));
        this.steps.put(ReadingJobs.READING_FAILED_EVENT, call -> runStep(new GiveUpReadingHandler(), call));
        this.steps.put(DocumentValidator.VALIDATED_EVENT, call -> runStep(new RecordValidationHandler(), call));
        this.steps.put(DocumentValidator.COMPLETE_EVENT, this::closeTask);
        this.steps.put(DocumentCategoriser.CATEGORISED_EVENT, call -> runStep(new RecordCategoryHandler(), call));
        this.steps.put(DecisionJobConsumer.DECIDED_EVENT, call -> runStep(new EndReadingHandler(), call));
        Mockito.when(this.engine.receiveEvent(Mockito.any(), Mockito.any())).thenAnswer(
            call -> this.steps.get(call.<WorkflowEvent>getArgument(1).getName()).answer(call));
        Mockito.when(this.intake.askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any()))
            .thenAnswer(this::answer);
    }

    private static void inject(final Object component, final String name, final Object value) throws Exception
    {
        final Field field = component.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(component, value);
    }

    /**
     * Runs a step as the engine would: through its own session, committed when the step succeeds.
     */
    private Object runStep(final ServiceTaskHandler handler, final InvocationOnMock call) throws Exception
    {
        final Resource target = SubmissionTree.withTypedCreates(
            this.context.resourceResolver().getResource(call.<Resource>getArgument(0).getPath()));
        handler.execute(TaskContexts.of(target, call.<WorkflowEvent>getArgument(1).getPayload(), new HashMap<>()));
        this.context.resourceResolver().commit();
        return null;
    }

    /** Completes a task as the engine would, so the intake moves on to its next one. */
    private Object closeTask(final InvocationOnMock call) throws Exception
    {
        this.context.resourceResolver().getResource(call.<Resource>getArgument(0).getPath())
            .adaptTo(ModifiableValueMap.class).put("status", "completed");
        this.context.resourceResolver().commit();
        return null;
    }

    /**
     * Adds a consent form the schema asks for after the proposal, and its validation. One not uploaded is optional,
     * so leaving it out is allowed.
     */
    private void createConsentValidation(final boolean uploaded)
    {
        final Resource consent = this.tree.createDocumentRequirement("consent");
        this.tree.createValidationRequirement("is_consent", "consent", "Decide whether it is a consent form.");
        if (uploaded) {
            this.tree.createParsedFileFor(consent, "I agree to take part.");
        } else {
            consent.adaptTo(ModifiableValueMap.class).put("required", false);
        }
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
        return IntakeResult.createRead(Map.of(key, new FieldResult(key,
            List.of(validation ? this.verdict : this.pick), "It says so.",
            validation ? this.verdictConfidence : this.pickConfidence, List.of(), 0)));
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
        return SubmissionFiles.getSubmission(getSubmissionResource()).getAnswers().stream()
            .filter(answer -> answer.getQuestion() != null
                && question.getPath().equals(answer.getQuestion().getPath()))
            .map(answer -> answer.getLatestExtraction().getExtractedValues())
            .findFirst()
            .orElse(null);
    }

    private List<String> getAskedKeys()
    {
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<List<ExtractionPrompt.Group>> groups = ArgumentCaptor.forClass(List.class);
        Mockito.verify(this.intake, Mockito.atLeast(0)).askQuestions(Mockito.any(), Mockito.any(), groups.capture(),
            Mockito.any());
        return groups.getAllValues().stream().map(asked -> asked.get(0).fields().get(0).key()).toList();
    }

    private Resource createWaitingTask()
    {
        return this.tree.createIntakeTask("checkProposal", "created", this.context.resourceResolver().getUserID());
    }

    private List<WorkflowEvent> getFiredEvents() throws Exception
    {
        final ArgumentCaptor<WorkflowEvent> events = ArgumentCaptor.forClass(WorkflowEvent.class);
        Mockito.verify(this.engine, Mockito.atLeast(0)).receiveEvent(Mockito.any(), events.capture());
        return events.getAllValues();
    }

    private List<String> getFiredEventNames() throws Exception
    {
        return getFiredEvents().stream().map(WorkflowEvent::getName).toList();
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

        assertEquals(List.of("readingStarted", "validated", "complete", "decided"), getFiredEventNames());
        final ArgumentCaptor<Resource> targets = ArgumentCaptor.forClass(Resource.class);
        Mockito.verify(this.engine, Mockito.times(4)).receiveEvent(targets.capture(), Mockito.any());
        assertEquals(task.getPath(), targets.getAllValues().get(2).getPath());
        // Nothing was categorised, so there is nothing to confirm
        assertEquals(ExtractionStatus.STATUS_DONE, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertNull(getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
        assertNull(getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY));
    }

    @Test
    void asksEachValidationInTheOrderOfItsDocument() throws Exception
    {
        // The validations are listed consent first; the documents, proposal first
        this.context.resourceResolver().delete(
            this.context.resourceResolver().getResource(SubmissionTree.VERSION_PATH + "/is_proposal"));
        createConsentValidation(true);
        this.tree.createValidationRequirement("is_proposal", "proposal", PROMPT);
        createWaitingTask();
        this.tree.createIntakeTask("checkConsent", "created", this.context.resourceResolver().getUserID());

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("readingStarted", "validated", "complete", "validated", "complete", "decided"),
            getFiredEventNames());
        assertEquals(List.of("is_proposal", "is_consent"),
            getVerdicts().stream().map(DecisionJobConsumerTest::getRequirementName).toList());
    }

    @Test
    void asksNothingAfterANo() throws Exception
    {
        createConsentValidation(true);
        createWaitingTask();
        stubVerdict("no", 0.9);

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("readingStarted", "validated", "complete"), getFiredEventNames());
        Mockito.verify(this.intake, Mockito.times(1)).askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(),
            Mockito.any());
    }

    @Test
    void skipsAValidationWhoseDocumentWasNotUploaded() throws Exception
    {
        createConsentValidation(false);
        createWaitingTask();

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("is_proposal"),
            getVerdicts().stream().map(DecisionJobConsumerTest::getRequirementName).toList());
    }

    @Test
    void asksNothingMoreAfterAStop() throws Exception
    {
        createConsentValidation(true);
        createWaitingTask();
        this.steps.put(DocumentValidator.COMPLETE_EVENT, call -> {
            closeTask(call);
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
        createConsentValidation(true);
        createWaitingTask();
        this.steps.put(DocumentValidator.COMPLETE_EVENT, call -> {
            this.context.resourceResolver().delete(getSubmissionResource());
            this.context.resourceResolver().commit();
            return null;
        });

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(1, getVerdicts().size());
    }

    // The no ended the reading, so the validations after it are never asked
    @Test
    void stopsWithoutClaimingAfterANo() throws Exception
    {
        createConsentValidation(true);
        createWaitingTask();
        stubVerdict("no", 0.9);
        this.consumer.process(this.job);
        Mockito.clearInvocations(this.engine, this.intake);

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals(List.of(), getFiredEventNames());
    }

    // Each parse queues a job: once the first ended the reading, the second must not ask again, nor complete a
    // task the intake reached since
    @Test
    void stopsWithoutClaimingOnceTheReadingEnded() throws Exception
    {
        createWaitingTask();
        this.consumer.process(this.job);
        Mockito.clearInvocations(this.engine, this.intake);
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
        getSubmissionResource().adaptTo(ModifiableValueMap.class).put(ExtractionStatus.STATUS_PROPERTY,
            ExtractionStatus.STATUS_RUNNING);
        this.context.resourceResolver().commit();
        Mockito.clearInvocations(this.engine);
        createWaitingTask();

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("readingStarted", "validated", "complete", "decided"), getFiredEventNames());
    }

    private Resource getValidation()
    {
        final Submission model =
            getSubmissionResource().adaptTo(Submission.class);
        return this.context.resourceResolver().getResource(model.findValidation(
            model.getSchemaVersion().getRequirements().stream()
                .filter(ValidationRequirement.class::isInstance)
                .map(ValidationRequirement.class::cast)
                .findFirst().orElseThrow()).getPath());
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

    // A no ends the reading as done, so nothing is given up even when no workflow waits
    @Test
    void leavesARejectedReadingDoneWhenNoWorkflowWaits() throws Exception
    {
        stubVerdict("no", 0.9);

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("readingStarted", "validated"), getFiredEventNames());
        assertEquals(ExtractionStatus.STATUS_DONE, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
    }

    // Left running, the submission would spin forever: nothing else carries the intake on
    @Test
    void givesUpWhenNoWorkflowWaits() throws Exception
    {
        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("readingStarted", "validated", "readingFailed"), getFiredEventNames());
        assertEquals(ExtractionStatus.STATUS_FAILED, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertEquals(ReadingJobs.NOT_SET_UP_MESSAGE, getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    @Test
    void givesUpWhenNoOpenTaskIsItsOwn() throws Exception
    {
        this.tree.createIntakeTask("done", "completed", this.context.resourceResolver().getUserID());
        this.tree.createIntakeTask("forTheSubmitter", "created", "demo-researcher");

        this.consumer.process(this.job);

        assertEquals(List.of("readingStarted", "validated", "readingFailed"), getFiredEventNames());
    }

    @Test
    void givesUpWhenTheTaskCannotBeCompleted() throws Exception
    {
        createWaitingTask();
        this.steps.put(DocumentValidator.COMPLETE_EVENT, call -> {
            throw new WorkflowFailedException("The intake broke");
        });

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(ExtractionStatus.STATUS_FAILED, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertEquals(ReadingJobs.COULD_NOT_READ_MESSAGE, getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    @Test
    void givesUpNothingWhenTheSubmissionWentAwayMeanwhile() throws Exception
    {
        createWaitingTask();
        this.steps.put(DocumentValidator.COMPLETE_EVENT, call -> {
            this.context.resourceResolver().delete(this.context.resourceResolver().getResource(
                this.submission.getPath()));
            this.context.resourceResolver().commit();
            throw new WorkflowFailedException("The submission is gone");
        });

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("readingStarted", "validated", "complete"), getFiredEventNames());
    }

    // A Stop or a Read again took the reading while the model was answering
    @Test
    void dropsTheVerdictWhenTheReadingIsNoLongerItsOwn() throws Exception
    {
        createWaitingTask();
        this.steps.put(DocumentValidator.VALIDATED_EVENT, call -> {
            throw new InvalidStateException("The reading is no longer this job's");
        });

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("readingStarted", "validated"), getFiredEventNames());
    }

    @Test
    void givesUpWhenTheModelCouldNotBeAsked() throws Exception
    {
        Mockito.doReturn(IntakeResult.createNotRead(IntakeResult.UNREACHABLE_MESSAGE)).when(this.intake)
            .askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any());

        this.consumer.process(this.job);

        assertEquals(List.of("readingStarted", "readingFailed"), getFiredEventNames());
        assertEquals(IntakeResult.UNREACHABLE_MESSAGE, getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
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

    @Test
    void doesNotStartWithoutAValidation() throws Exception
    {
        this.context.resourceResolver().delete(
            this.context.resourceResolver().getResource(SubmissionTree.VERSION_PATH + "/is_proposal"));

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals(List.of("readingFailed"), getFiredEventNames());
        assertEquals(ReadingJobs.NOT_SET_UP_MESSAGE, getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    @Test
    void doesNotStartWithAValidationThatNamesNoDocument() throws Exception
    {
        this.context.resourceResolver().getResource(SubmissionTree.VERSION_PATH + "/is_proposal")
            .adaptTo(ModifiableValueMap.class).remove("document");

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

    @Test
    void doesNotStartWhenTheValidationDoesNotApply() throws Exception
    {
        final ConditionEvaluator conditions = Mockito.mock(ConditionEvaluator.class);
        this.context.registerService(ConditionEvaluator.class, conditions);

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals(ReadingJobs.NOT_SET_UP_MESSAGE, getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    @Test
    void doesNotStartWhenNoValidatedDocumentWasUploaded() throws Exception
    {
        this.tree.createDocumentRequirement("consent").adaptTo(ModifiableValueMap.class).put("required", false);
        this.tree.createValidationRequirement("is_signed", "consent", PROMPT);
        this.context.resourceResolver().delete(
            this.context.resourceResolver().getResource(SubmissionTree.VERSION_PATH + "/is_proposal"));

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals(SubmissionFiles.NO_DOCUMENT_MESSAGE, getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    @Test
    void doesNotStartWhenTheUploadCouldNotBeParsed() throws Exception
    {
        final Resource failed = this.tree.createFile("failed");
        SubmissionTree.reference(failed.getParent().getParent(), "fulfills",
            this.tree.createDocumentRequirement("consent"));
        this.tree.createValidationRequirement("is_signed", "consent", PROMPT);
        this.context.resourceResolver().delete(
            this.context.resourceResolver().getResource(SubmissionTree.VERSION_PATH + "/is_proposal"));

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals("The consent could not be read, please try again",
            getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    // The schema asks to check a document it never has parsed, so no upload helps
    @Test
    void doesNotStartWhenTheDocumentIsNeverParsed() throws Exception
    {
        final Resource unparsed = this.tree.createFile(null);
        final Resource consent = this.tree.createDocumentRequirement("consent");
        consent.adaptTo(ModifiableValueMap.class).put("skipParsing", true);
        SubmissionTree.reference(unparsed.getParent().getParent(), "fulfills", consent);
        this.tree.createValidationRequirement("is_signed", "consent", PROMPT);
        this.context.resourceResolver().delete(
            this.context.resourceResolver().getResource(SubmissionTree.VERSION_PATH + "/is_proposal"));

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals("The consent is not set up to be read", getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    // The submission view should not have let the submitter on without it
    @Test
    void doesNotStartWhenARequiredDocumentIsMissing() throws Exception
    {
        createRequiredConsent();

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals("The consent is missing, please upload it",
            getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
        Mockito.verifyNoInteractions(this.intake);
    }

    @Test
    void skipsARequiredDocumentWhoseConditionDoesNotHold() throws Exception
    {
        final Resource consent = createRequiredConsent();
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

    private Resource createRequiredConsent()
    {
        final Resource consent = this.tree.createDocumentRequirement("consent");
        this.tree.createValidationRequirement("is_consent", "consent", "Decide whether it is a consent form.");
        return consent;
    }

    // Asked back to back, the two calls about the proposal share the prompt cache
    @Test
    void categorisesEachDocumentRightAfterItsValidation() throws Exception
    {
        final Resource category = createStudyKind();
        createConsentValidation(true);
        createWaitingTask();
        this.tree.createIntakeTask("checkConsent", "created", this.context.resourceResolver().getUserID());

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("verdict", "classification.study_kind", "verdict"), getAskedKeys());
        assertEquals(List.of("readingStarted", "validated", "complete", "categorised", "validated", "complete",
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
        this.context.resourceResolver().delete(
            this.context.resourceResolver().getResource(SubmissionTree.VERSION_PATH + "/is_proposal"));
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
        stubPick("observational", 0.4);

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
        stubPick("chart_review", 0.95);

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
        stubVerdict("no", 0.9);

        this.consumer.process(this.job);

        assertEquals(List.of("verdict"), getAskedKeys());
        assertNull(getSuggestion(category));
    }

    @Test
    void categorisesADocumentWithoutAValidation() throws Exception
    {
        this.context.resourceResolver().delete(
            this.context.resourceResolver().getResource(SubmissionTree.VERSION_PATH + "/is_proposal"));
        final Resource category = createStudyKind();

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("readingStarted", "categorised", "decided"), getFiredEventNames());
        assertEquals(List.of("observational"), getSuggestion(category));
    }

    // A category the submitter already gave is not asked again, but the reading still has to end
    @Test
    void asksNothingWhenTheCategoryIsAlreadyAnswered() throws Exception
    {
        this.context.resourceResolver().delete(
            this.context.resourceResolver().getResource(SubmissionTree.VERSION_PATH + "/is_proposal"));
        final Resource category = createStudyKind();
        final Resource answer = this.context.create().resource(SubmissionTree.SUBMISSION_PATH + "/a1",
            Map.of("jcr:primaryType", "sub:Answer", "sling:resourceType", "sub/Answer", "value",
                new String[] { "interventional" }));
        SubmissionTree.reference(answer, "question", category);

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("readingStarted", "decided"), getFiredEventNames());
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
        getSubmissionResource().adaptTo(ModifiableValueMap.class).put(ExtractionStatus.STATUS_PROPERTY,
            ExtractionStatus.STATUS_RUNNING);
        this.context.resourceResolver().commit();
        Mockito.clearInvocations(this.engine, this.intake);

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("readingStarted", "decided"), getFiredEventNames());
        Mockito.verifyNoInteractions(this.intake);
        assertEquals(ExtractionStatus.STATUS_DONE, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertEquals(DecisionJobConsumer.CONFIRM_CATEGORY_MESSAGE,
            getProperties().get(ExtractionStatus.MESSAGE_PROPERTY), "the category is still to be confirmed");
    }

    // Nothing to check, so the reading ends at once and the forms are read on Next
    @Test
    void endsTheReadingAtOnceWhenOnlyAFormReadsTheDocuments() throws Exception
    {
        this.context.resourceResolver().delete(
            this.context.resourceResolver().getResource(SubmissionTree.VERSION_PATH + "/is_proposal"));
        this.context.resourceResolver().getResource(SubmissionTree.FORM_PATH).adaptTo(ModifiableValueMap.class)
            .put("documents", new String[] { "proposal" });

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("readingStarted", "decided"), getFiredEventNames());
        Mockito.verifyNoInteractions(this.intake);
        assertEquals(ExtractionStatus.STATUS_DONE, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertNull(getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    @Test
    void leavesOutAClassificationWhoseQuestionIsGone() throws Exception
    {
        this.context.resourceResolver().delete(
            this.context.resourceResolver().getResource(SubmissionTree.VERSION_PATH + "/is_proposal"));
        this.context.resourceResolver().delete(createStudyKind());

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals(ReadingJobs.NOT_SET_UP_MESSAGE, getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    @Test
    void givesUpWhenTheModelCouldNotBeAskedTheCategory() throws Exception
    {
        createStudyKind();
        createWaitingTask();
        Mockito.doAnswer(this::answer).doReturn(IntakeResult.createNotRead(IntakeResult.UNREACHABLE_MESSAGE))
            .when(this.intake).askQuestions(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.any());

        this.consumer.process(this.job);

        assertEquals(List.of("readingStarted", "validated", "complete", "readingFailed"), getFiredEventNames());
        assertEquals(IntakeResult.UNREACHABLE_MESSAGE, getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    @Test
    void dropsThePickWhenTheReadingIsNoLongerItsOwn() throws Exception
    {
        createStudyKind();
        createWaitingTask();
        this.steps.put(DocumentCategoriser.CATEGORISED_EVENT, call -> {
            throw new InvalidStateException("The reading is no longer this job's");
        });

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(List.of("readingStarted", "validated", "complete", "categorised"), getFiredEventNames());
    }

    @Test
    void leavesTheEndToTheReadingThatTookOver() throws Exception
    {
        createWaitingTask();
        this.steps.put(DecisionJobConsumer.DECIDED_EVENT, call -> {
            throw new InvalidStateException("The reading is no longer this job's");
        });

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(ExtractionStatus.STATUS_RUNNING, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
    }
}

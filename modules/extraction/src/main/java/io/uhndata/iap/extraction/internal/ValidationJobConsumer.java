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

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.consumer.JobConsumer;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.extraction.internal.AnswerIntakeService.IntakeResult;
import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.ValidationRequirement;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.models.Validation;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowInstances;

/**
 * Asks the model whether each uploaded document is what its requirement asks for, such as a research proposal, as
 * a background job. Each validation belongs to one document requirement and has its own prompt, and its template
 * as reference text. A validation whose document has no upload is not asked.
 *
 * <p>The job asks the validations one at a time, in the order of their document requirements. For each, it fires
 * {@code validated} with the model's answer, and that system workflow saves it on the submission
 * ({@link RecordValidationHandler}). Then it completes the task the submission's intake workflow waits at, so the
 * intake's gateway reads the verdict through the {@code validation} operand and goes on, or ends. A {@code no} ends
 * the reading, so the validations after it are not asked. The intake waits at a task because the engine cannot yet
 * wake a workflow with a named event.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = JobConsumer.class,
    property = { JobConsumer.PROPERTY_TOPICS + "=" + ValidationJobConsumer.JOB_TOPIC })
public class ValidationJobConsumer implements JobConsumer, ReadingJob
{
    /** The job topic. */
    public static final String JOB_TOPIC = "iap/extraction/validate";

    /** The event that saves the verdict. */
    static final String VALIDATED_EVENT = "validated";

    /** The event that completes the task the intake waits at. */
    static final String COMPLETE_EVENT = "complete";

    /** The key the model's verdict comes back under. */
    static final String VERDICT_KEY = "verdict";

    private static final Logger LOGGER = LoggerFactory.getLogger(ValidationJobConsumer.class);

    @Reference
    private ReadingJobs jobs;

    @Reference
    private WorkflowEngine engine;

    @Reference
    private DecisionReader reader;

    @Override
    public JobResult process(final Job job)
    {
        return this.jobs.run(job, this);
    }

    /**
     * Whether nothing is left to validate: a validation said no, which ends the reading, or every validation whose
     * document was uploaded has a verdict.
     *
     * @param submission the submission to read
     * @return {@code true} when the model has nothing left to be asked
     */
    @Override
    public boolean isAlreadyRead(final Submission submission)
    {
        final List<String> verdicts = getUploadedValidations(submission).stream()
            .map(requirement -> getVerdict(submission, requirement))
            .toList();
        return !verdicts.isEmpty()
            && (verdicts.contains(Validation.OUTCOME_NO) || verdicts.stream().allMatch(Objects::nonNull));
    }

    /**
     * Why the validation cannot start: the schema has no validation, a document still to be validated is missing,
     * or none of their documents was uploaded.
     *
     * @param resolver this job's session
     * @param submission the submission to read
     * @return the message for the submitter, or {@code null} when every document still to be validated is parsed
     */
    @Override
    public String findReasonNotToStart(final ResourceResolver resolver, final Submission submission)
    {
        final List<ValidationRequirement> validations = getValidations(submission);
        if (validations.isEmpty()) {
            return ReadingJobs.NOT_SET_UP_MESSAGE;
        }
        final String missing = validations.stream()
            .filter(requirement -> getVerdict(submission, requirement) == null && isMissing(submission, requirement))
            .map(requirement -> describeMissing(getDocument(submission, requirement).getLabel()))
            .findFirst()
            .orElse(null);
        if (missing != null) {
            return missing;
        }
        return getPendingValidations(submission).isEmpty() ? SubmissionFiles.NO_DOCUMENT_MESSAGE : null;
    }

    /**
     * What the submitter is told when a document to validate is missing.
     *
     * @param label the document requirement's label
     * @return the message
     */
    static String describeMissing(final String label)
    {
        return "The " + label + " is missing, please upload it";
    }

    /**
     * Whether a document the validation reads should be there and cannot be read: a required one was never uploaded,
     * which the submission view should have prevented, or its parse failed. An optional one left out is not missing.
     */
    private static boolean isMissing(final Submission submission, final ValidationRequirement requirement)
    {
        final File file = getFile(submission, requirement);
        if (file == null) {
            final DocumentRequirement document = getDocument(submission, requirement);
            return document.isRequired() && submission.isApplicable(document);
        }
        // Every parse has settled by now, so one that is not completed failed or was stopped
        return !ParsePropertyNames.STATUS_COMPLETED.equals(file.getParseStatus());
    }

    @Override
    public void read(final Resource submission, final String jobId) throws WorkflowException
    {
        final ResourceResolver resolver = submission.getResourceResolver();
        final List<ValidationRequirement> pending = getPendingValidations(SubmissionFiles.getSubmission(submission));
        for (int next = 0; next < pending.size(); next++) {
            // Each verdict is saved through the engine's session, which this one sees only after a refresh
            resolver.refresh();
            final Resource current = resolver.getResource(submission.getPath());
            if (current == null || next > 0 && !isRunning(current)) {
                // A no or a Stop ended the reading
                return;
            }
            if (!validate(current, jobId, pending.get(next), next == pending.size() - 1)) {
                return;
            }
        }
    }

    /**
     * Asks one validation, saves the verdict and carries the intake on.
     *
     * @param last whether this is the job's last validation, after which it lets the reading go
     * @return {@code false} when the job must stop: the reading was given up, or is no longer this job's
     */
    private boolean validate(final Resource submission, final String jobId, final ValidationRequirement requirement,
        final boolean last) throws WorkflowException
    {
        final File file = getFile(SubmissionFiles.getSubmission(submission), requirement);
        final IntakeResult result = this.reader.ask(submission.getPath(), file, requirement,
            ExtractionField.createYesNo(VERDICT_KEY, requirement.getLabel(), requirement.getPrompt()));
        if (result.status() == AnswerIntakeService.Status.NOT_READ) {
            this.jobs.giveUp(submission, jobId, result.message(), true);
            return false;
        }
        final FieldResult verdict = result.fields().getOrDefault(VERDICT_KEY, FieldResult.createNotFound(VERDICT_KEY));
        return recordVerdict(submission, jobId, requirement, file, verdict, last) && handOver(submission, jobId);
    }

    /**
     * The schema's validations that apply and name one of its documents, in the order of those documents.
     */
    private static List<ValidationRequirement> getValidations(final Submission submission)
    {
        final List<String> documents = submission.getSchemaVersion().getDocumentRequirements().stream()
            .map(DocumentRequirement::getName)
            .toList();
        return submission.getSchemaVersion().getRequirements().stream()
            .filter(ValidationRequirement.class::isInstance)
            .map(ValidationRequirement.class::cast)
            .filter(requirement -> documents.contains(requirement.getDocumentRequirementName())
                && submission.isApplicable(requirement))
            .sorted(Comparator.comparingInt(requirement -> documents.indexOf(requirement.getDocumentRequirementName())))
            .toList();
    }

    /** The validations whose document has an upload, in document order. */
    private static List<ValidationRequirement> getUploadedValidations(final Submission submission)
    {
        return getValidations(submission).stream()
            .filter(requirement -> getFile(submission, requirement) != null)
            .toList();
    }

    /** The validations whose document has an upload and that have no verdict yet, in document order. */
    private static List<ValidationRequirement> getPendingValidations(final Submission submission)
    {
        return getUploadedValidations(submission).stream()
            .filter(requirement -> getVerdict(submission, requirement) == null)
            .toList();
    }

    /** The document requirement the validation reads; {@link #getValidations} keeps only those that have one. */
    private static DocumentRequirement getDocument(final Submission submission,
        final ValidationRequirement requirement)
    {
        return SubmissionFiles.findDocumentRequirement(submission, requirement.getDocumentRequirementName());
    }

    private static File getFile(final Submission submission, final ValidationRequirement requirement)
    {
        return SubmissionFiles.getCurrentFile(submission, requirement.getDocumentRequirementName());
    }

    /** The validation's {@code yes}, {@code no} or {@code unsure}, or {@code null} before the model was asked. */
    private static String getVerdict(final Submission submission, final ValidationRequirement requirement)
    {
        final Validation validation = submission.findValidation(requirement);
        return validation == null ? null : validation.getOutcome();
    }

    private static boolean isRunning(final Resource submission)
    {
        return ExtractionStatus.STATUS_RUNNING.equals(
            submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class));
    }

    /**
     * Saves the verdict through the {@code validated} system workflow.
     *
     * @param last whether this is the job's last verdict, so the claim is released with it
     * @return {@code false} when the reading is no longer this job's, so the verdict was dropped
     * @throws WorkflowException when the engine refuses the verdict for another reason
     */
    private boolean recordVerdict(final Resource submission, final String jobId,
        final ValidationRequirement requirement, final File file, final FieldResult verdict, final boolean last)
        throws WorkflowException
    {
        try {
            this.engine.receiveEvent(submission, new WorkflowEvent(VALIDATED_EVENT, Map.of(
                ClaimReadingHandler.JOB_KEY, jobId, RecordValidationHandler.REQUIREMENT_KEY, requirement.getPath(),
                RecordValidationHandler.FILE_KEY, file.getPath(), RecordValidationHandler.RESULT_KEY, verdict,
                RecordValidationHandler.LAST_KEY, last)));
            return true;
        } catch (final InvalidStateException e) {
            // A Stop or a Read again took the reading while the model was answering
            LOGGER.info("Dropping the verdict on {}: {}", submission.getPath(), e.getMessage());
            return false;
        }
    }

    /**
     * Completes the task the intake waits at, so the intake goes on. When that cannot happen, the reading is given
     * up if it is still running, since nothing else would end it.
     *
     * @return {@code true} when the intake took the verdict
     */
    private boolean handOver(final Resource submission, final String jobId)
    {
        final Resource task = findWaitingTask(submission);
        if (task == null) {
            LOGGER.warn("No workflow waits for the validation of {}", submission.getPath());
            // The schema's intake workflow is missing or does not wait for the job, which only an administrator
            // can fix
            ErrorLogger.logProblem("No workflow waits for a submission's validation",
                ErrorContext.of(ValidationJobConsumer.class, "findWaitingTask").about(submission));
            giveUpIfRunning(submission, jobId, ReadingJobs.NOT_SET_UP_MESSAGE);
            return false;
        }
        try {
            this.engine.receiveEvent(task, new WorkflowEvent(COMPLETE_EVENT, Map.of()));
            return true;
        } catch (final WorkflowException | RuntimeException e) {
            LOGGER.error("Could not carry the intake of {} on: {}", submission.getPath(), e.getMessage(), e);
            // The verdict is saved, but the intake is stuck at its task, and nothing else will say why
            ErrorLogger.logError(e, ErrorContext.of(ValidationJobConsumer.class, "completeTask").about(task));
            giveUpIfRunning(submission, jobId, ReadingJobs.COULD_NOT_READ_MESSAGE);
            return false;
        }
    }

    /** The open task of the submission's workflows that this job's user performs, or {@code null}. */
    private static Resource findWaitingTask(final Resource submission)
    {
        final ResourceResolver resolver = submission.getResourceResolver();
        final Resource instances = submission.getChild(WorkflowInstances.NODE_NAME);
        if (instances == null) {
            return null;
        }
        final String user = resolver.getUserID();
        return Objects.requireNonNull(instances.adaptTo(WorkflowInstances.class),
            "The workflow instances node reads as one").getInstances().stream()
            .flatMap(instance -> instance.getTaskInstances().stream())
            .filter(task -> TaskInstance.OPEN_STATUS.equals(task.getStatus()) && task.getPerformers().contains(user))
            .findFirst()
            .map(task -> resolver.getResource(task.getPath()))
            .orElse(null);
    }

    /**
     * Gives the reading up unless a verdict already ended it. A no ends it as done; a yes or an unsure leaves it
     * running for the intake's next step.
     */
    private void giveUpIfRunning(final Resource submission, final String jobId, final String reason)
    {
        final ResourceResolver resolver = submission.getResourceResolver();
        // The verdict was saved through the engine's session, which this one sees only after a refresh
        resolver.refresh();
        final Resource current = resolver.getResource(submission.getPath());
        if (current != null && isRunning(current)) {
            // The claim may be this job's still, or released with the last verdict: either way it is not another's
            this.jobs.giveUp(current, jobId, reason, false);
        }
    }
}

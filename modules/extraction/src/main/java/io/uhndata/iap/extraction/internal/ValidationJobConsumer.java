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
import io.uhndata.iap.schemas.models.ValidationRequirement;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowInstances;

/**
 * Asks the model whether an upload is what the request needs, such as a research proposal, as a background job.
 * The question comes from the schema's validation requirement: its prompt, and its template as reference text.
 *
 * <p>The job fires {@code validated} with the model's answer, and that system workflow saves it on the submission
 * ({@link RecordValidationHandler}). Then it completes the task the submission's intake workflow waits at, so the
 * intake's gateway reads the verdict through the {@code validation} operand and goes on, or ends. The intake waits
 * at a task because the engine cannot yet wake a workflow with a named event.</p>
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
     * Why the validation cannot start: the schema has no validation, or its document is missing or unreadable.
     *
     * @param resolver this job's session
     * @param submission the submission to read
     * @return the message for the submitter, or {@code null} when the validation's document is parsed
     */
    @Override
    public String findReasonNotToStart(final ResourceResolver resolver, final Submission submission)
    {
        final ValidationRequirement requirement = findRequirement(submission);
        if (requirement == null) {
            return ReadingJobs.NOT_SET_UP_MESSAGE;
        }
        final File file = SubmissionFiles.getCurrentFile(submission, requirement.getDocumentRequirementName());
        if (file == null) {
            return SubmissionFiles.NO_DOCUMENT_MESSAGE;
        }
        // Every parse has settled by now, so one that is not completed failed or was stopped
        return ParsePropertyNames.STATUS_COMPLETED.equals(file.getParseStatus()) ? null
            : SubmissionFiles.describeUnreadable();
    }

    @Override
    public void read(final Resource submission, final String jobId) throws WorkflowException
    {
        final Submission model = SubmissionFiles.getSubmission(submission);
        final ValidationRequirement requirement = findRequirement(model);
        final File file = SubmissionFiles.getCurrentFile(model, requirement.getDocumentRequirementName());
        final IntakeResult result = this.reader.ask(submission.getPath(), file, requirement,
            ExtractionField.createYesNo(VERDICT_KEY, requirement.getLabel(), requirement.getPrompt()));
        if (result.status() == AnswerIntakeService.Status.NOT_READ) {
            this.jobs.giveUp(submission, jobId, result.message(), true);
            return;
        }
        final FieldResult verdict = result.fields().getOrDefault(VERDICT_KEY, FieldResult.createNotFound(VERDICT_KEY));
        if (recordVerdict(submission, jobId, requirement, file, verdict)) {
            handOver(submission, jobId);
        }
    }

    /** The schema's validation, when it has one that applies and names the document it reads. */
    private static ValidationRequirement findRequirement(final Submission submission)
    {
        return submission.getSchemaVersion().getRequirements().stream()
            .filter(ValidationRequirement.class::isInstance)
            .map(ValidationRequirement.class::cast)
            .filter(requirement -> requirement.getDocumentRequirementName() != null
                && submission.isApplicable(requirement))
            .findFirst()
            .orElse(null);
    }

    /**
     * Saves the verdict through the {@code validated} system workflow.
     *
     * @return {@code false} when the reading is no longer this job's, so the verdict was dropped
     * @throws WorkflowException when the engine refuses the verdict for another reason
     */
    private boolean recordVerdict(final Resource submission, final String jobId,
        final ValidationRequirement requirement, final File file, final FieldResult verdict) throws WorkflowException
    {
        try {
            this.engine.receiveEvent(submission, new WorkflowEvent(VALIDATED_EVENT, Map.of(
                ClaimReadingHandler.JOB_KEY, jobId, RecordValidationHandler.REQUIREMENT_KEY, requirement.getPath(),
                RecordValidationHandler.FILE_KEY, file.getPath(), RecordValidationHandler.RESULT_KEY, verdict)));
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
     */
    private void handOver(final Resource submission, final String jobId)
    {
        final Resource task = findWaitingTask(submission);
        if (task == null) {
            LOGGER.warn("No workflow waits for the validation of {}", submission.getPath());
            // The schema's intake workflow is missing or does not wait for the job, which only an administrator
            // can fix
            ErrorLogger.logProblem("No workflow waits for a submission's validation",
                ErrorContext.of(ValidationJobConsumer.class, "findWaitingTask").about(submission));
            giveUpIfRunning(submission, jobId, ReadingJobs.NOT_SET_UP_MESSAGE);
            return;
        }
        try {
            this.engine.receiveEvent(task, new WorkflowEvent(COMPLETE_EVENT, Map.of()));
        } catch (final WorkflowException | RuntimeException e) {
            LOGGER.error("Could not carry the intake of {} on: {}", submission.getPath(), e.getMessage(), e);
            // The verdict is saved, but the intake is stuck at its task, and nothing else will say why
            ErrorLogger.logError(e, ErrorContext.of(ValidationJobConsumer.class, "completeTask").about(task));
            giveUpIfRunning(submission, jobId, ReadingJobs.COULD_NOT_READ_MESSAGE);
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
     * Gives the reading up unless the verdict already ended it. A no ends it as done; a yes or an unsure leaves it
     * running for the intake's next job.
     */
    private void giveUpIfRunning(final Resource submission, final String jobId, final String reason)
    {
        final ResourceResolver resolver = submission.getResourceResolver();
        // The verdict was saved through the engine's session, which this one sees only after a refresh
        resolver.refresh();
        final Resource current = resolver.getResource(submission.getPath());
        if (current != null && ExtractionStatus.STATUS_RUNNING.equals(
            current.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class))) {
            // The verdict released the claim, so this gives up a reading nobody holds
            this.jobs.giveUp(current, jobId, reason, false);
        }
    }
}

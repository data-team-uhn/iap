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
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.extraction.internal.AnswerIntakeService.IntakeResult;
import io.uhndata.iap.schemas.models.ValidationRequirement;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowInstances;

/**
 * Asks the model whether an uploaded document is what its requirement asks for, such as a research proposal, and
 * hands the verdict to the intake. One step of {@link DecisionJobConsumer}.
 *
 * <p>It fires {@code validated} with the model's answer, and that system workflow saves it on the submission
 * ({@link RecordValidationHandler}). Then it completes the task the submission's intake workflow waits at, so the
 * intake's gateway reads the verdict through the {@code validation} operand and goes on, or ends. The intake waits
 * at a task because no engine event wakes a waiting workflow.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = DocumentValidator.class)
public class DocumentValidator
{
    /** The event that saves the verdict. */
    static final String VALIDATED_EVENT = "validated";

    /** The event that completes the task the intake waits at. */
    static final String COMPLETE_EVENT = "complete";

    /** The key the model's verdict comes back under. */
    static final String VERDICT_KEY = "verdict";

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentValidator.class);

    @Reference
    private ReadingJobs jobs;

    @Reference
    private WorkflowEngine engine;

    @Reference
    private RequirementReader reader;

    /**
     * Asks one validation, saves the verdict and carries the intake on.
     *
     * @param submission the submission being read, through the job's session
     * @param jobId the job's id, which the claim holds
     * @param requirement the validation to ask
     * @param file the parsed upload it reads
     * @return {@code false} when the job must stop: the reading was given up, or is no longer this job's
     * @throws WorkflowException when the engine refuses the verdict for a reason other than a lost claim
     */
    boolean validate(final Resource submission, final String jobId, final ValidationRequirement requirement,
        final File file) throws WorkflowException
    {
        final IntakeResult result = this.reader.ask(submission.getPath(), RequirementReader.Request.of(requirement,
            file, ExtractionField.createYesNo(VERDICT_KEY, requirement.getLabel(), requirement.getPrompt())));
        if (result.status() == AnswerIntakeService.Status.NOT_READ) {
            this.jobs.giveUp(submission, jobId, result.message(), true);
            return false;
        }
        final FieldResult verdict = result.fields().getOrDefault(VERDICT_KEY, FieldResult.createNotFound(VERDICT_KEY));
        return recordVerdict(submission, jobId, requirement, file, verdict) && handOver(submission, jobId);
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
        return this.jobs.sendOutcome(submission, new WorkflowEvent(VALIDATED_EVENT, Map.of(
            ReadingEvents.JOB_KEY, jobId, ReadingEvents.REQUIREMENT_KEY, requirement.getPath(),
            ReadingEvents.FILE_KEY, file.getPath(), ReadingEvents.RESULT_KEY, verdict)));
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
                ErrorContext.of(DocumentValidator.class, "findWaitingTask").about(submission));
            giveUpIfRunning(submission, jobId, ReadingJobs.NOT_SET_UP_MESSAGE);
            return false;
        }
        try {
            this.engine.receiveEvent(task, new WorkflowEvent(COMPLETE_EVENT, Map.of()));
            return true;
        } catch (final WorkflowException | RuntimeException e) {
            LOGGER.error("Could not carry the intake of {} on: {}", submission.getPath(), e.getMessage(), e);
            // The verdict is saved, but the intake is stuck at its task, and nothing else will say why
            ErrorLogger.logError(e, ErrorContext.of(DocumentValidator.class, "completeTask").about(task));
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
     * running for the job's next step.
     */
    private void giveUpIfRunning(final Resource submission, final String jobId, final String reason)
    {
        final ResourceResolver resolver = submission.getResourceResolver();
        // The verdict was saved through the engine's session, which this one sees only after a refresh
        resolver.refresh();
        final Resource current = resolver.getResource(submission.getPath());
        if (current != null && ExtractionStatus.isRunning(current)) {
            // The claim is this job's still: a yes or an unsure keeps it
            this.jobs.giveUp(current, jobId, reason, false);
        }
    }
}

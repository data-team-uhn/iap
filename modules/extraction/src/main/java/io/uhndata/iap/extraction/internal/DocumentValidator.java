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

import org.apache.sling.api.resource.Resource;
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

/**
 * Asks the model whether an uploaded document is what its requirement asks for, such as a research proposal, and
 * hands the verdict to the intake. One step of {@link DecisionJobConsumer}.
 *
 * <p>It fires {@code validated} with the model's answer, and that system workflow saves it on the submission
 * ({@link RecordValidationHandler}). Then it completes the task the submission's intake workflow waits at
 * ({@link IntakeTasks}), so the intake's gateway reads the verdict through the
 * {@code validation} operand and goes on, or ends. The intake waits at a task, not a message catch, because it was
 * written before the engine delivered messages to a waiting workflow. A verdict whose task is still open counts as not made ({@link DecisionChain}), so a hand-over
 * lost to a failure or a restart is done by the next job, a {@code no} too: the request counts as rejected only once
 * the intake took it.</p>
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

    @Reference
    private DecisionChain chain;

    private final IntakeTasks intakeTasks = new IntakeTasks();

    /**
     * Asks one validation, saves the verdict and carries the intake on.
     *
     * @param submission the submission being read, through the job's session
     * @param jobId the job's id, which the claim holds
     * @param requirement the validation to ask
     * @param file the parsed upload it reads
     * @throws ReadingEndedException when the job must stop: the reading was given up, or is no longer this job's
     * @throws WorkflowException when the engine refuses the verdict for a reason other than a lost claim
     */
    void validate(final Resource submission, final String jobId, final ValidationRequirement requirement,
        final File file) throws ReadingEndedException, WorkflowException
    {
        // A verdict saved before its hand-over was lost is handed over without asking the model again
        if (!this.chain.hasVerdict(SubmissionFiles.getSubmission(submission), requirement)) {
            askAndRecordVerdict(submission, jobId, requirement, file);
        }
        handOver(submission, jobId, requirement);
    }

    /**
     * Asks the model for the verdict and saves it.
     *
     * @throws ReadingEndedException when the job must stop: the reading was given up, or is no longer this job's
     */
    private void askAndRecordVerdict(final Resource submission, final String jobId,
        final ValidationRequirement requirement, final File file) throws ReadingEndedException, WorkflowException
    {
        final IntakeResult result = this.reader.ask(submission.getPath(), RequirementReader.Request.of(requirement,
            file, ExtractionField.createYesNo(VERDICT_KEY, requirement.getLabel(), requirement.getPrompt())));
        if (result.status() == AnswerIntakeService.Status.NOT_READ) {
            this.jobs.giveUpOwnReading(submission, jobId, result.message());
            throw new ReadingEndedException();
        }
        final FieldResult verdict = result.fields().getOrDefault(VERDICT_KEY, FieldResult.createNotFound(VERDICT_KEY));
        this.jobs.sendOutcome(submission, new WorkflowEvent(VALIDATED_EVENT, Map.of(
            ReadingEvents.JOB_KEY, jobId, ReadingEvents.REQUIREMENT_KEY, requirement.getPath(),
            ReadingEvents.FILE_KEY, file.getPath(), ReadingEvents.RESULT_KEY, verdict)));
    }

    /**
     * Completes the task the intake waits at, so the intake goes on. When that cannot happen, the reading is given
     * up if it is still running, since nothing else would end it.
     *
     * @throws ReadingEndedException when the intake could not take the verdict, so the reading was given up
     */
    private void handOver(final Resource submission, final String jobId, final ValidationRequirement requirement)
        throws ReadingEndedException
    {
        final Resource task = findWaitingTask(submission, requirement);
        if (task == null) {
            LOGGER.warn("No workflow waits for the validation of {}", submission.getPath());
            // The schema's intake workflow is missing or does not wait for the job, which only an administrator
            // can fix
            ErrorLogger.logProblem("No workflow waits for a submission's validation",
                ErrorContext.of(DocumentValidator.class, "findWaitingTask").about(submission));
            giveUpIfRunning(submission, jobId, ReadingJobs.NOT_SET_UP_MESSAGE);
            throw new ReadingEndedException();
        }
        try {
            this.engine.receiveEvent(task, new WorkflowEvent(COMPLETE_EVENT, Map.of()));
        } catch (final WorkflowException | RuntimeException e) {
            LOGGER.error("Could not carry the intake of {} on: {}", submission.getPath(), e.getMessage(), e);
            // The verdict is saved, but the intake is stuck at its task, and nothing else will say why
            ErrorLogger.logError(e, ErrorContext.of(DocumentValidator.class, "completeTask").about(task));
            giveUpIfRunning(submission, jobId, ReadingJobs.COULD_NOT_READ_MESSAGE);
            throw new ReadingEndedException();
        }
    }

    /** The open task that waits for this validation, through the job's session, or {@code null}. */
    private Resource findWaitingTask(final Resource submission, final ValidationRequirement requirement)
    {
        final TaskInstance task =
            this.intakeTasks.findWaitingTask(SubmissionFiles.getSubmission(submission), requirement);
        return task == null ? null : submission.getResourceResolver().getResource(task.getPath());
    }

    /** Gives the reading up unless it ended meanwhile, such as by a Stop. */
    private void giveUpIfRunning(final Resource submission, final String jobId, final String reason)
    {
        final Resource current = this.jobs.findStillRunning(submission);
        if (current != null) {
            this.jobs.giveUpOwnReading(current, jobId, reason);
        }
    }
}

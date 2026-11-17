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

import java.util.Set;
import java.util.stream.Collectors;

import io.uhndata.iap.schemas.models.ValidationRequirement;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.models.TaskInstance;

/**
 * Finds the tasks an intake workflow holds for validation verdicts. By contract, such a task's definition id is the
 * validation's name and the reading jobs' user performs it, so a person's task of the same name, in another workflow
 * of the submission, is never taken for it.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class IntakeTasks
{
    /** The user the reading jobs act as, and so a performer of every task they complete. */
    static final String READER_USER = "iap-extraction";

    /**
     * The open task that waits for a validation's verdict.
     *
     * @param submission the submission
     * @param validation the validation
     * @return the task, or {@code null} when no workflow of the submission waits for it now
     */
    TaskInstance findWaitingTask(final Submission submission, final ValidationRequirement validation)
    {
        return submission.getOpenTasks().stream()
            .filter(task -> validation.getName().equals(task.getTaskDefinitionId()) && isIntakeTask(task))
            .findFirst()
            .orElse(null);
    }

    /**
     * The validations whose verdict an intake took: an intake task named after each was completed. Only a completed
     * task counts, so an intake that has not reached its task yet has taken nothing, and one that waits at the same
     * task again still took the first verdict.
     *
     * @param submission the submission
     * @return the names of the validations handed over
     */
    Set<String> getHandedOverNames(final Submission submission)
    {
        return submission.getWorkflowInstances().stream()
            .flatMap(instance -> instance.getTaskInstances().stream())
            .filter(task -> TaskInstance.COMPLETED_STATUS.equals(task.getStatus()) && isIntakeTask(task))
            .map(TaskInstance::getTaskDefinitionId)
            .collect(Collectors.toSet());
    }

    private static boolean isIntakeTask(final TaskInstance task)
    {
        return task.getPerformers().contains(READER_USER);
    }
}

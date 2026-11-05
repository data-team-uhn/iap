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
package io.uhndata.iap.workflows.internal;

import java.util.Objects;

import org.apache.sling.api.resource.Resource;

import io.uhndata.iap.history.api.RecordedAction;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.models.FlowNode;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowInstance;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.spi.Payloads;

/**
 * What caused a delivery, as its history records it: who asked for what, and what handled it.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class DeliveryCauses
{
    private DeliveryCauses()
    {
        // Static methods only
    }

    /**
     * The cause of an event that a system workflow handled.
     *
     * @param actor the user who sent the event
     * @param event the event
     * @param start the start event of the system workflow that caught it
     * @param startedInstance the path of the content workflow instance the delivery started, {@code null} if none
     * @return the cause, to which the delivery adds what its steps did
     */
    static RecordedAction.Builder ofSystemWorkflow(final String actor, final WorkflowEvent event, final FlowNode start,
        final String startedInstance)
    {
        final WorkflowVersion version = start.getWorkflowVersion();
        return RecordedAction.by(actor, event.getName())
            .event(event.getName())
            .workflow(startedInstance, version == null ? null : version.getIdentifier())
            .activity(start.getElementId(), start.getLabel());
    }

    /**
     * The cause of an event that completed a task of a running instance.
     *
     * @param actor the user who sent the event
     * @param event the event, carrying the decision taken on the task if it offers a choice
     * @param task the task the event completed, as the event left it
     * @return the cause, to which the delivery adds what its steps did
     */
    static RecordedAction.Builder ofTask(final String actor, final WorkflowEvent event, final Resource task)
    {
        final TaskInstance instanceTask = Objects.requireNonNull(task.adaptTo(TaskInstance.class),
            "A task the engine resumes is a task");
        final WorkflowInstance instance = Objects.requireNonNull(instanceTask.getWorkflowInstance(),
            "A task the engine resumes belongs to an instance");
        return RecordedAction.by(actor, event.getName())
            .event(event.getName())
            .workflow(instance.getPath(), instance.getWorkflowVersionIdentifier())
            .activity(instanceTask.getTaskDefinitionId(), instanceTask.getLabel())
            .task(task.getPath(), Payloads.text(event, TaskCompletion.OUTCOME_PARAMETER),
                Payloads.text(event, TaskCompletion.OUTCOME_NOTE_PARAMETER));
    }
}

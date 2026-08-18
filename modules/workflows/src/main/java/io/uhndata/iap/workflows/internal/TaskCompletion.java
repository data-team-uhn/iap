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
import java.util.Set;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.NoApplicableWorkflowException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.spi.Payloads;

/**
 * Completes a user task: records what the person decided, and carries their instance on from there.
 *
 * <p>Authorization is the same mechanism as everywhere else, one step later in the process: the task's
 * <em>defining activity</em> names the principals who may complete it, and {@link PerformerCheck} asks that node
 * exactly as it asks a start event who may fire it — including about the resource being worked on, which a task
 * that comes back to whoever raised it is answered by. Being able to see a task and being allowed to decide it are
 * separate questions, and this is where the second is answered.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class TaskCompletion
{
    /** The domain event that completes a user task. */
    static final String COMPLETE_EVENT = "complete";

    /** The payload entry carrying the person's decision. */
    static final String OUTCOME_PARAMETER = "outcome";

    /** The status a task carries until somebody completes it. */
    private static final String OPEN_STATUS = "created";

    private TaskCompletion()
    {
    }

    /**
     * The events the actor could send to the task right now: completing it, while it is open and its definition
     * admits them.
     *
     * @param taskResource the task, resolved through the engine's session
     * @param performers who is asking
     * @return {@code complete}, or nothing
     * @throws WorkflowFailedException when the actor's group membership cannot be read
     */
    static Set<String> availableEvents(final Resource taskResource, final PerformerCheck performers)
        throws WorkflowFailedException
    {
        final TaskInstance task = Objects.requireNonNull(taskResource.adaptTo(TaskInstance.class),
            "A wf:TaskInstance resource always adapts to its model");
        final Activity definition = task.getDefinition();
        return OPEN_STATUS.equals(task.getStatus()) && definition != null && performers.admits(definition)
            ? Set.of(COMPLETE_EVENT) : Set.of();
    }

    /**
     * Completes the task the event was aimed at.
     *
     * @param resolver the engine's own session
     * @param taskResource the task being completed
     * @param event the incoming event
     * @param actor the user completing it
     * @param performer how the resumed instance performs any service task it meets
     * @param conditions the evaluator the resumed instance's gateways are asked of
     * @throws WorkflowException when the event does not apply, the actor may not complete it, a decision arrives
     *             without an outcome, or the definition cannot be run on from here
     * @throws PersistenceException when the instance cannot be written
     */
    static void apply(final ResourceResolver resolver, final Resource taskResource, final WorkflowEvent event,
        final String actor, final InstanceRunner.ServiceTaskPerformer performer,
        final ConditionEvaluator conditions) throws WorkflowException, PersistenceException
    {
        if (!COMPLETE_EVENT.equals(event.getName())) {
            throw new NoApplicableWorkflowException("A task has nothing waiting for a " + event.getName()
                + " event; the only thing that can happen to one is being completed");
        }
        final TaskInstance task = Objects.requireNonNull(taskResource.adaptTo(TaskInstance.class),
            "A wf:TaskInstance resource always adapts to its model");
        if (!OPEN_STATUS.equals(task.getStatus())) {
            throw new NoApplicableWorkflowException("The task " + task.getPath() + " is already " + task.getStatus()
                + ", so there is nothing left to decide");
        }
        final Activity definition = task.getDefinition();
        if (definition == null) {
            throw new WorkflowDefinitionException("The task " + task.getPath()
                + " no longer has a definition, so who may complete it cannot be established");
        }
        PerformerCheck.verify(resolver, InstanceRunner.hostOf(Objects.requireNonNull(taskResource.getParent(),
            "A task always lives inside its instance")), definition, actor);

        final String outcome = Payloads.text(event, OUTCOME_PARAMETER);
        // A decision left blank would let the next gateway route on whatever an earlier task decided
        if (outcome == null && !definition.getOutcomes().isEmpty()) {
            throw new InvalidPayloadException("Completing " + task.getPath() + " takes one of its outcomes: "
                + String.join(", ", definition.getOutcomes()));
        }
        new InstanceRunner(resolver, performer, actor, conditions).complete(task, outcome);
    }
}

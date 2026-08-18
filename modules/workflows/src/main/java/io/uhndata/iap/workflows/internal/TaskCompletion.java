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

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.workflows.api.NoApplicableWorkflowException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.IntermediateCatchingEvent;
import io.uhndata.iap.workflows.models.TaskInstance;

/**
 * What a {@code complete} event aimed at a user task does: the person's decision is recorded and their instance
 * carries on from there.
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
    static final String OUTCOME = "outcome";

    /** The domain event a passed deadline delivers, which the clock fires and no user can. */
    static final String TIMEOUT_EVENT = "timeout";

    /** The status a task carries until somebody completes it. */
    private static final String OPEN = "created";

    private TaskCompletion()
    {
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
     * @throws WorkflowException when the event does not apply, the actor may not complete this, or the definition
     *             cannot be run on from here
     * @throws PersistenceException when the instance cannot be written
     */
    static void apply(final ResourceResolver resolver, final Resource taskResource, final WorkflowEvent event,
        final String actor, final InstanceRunner.ServiceTaskPerformer performer,
        final ConditionEvaluator conditions) throws WorkflowException, PersistenceException
    {
        if (!COMPLETE_EVENT.equals(event.getName()) && !TIMEOUT_EVENT.equals(event.getName())) {
            throw new NoApplicableWorkflowException("A task has nothing waiting for a " + event.getName()
                + " event; the only things that can happen to one are being completed and running out of time");
        }
        final TaskInstance task = Objects.requireNonNull(taskResource.adaptTo(TaskInstance.class),
            "A wf:TaskInstance resource always adapts to its model");
        if (!OPEN.equals(task.getStatus())) {
            throw new NoApplicableWorkflowException("The task " + task.getPath() + " is already " + task.getStatus()
                + ", so there is nothing left to decide");
        }
        final Activity definition = task.getDefinition();
        if (definition == null) {
            throw new WorkflowDefinitionException("The task " + task.getPath()
                + " no longer has a definition, so who may complete it cannot be established");
        }
        if (TIMEOUT_EVENT.equals(event.getName())) {
            expire(resolver, task, definition, performer, conditions);
            return;
        }
        PerformerCheck.verify(resolver, InstanceRunner.hostOf(Objects.requireNonNull(taskResource.getParent(),
            "A task always lives inside its instance")), definition, actor);

        final Object outcome = event.get(OUTCOME);
        new InstanceRunner(resolver, performer, actor, conditions)
            .complete(task, outcome instanceof String ? (String) outcome : null);
    }

    /**
     * Runs out this task's clock: the boundary event its deadline belongs to fires, the task is cancelled, and
     * execution leaves down the timer's arc.
     *
     * <p>No performer check, deliberately. {@code performers} says who may make execution pass through a node, and
     * a timer is passed through by time — there is nobody to check, and refusing the clock because it belongs to no
     * group would leave the instance parked on a task that can never now be done.</p>
     *
     * @param resolver the engine's own session
     * @param task the task whose deadline has passed
     * @param definition the activity the task was raised from
     * @param performer how the resumed instance performs any service task it meets
     * @param conditions the evaluator the resumed instance's gateways are asked of
     * @throws WorkflowException when nothing is counting down to this task, or the run cannot continue
     * @throws PersistenceException when the instance cannot be written
     */
    private static void expire(final ResourceResolver resolver, final TaskInstance task, final Activity definition,
        final InstanceRunner.ServiceTaskPerformer performer, final ConditionEvaluator conditions)
        throws WorkflowException, PersistenceException
    {
        final IntermediateCatchingEvent timer = definition.getBoundaryEvents().stream()
            .filter(event -> event.getElementId().equals(task.getDueEventId()))
            .findFirst()
            .orElseThrow(() -> new NoApplicableWorkflowException("The task " + task.getPath()
                + " has no deadline to run out: nothing is counting down to it"));
        new InstanceRunner(resolver, performer, task.getAssignee(), conditions).expire(task, timer);
    }
}

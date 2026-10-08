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

import java.util.Calendar;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.NoApplicableWorkflowException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.IntermediateCatchingEvent;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowInstance;
import io.uhndata.iap.workflows.spi.Payloads;

/**
 * Ends a user task and carries its instance on from there. A person completes it with a decision, or its deadline
 * runs out.
 *
 * <p>Completing is authorized by the same mechanism as everything else, asked one step later. The task's defining
 * activity names the principals who may complete it, and {@link PerformerCheck} asks that node exactly as it asks a
 * start event who may fire it. Seeing a task and being allowed to decide it are separate questions. This asks the
 * second.</p>
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

    /** The event a passed deadline delivers. */
    static final String TIMEOUT_EVENT = "timeout";

    private static final String WALK_ID_PROPERTY = "walkId";

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
        return TaskInstance.OPEN_STATUS.equals(task.getStatus()) && definition != null
            && performers.admits(definition) ? Set.of(COMPLETE_EVENT) : Set.of();
    }

    /**
     * Completes the task the event was aimed at, or times it out.
     *
     * @param resolver the engine's own session
     * @param taskResource the task the event is aimed at
     * @param event the incoming event
     * @param actor the user who sent the event
     * @param performer how the resumed instance performs any service task it meets
     * @param conditions the evaluator for the resumed instance's gateway guards
     * @throws WorkflowException when the event does not apply, the actor may not complete it, a decision arrives
     *             without an outcome, or the definition cannot be run on from here
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
        if (!TaskInstance.OPEN_STATUS.equals(task.getStatus())) {
            throw new NoApplicableWorkflowException("The task " + task.getPath() + " is already " + task.getStatus()
                + ", so there is nothing left to decide");
        }
        final Activity definition = task.getDefinition();
        if (definition == null) {
            throw new WorkflowDefinitionException("The task " + task.getPath()
                + " no longer has a definition, so the instance cannot be carried on from it");
        }
        stamp(resolver, task);
        if (TIMEOUT_EVENT.equals(event.getName())) {
            expire(resolver, task, definition, performer, conditions);
            return;
        }
        PerformerCheck.verify(resolver, definition, actor);

        final String outcome = Payloads.text(event, OUTCOME_PARAMETER);
        // A decision left blank would let the next gateway route on whatever an earlier task decided
        if (outcome == null && !definition.getOutcomes().isEmpty()) {
            throw new InvalidPayloadException("Completing " + task.getPath() + " takes one of its outcomes: "
                + String.join(", ", definition.getOutcomes()));
        }
        new InstanceRunner(resolver, performer, actor, new FlowRouting(conditions)).complete(task, outcome);
    }

    /**
     * Stamps the task's instance with a new identifier. Two events carrying one instance on at once then both change
     * the same property, and the second to commit is refused rather than merged. Otherwise two branches arriving at a
     * join in two requests would each see only themselves arrive, and both would wait for good.
     *
     * @param resolver the engine's own session
     * @param task the task the event is aimed at
     */
    private static void stamp(final ResourceResolver resolver, final TaskInstance task)
    {
        final WorkflowInstance instance = Objects.requireNonNull(task.getWorkflowInstance(),
            "A task always lives inside its instance");
        final ModifiableValueMap properties = Objects.requireNonNull(Objects.requireNonNull(
            resolver.getResource(instance.getPath()), "The engine's own session can always see an instance")
            .adaptTo(ModifiableValueMap.class), "The engine's own session can always write an instance");
        properties.put(WALK_ID_PROPERTY, UUID.randomUUID().toString());
    }

    /**
     * Times out this task by firing the boundary timer its deadline belongs to.
     *
     * <p>There is no performer check. {@code performers} says who may complete a task, and nobody completes a
     * timeout. Checking would leave the instance stuck on a task nobody can finish.</p>
     *
     * <p>The deadline is the check instead. Any channel can deliver an event, so a timeout that arrives early is
     * refused, whoever sends it. After the deadline, a timeout from anywhere only does sooner what the next sweep
     * would do.</p>
     *
     * @param resolver the engine's own session
     * @param task the task whose deadline has passed
     * @param definition the activity the task was raised from
     * @param performer how the resumed instance performs any service task it meets
     * @param conditions the evaluator for the resumed instance's gateway guards
     * @throws WorkflowException when nothing is counting down to this task, its deadline has not passed yet, or the
     *     run cannot continue
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
        final Calendar due = task.getDueDate();
        if (due == null || due.after(Calendar.getInstance())) {
            throw new InvalidStateException("The task " + task.getPath() + " has not run out of time yet");
        }
        new InstanceRunner(resolver, performer, task.getAssignee(), new FlowRouting(conditions)).expire(task, timer);
    }
}

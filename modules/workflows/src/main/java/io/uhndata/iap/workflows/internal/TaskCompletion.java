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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.NoApplicableWorkflowException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.FlowNode;
import io.uhndata.iap.workflows.models.IntermediateCatchingEvent;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.spi.Payloads;

/**
 * Ends a task and carries its instance on from there. A person completes a user task with a decision, or its
 * deadline runs out. A task a catching event waits on ends when the message it waits for arrives.
 *
 * <p>Completing is authorized by the same mechanism as everything else, asked one step later. The node a task was
 * raised from names who may complete it. {@link PerformerCheck} asks that node exactly as it asks a start event who
 * may fire it. Seeing a task and being allowed to complete it are separate questions. This asks the second.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = TaskCompletion.class)
public class TaskCompletion
{
    /** The domain event that completes a user task. */
    static final String COMPLETE_EVENT = "complete";

    /** The payload entry carrying the person's decision. */
    static final String OUTCOME_PARAMETER = "outcome";

    /** The payload entry carrying what the person deciding said about it. */
    static final String OUTCOME_NOTE_PARAMETER = "outcomeNote";

    /** The event a passed deadline delivers. */
    static final String TIMEOUT_EVENT = "timeout";

    private final Performers performers;

    private final InstanceRunners runners;

    /**
     * Constructor.
     *
     * @param performers says who the names in a task's definition stand for
     * @param runners hands out the runner that carries the instance on
     */
    @Activate
    public TaskCompletion(@Reference final Performers performers, @Reference final InstanceRunners runners)
    {
        this.performers = performers;
        this.runners = runners;
    }

    /**
     * The events the actor could send to the task right now: the one that completes it, while it is open and its
     * definition admits them.
     *
     * @param taskResource the task, resolved through the engine's session
     * @param check whom the definitions admit, for the asking user
     * @return {@code complete} for a user task, the message for a task a catching event waits on, or nothing
     * @throws WorkflowFailedException when the actor's group membership cannot be read
     */
    static Set<String> availableEvents(final Resource taskResource, final PerformerCheck check)
        throws WorkflowFailedException
    {
        final TaskInstance task = Objects.requireNonNull(taskResource.adaptTo(TaskInstance.class),
            "A wf:TaskInstance resource always adapts to its model");
        final FlowNode definition = task.getDefinition();
        return TaskInstance.OPEN_STATUS.equals(task.getStatus()) && definition != null
            && check.admits(definition, findHost(taskResource)) ? Set.of(findCompletingEvent(definition)) : Set.of();
    }

    /**
     * Completes the task the event resumes, or times it out.
     *
     * @param resolver the engine's own session
     * @param taskResource the task the event resumes
     * @param event the incoming event
     * @param actor the user who sent the event
     * @param performer how the resumed instance handles any service task or message it meets
     * @throws WorkflowException when the event does not apply, the actor may not complete it, the outcome is missing
     *             or not one the task offers, or the definition cannot be run on from here
     * @throws PersistenceException when the instance cannot be written
     */
    void apply(final ResourceResolver resolver, final Resource taskResource, final WorkflowEvent event,
        final String actor, final InstanceRunner.StepPerformer performer)
        throws WorkflowException, PersistenceException
    {
        final TaskInstance task = readOpenTask(taskResource);
        final FlowNode definition = readDefinition(task, event);
        final Completion completion = new Completion(task, findHost(taskResource),
            this.performers.createCheck(resolver, actor), this.runners.createRunner(resolver, performer, actor));
        if (definition instanceof IntermediateCatchingEvent) {
            completion.receive(event, (IntermediateCatchingEvent) definition);
        } else {
            completion.decide(event, (Activity) definition);
        }
    }

    /**
     * The event that completes a task: {@code complete} for a user task, and for a task a catching event waits on,
     * the message it waits for.
     *
     * @param definition the node the task was raised from
     * @return the event's name
     */
    private static String findCompletingEvent(final FlowNode definition)
    {
        return definition instanceof IntermediateCatchingEvent
            ? ((IntermediateCatchingEvent) definition).getMessageName() : COMPLETE_EVENT;
    }

    /**
     * What can happen to an open user task, and the only events it waits for.
     *
     * @version $Id$
     * @since 0.1.0
     */
    private enum TaskEvent
    {
        /** A person completes it. */
        COMPLETE,

        /** Its deadline runs out. */
        TIMEOUT;

        /**
         * The kind of an event aimed at a user task.
         *
         * @param event the incoming event
         * @return what the event does to the task
         * @throws NoApplicableWorkflowException when the event neither completes nor times out a task
         */
        static TaskEvent of(final WorkflowEvent event) throws NoApplicableWorkflowException
        {
            return switch (event.getName()) {
                case COMPLETE_EVENT -> COMPLETE;
                case TIMEOUT_EVENT -> TIMEOUT;
                default -> throw new NoApplicableWorkflowException("A user task has nothing waiting for a "
                    + event.getName() + " event: it can only be completed, or run out of time");
            };
        }
    }

    /**
     * An event about to end one open task: the task, the host it drives, who is sending, and what carries the
     * instance on.
     *
     * @param task the open task
     * @param host the resource the task's instance drives, whose creator {@code @creator} names
     * @param check whom the definitions admit, for the sender
     * @param runner what carries the instance on
     * @version $Id$
     * @since 0.1.0
     */
    private record Completion(TaskInstance task, Resource host, PerformerCheck check, InstanceRunner runner)
    {
        /**
         * Ends a task a catching event waits on, with the message it waits for.
         *
         * @param event the incoming event
         * @param definition the catching event
         * @throws WorkflowException when the event is not that message, or the sender may not send it
         * @throws PersistenceException when the instance cannot be written
         */
        void receive(final WorkflowEvent event, final IntermediateCatchingEvent definition)
            throws WorkflowException, PersistenceException
        {
            final String message = definition.getMessageName();
            if (!event.getName().equals(message) && !event.getName().equals(WorkflowEvent.MESSAGE_PREFIX + message)) {
                throw new NoApplicableWorkflowException(this.task.getPath() + " waits for the " + message
                    + " message, not for a " + event.getName() + " event");
            }
            this.check.verify(definition, this.host);
            this.runner.completeTask(this.task, definition);
        }

        /**
         * Ends a user task: a person completes it, or its deadline runs out.
         *
         * @param event the incoming event
         * @param definition the user task
         * @throws WorkflowException when the event does not apply, the sender may not send it, the outcome is
         *             missing or not one the task offers, or the definition cannot be run on from here
         * @throws PersistenceException when the instance cannot be written
         */
        void decide(final WorkflowEvent event, final Activity definition) throws WorkflowException, PersistenceException
        {
            if (TaskEvent.of(event) == TaskEvent.TIMEOUT) {
                final TaskDeadline deadline = new TaskDeadline(this.task, definition);
                this.check.verify(deadline.listWhoMayFire(), this.host);
                deadline.checkPassed();
                this.runner.fireDeadline(this.task, definition, deadline.getTimer());
                return;
            }
            this.check.verify(definition, this.host);
            final Optional<String> outcome = readOutcome(event, this.task, definition);
            if (outcome.isPresent()) {
                this.runner.completeTask(this.task, definition, outcome.get());
            } else {
                this.runner.completeTask(this.task, definition);
            }
        }
    }

    /**
     * The task an event is aimed at, while it is still open.
     *
     * @param taskResource the task, resolved through the engine's own session
     * @return the task
     * @throws NoApplicableWorkflowException when the task is already over
     */
    private static TaskInstance readOpenTask(final Resource taskResource) throws NoApplicableWorkflowException
    {
        final TaskInstance task = Objects.requireNonNull(taskResource.adaptTo(TaskInstance.class),
            "A wf:TaskInstance resource always adapts to its model");
        if (!TaskInstance.OPEN_STATUS.equals(task.getStatus())) {
            throw new NoApplicableWorkflowException("The task " + task.getPath() + " is already " + task.getStatus()
                + ", so there is nothing left to decide");
        }
        return task;
    }

    /**
     * The node a task was raised from: a user task, or a catching event.
     *
     * @param task the task
     * @param event the incoming event
     * @return its definition
     * @throws NoApplicableWorkflowException when the definition has gone and the event could not have ended the task
     *             anyway
     * @throws WorkflowDefinitionException when the definition has gone
     */
    private static FlowNode readDefinition(final TaskInstance task, final WorkflowEvent event)
        throws WorkflowException
    {
        final FlowNode definition = task.getDefinition();
        if (definition != null) {
            return definition;
        }
        // An event nothing would wait for is the sender's mistake, whatever became of the definition
        if (!COMPLETE_EVENT.equals(event.getName()) && !TIMEOUT_EVENT.equals(event.getName())) {
            throw new NoApplicableWorkflowException("The task " + task.getPath() + " has nothing waiting for a "
                + event.getName() + " event");
        }
        throw new WorkflowDefinitionException("The task " + task.getPath()
            + " no longer has a definition, so the instance cannot be carried on from it");
    }

    /**
     * The decision a completion carries.
     *
     * @param event the completing event
     * @param task the task being completed
     * @param definition the activity the task was raised from
     * @return the outcome, or nothing for a task with nothing to decide
     * @throws InvalidPayloadException when the outcome is missing or is not one the task offers
     */
    private static Optional<String> readOutcome(final WorkflowEvent event, final TaskInstance task,
        final Activity definition) throws InvalidPayloadException
    {
        final String outcome = Payloads.text(event, OUTCOME_PARAMETER);
        final List<String> options = definition.getOutcomeOptions();
        // Anything else would let the next gateway route on a decision nobody was offered
        if (outcome == null ? !options.isEmpty() : !options.contains(outcome)) {
            throw new InvalidPayloadException(options.isEmpty()
                ? "Completing " + task.getPath() + " takes no outcome"
                : "Completing " + task.getPath() + " takes one of its outcomes: " + String.join(", ", options));
        }
        return Optional.ofNullable(outcome);
    }

    /**
     * The resource a task's instance drives, whose creator {@code @creator} names.
     *
     * @param taskResource a task
     * @return the host resource
     */
    private static Resource findHost(final Resource taskResource)
    {
        return InstanceRecords.hostOf(
            Objects.requireNonNull(taskResource.getParent(), "A task always lives inside its instance"));
    }
}

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
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

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
@Component(service = TaskCompletion.class)
public class TaskCompletion
{
    /** The domain event that completes a user task. */
    static final String COMPLETE_EVENT = "complete";

    /** The payload entry carrying the person's decision. */
    static final String OUTCOME_PARAMETER = "outcome";

    /** The event a passed deadline delivers. */
    static final String TIMEOUT_EVENT = "timeout";

    /** The service user that delivers passed deadlines, and who a boundary timer admits when it names nobody. */
    static final String TIMER_USER = "iap-timer";

    private static final String WALK_ID_PROPERTY = "walkId";

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
            && performers.admits(definition, hostOf(taskResource)) ? Set.of(COMPLETE_EVENT) : Set.of();
    }

    /**
     * Completes the task the event was aimed at, or times it out.
     *
     * @param resolver the engine's own session
     * @param taskResource the task the event is aimed at
     * @param event the incoming event
     * @param actor the user who sent the event
     * @param performer how the resumed instance performs any service task it meets
     * @throws WorkflowException when the event does not apply, the actor may not complete it, a decision arrives
     *             without an outcome, or the definition cannot be run on from here
     * @throws PersistenceException when the instance cannot be written
     */
    void apply(final ResourceResolver resolver, final Resource taskResource, final WorkflowEvent event,
        final String actor, final InstanceRunner.ServiceTaskPerformer performer)
        throws WorkflowException, PersistenceException
    {
        checkEventApplies(event);
        final TaskInstance task = readOpenTask(taskResource);
        final Activity definition = readDefinition(task);
        stamp(taskResource);
        final Resource host = hostOf(taskResource);
        final PerformerCheck check = this.performers.checkFor(resolver, actor);
        final InstanceRunner runner = this.runners.runnerFor(resolver, performer, actor);
        if (TIMEOUT_EVENT.equals(event.getName())) {
            final IntermediateCatchingEvent timer = findRunningTimer(task, definition);
            check.verify(whoMayFire(timer), host);
            checkDeadlinePassed(task);
            runner.fireDeadline(task, timer);
        } else {
            check.verify(definition, host);
            runner.completeTask(task, readOutcome(event, task, definition));
        }
    }

    /**
     * Refuses an event a task has nothing waiting for.
     *
     * @param event the incoming event
     * @throws NoApplicableWorkflowException when the event neither completes nor times out a task
     */
    private static void checkEventApplies(final WorkflowEvent event) throws NoApplicableWorkflowException
    {
        if (!COMPLETE_EVENT.equals(event.getName()) && !TIMEOUT_EVENT.equals(event.getName())) {
            throw new NoApplicableWorkflowException("A task has nothing waiting for a " + event.getName()
                + " event; the only things that can happen to one are being completed and running out of time");
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
     * The activity a task was raised from.
     *
     * @param task the task
     * @return its definition
     * @throws WorkflowDefinitionException when the definition has gone
     */
    private static Activity readDefinition(final TaskInstance task) throws WorkflowDefinitionException
    {
        final Activity definition = task.getDefinition();
        if (definition == null) {
            throw new WorkflowDefinitionException("The task " + task.getPath()
                + " no longer has a definition, so the instance cannot be carried on from it");
        }
        return definition;
    }

    /**
     * The decision a completion carries.
     *
     * @param event the completing event
     * @param task the task being completed
     * @param definition the activity the task was raised from
     * @return the outcome, or {@code null} for a task with nothing to decide
     * @throws InvalidPayloadException when a task with outcomes to choose from is completed without one
     */
    private static String readOutcome(final WorkflowEvent event, final TaskInstance task, final Activity definition)
        throws InvalidPayloadException
    {
        final String outcome = Payloads.text(event, OUTCOME_PARAMETER);
        // A decision left blank would let the next gateway route on whatever an earlier task decided
        if (outcome == null && !definition.getOutcomeOptions().isEmpty()) {
            throw new InvalidPayloadException("Completing " + task.getPath() + " takes one of its outcomes: "
                + String.join(", ", definition.getOutcomeOptions()));
        }
        return outcome;
    }

    /**
     * Stamps the task's instance with a new identifier. Two events carrying one instance on at once then both change
     * the same property, and the second to commit is refused rather than merged. Otherwise two branches arriving at a
     * join in two requests would each see only themselves arrive, and both would wait for good.
     *
     * @param taskResource the task the event is aimed at, resolved through the engine's own session
     */
    private static void stamp(final Resource taskResource)
    {
        final ModifiableValueMap properties = Objects.requireNonNull(
            instanceOf(taskResource).adaptTo(ModifiableValueMap.class),
            "The engine's own session can always write an instance");
        properties.put(WALK_ID_PROPERTY, UUID.randomUUID().toString());
    }

    /**
     * The boundary timer the task's deadline belongs to.
     *
     * @param task the task whose deadline has passed
     * @param definition the activity the task was raised from
     * @return the timer
     * @throws NoApplicableWorkflowException when nothing is counting down to this task
     */
    private static IntermediateCatchingEvent findRunningTimer(final TaskInstance task, final Activity definition)
        throws NoApplicableWorkflowException
    {
        return definition.getBoundaryEvents().stream()
            .filter(event -> event.getElementId().equals(task.getDueEventId()))
            .findFirst()
            .orElseThrow(() -> new NoApplicableWorkflowException("The task " + task.getPath()
                + " has no deadline to run out: nothing is counting down to it"));
    }

    /**
     * Who may fire a boundary timer. The task's own performers do not count: they may decide the work, which is not
     * the same as declaring it late. A timer that names nobody admits the {@value #TIMER_USER} service user, which
     * is who the deadline sweep delivers as.
     *
     * @param timer the boundary timer
     * @return the principals it admits
     */
    private static List<String> whoMayFire(final IntermediateCatchingEvent timer)
    {
        final List<String> performers = timer.getPerformers();
        return performers.isEmpty() ? List.of(TIMER_USER) : performers;
    }

    /**
     * Refuses to time out a task early, whoever asks.
     *
     * @param task the task
     * @throws InvalidStateException when its deadline has not passed yet
     */
    private static void checkDeadlinePassed(final TaskInstance task) throws InvalidStateException
    {
        final Calendar due = task.getDueDate();
        if (due == null || due.after(Calendar.getInstance())) {
            throw new InvalidStateException("The task " + task.getPath() + " has not run out of time yet");
        }
    }

    /**
     * The resource a task's instance drives, whose creator {@code @creator} names.
     *
     * @param taskResource a task
     * @return the host resource
     */
    private static Resource hostOf(final Resource taskResource)
    {
        return InstanceRecords.hostOf(instanceOf(taskResource));
    }

    /**
     * The workflow instance a task belongs to.
     *
     * @param taskResource a task
     * @return the instance holding it
     */
    private static Resource instanceOf(final Resource taskResource)
    {
        return Objects.requireNonNull(taskResource.getParent(), "A task always lives inside its instance");
    }
}

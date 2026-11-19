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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.history.api.RecordedAction;
import io.uhndata.iap.history.api.RecordedEffect;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.Event;
import io.uhndata.iap.workflows.models.FlowNode;
import io.uhndata.iap.workflows.spi.Execution;

/**
 * What one delivery leaves behind besides its writes. What its steps said they did, and the instance it started, are
 * recorded by its commit. Once it commits, the notifications its message events announced are sent, and the work its
 * steps left for after the commit is done. If it does not commit, the actions its steps registered for a rollback
 * run instead.
 *
 * <p>What the delivery's events were aimed at, and what its steps said they did, are what it changed. Each entity
 * among them gets a new revision token before the commit.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class DeliveryConsequences
{
    private static final Logger LOGGER = LoggerFactory.getLogger(DeliveryConsequences.class);

    private final PendingNotifications notifications;

    private final List<RecordedEffect> effects = new ArrayList<>();

    private final List<StepAction> afterCommit = new ArrayList<>();

    private final List<StepAction> onRollback = new ArrayList<>();

    private final List<String> targets = new ArrayList<>();

    private String startedInstance;

    /**
     * Constructor.
     *
     * @param notifications where the delivery's notifications wait for its commit
     */
    DeliveryConsequences(final PendingNotifications notifications)
    {
        this.notifications = notifications;
    }

    /**
     * Holds the notification a throwing event announces, until the delivery commits. It is about what the event
     * {@link Event#actsOn acts on}.
     *
     * @param source the throwing event
     * @param target the resource the run is aimed at, backed by the engine's own session
     * @param variables the run's variables, which say what it created
     * @param cause the event being delivered
     * @param actor the user the run acts for
     * @throws WorkflowDefinitionException when the event names an unknown subject
     */
    void hold(final Event source, final Resource target, final Map<String, Object> variables,
        final WorkflowEvent cause, final String actor) throws WorkflowDefinitionException
    {
        this.notifications.hold(source, source.actsOn(new Run(target, variables)), cause, actor);
    }

    /**
     * Notes a resource an event of this delivery is aimed at: the event that came in, or one a step sent.
     *
     * @param path the path of the resource
     */
    void noteTarget(final String path)
    {
        this.targets.add(path);
    }

    /**
     * The resources this delivery changed: what its events were aimed at, and what its steps said they did.
     *
     * @return the paths of the resources, possibly repeated; some may since have been removed
     */
    List<String> changedPaths()
    {
        return Stream.concat(this.targets.stream(), this.effects.stream().map(RecordedEffect::path)).toList();
    }

    /**
     * Notes what a step did to a resource.
     *
     * @param path the path of the resource
     * @param role the part it played, named by its purpose
     */
    void recordRole(final String path, final String role)
    {
        this.effects.add(new RecordedEffect(path, role));
    }

    /**
     * Notes an instance a step started. The first one started is the one the history names.
     *
     * @param path the path of the instance
     */
    void noteStartedInstance(final String path)
    {
        if (this.startedInstance == null) {
            this.startedInstance = path;
        }
    }

    /**
     * Notes work to do once the delivery has committed.
     *
     * @param work what to do
     * @param step the activity whose step left it, which a failure is recorded against
     */
    void afterCommit(final Runnable work, final Activity step)
    {
        this.afterCommit.add(StepAction.of(work, step, "afterCommit"));
    }

    /**
     * Notes an action that undoes a side effect of a step, should the delivery not commit.
     *
     * @param action what undoes the side effect
     * @param step the activity whose step registered it, which a failure is recorded against
     */
    void onRollback(final Runnable action, final Activity step)
    {
        this.onRollback.add(StepAction.of(action, step, "rollback"));
    }

    /**
     * The delivery as its history records it, when a system workflow handled its event.
     *
     * @param actor the user who sent the event
     * @param event the event
     * @param start the start event of the system workflow that caught it
     * @return the action, with the instance the delivery started and what its steps said they did
     */
    RecordedAction recordedAsSystemWorkflow(final String actor, final WorkflowEvent event, final FlowNode start)
    {
        return withEffects(DeliveryCauses.ofSystemWorkflow(actor, event, start, this.startedInstance));
    }

    /**
     * The delivery as its history records it, when its event completed a task of a running instance.
     *
     * @param actor the user who sent the event
     * @param event the event
     * @param task the task the event completed, as the event left it
     * @return the action, with what its steps said they did
     */
    RecordedAction recordedAsTask(final String actor, final WorkflowEvent event, final Resource task)
    {
        return withEffects(DeliveryCauses.ofTask(actor, event, task));
    }

    private RecordedAction withEffects(final RecordedAction.Builder cause)
    {
        this.effects.forEach(cause::affecting);
        return cause.build();
    }

    /**
     * Finishes what waited for the commit: sends the notifications, then does the work the steps left, in the order
     * they left it. The delivery has committed by then, so a piece of work that fails is reported rather than thrown,
     * and the rest still runs.
     */
    void completeAfterCommit()
    {
        this.notifications.send();
        this.afterCommit.forEach(StepAction::perform);
    }

    /**
     * Cleans up after a delivery that did not commit, once its writes are reverted: runs the actions its steps
     * registered for a rollback, the last one registered first. An action that fails is reported rather than thrown,
     * and the rest still run. The notifications and the work left for after the commit are dropped.
     */
    void abandon()
    {
        this.onRollback.reversed().forEach(StepAction::perform);
    }

    /**
     * An action a step left for after the delivery, and what to record if it fails.
     *
     * @param action what to do
     * @param failure what to record if it fails
     * @version $Id$
     * @since 0.1.0
     */
    private record StepAction(Runnable action, ErrorContext failure)
    {
        static StepAction of(final Runnable action, final Activity step, final String operation)
        {
            return new StepAction(action, ErrorContext.of(step.getHandler(), operation).about(step.getPath()));
        }

        void perform()
        {
            try {
                this.action.run();
            } catch (final RuntimeException e) {
                LOGGER.error("An action a step left failed, {}: {}", this.failure, e.getMessage(), e);
                ErrorLogger.logError(e, this.failure);
            }
        }
    }

    /**
     * What a run is working on, for a step that has no task context of its own, such as a thrown message.
     *
     * @param target the resource the event is aimed at, backed by the engine's own session
     * @param variables the run's variables
     * @version $Id$
     * @since 0.1.0
     */
    private record Run(Resource target, Map<String, Object> variables) implements Execution
    {
        @Override
        public Resource getTarget()
        {
            return this.target;
        }

        @Override
        public Object getVariable(final String name)
        {
            return this.variables.get(name);
        }

        @Override
        public ResourceResolver getResourceResolver()
        {
            return this.target.getResourceResolver();
        }
    }
}

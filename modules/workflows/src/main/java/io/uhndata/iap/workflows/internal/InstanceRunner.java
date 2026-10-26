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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.EndEvent;
import io.uhndata.iap.workflows.models.FlowNode;
import io.uhndata.iap.workflows.models.IntermediateCatchingEvent;
import io.uhndata.iap.workflows.models.SequenceFlow;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowInstance;
import io.uhndata.iap.workflows.models.WorkflowToken;
import io.uhndata.iap.workflows.models.WorkflowVersion;

/**
 * The runtime of user workflows: the ones that outlive the request that started them.
 *
 * <p>A system workflow runs straight through and leaves nothing behind. A user workflow persists. It keeps a
 * {@code wf:WorkflowInstance} inside the resource it drives, a {@code wf:WorkflowToken} for each branch in
 * progress, and a {@code wf:TaskInstance} for each thing a person still has to do. {@link InstanceRecords} writes
 * those; this class decides what to write.</p>
 *
 * <p>Running one is always the same walk. From wherever a token rests, through whatever can be passed
 * automatically, until it has to stop. A token stops at a user task, where it parks and the walk returns, possibly
 * for days. It stops at a join that is still waiting for another branch. Or it reaches an end event, which ends its
 * branch and tells the host what the outcome meant. A terminating end event ends the whole instance. Starting an
 * instance, completing one of its tasks and firing a task's deadline are that same walk from different starting
 * points. All three live here.</p>
 *
 * <p>An instance holds one token for each branch in progress. Parallel and inclusive gateways fork a token into
 * several and join them back. The walk is therefore a queue of positions rather than a single path, and the instance
 * finishes when its last token is spent.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class InstanceRunner
{
    private final ServiceTaskPerformer performer;

    private final String actor;

    private final FlowRouting routing;

    private final InstanceRecords records;

    /**
     * Constructor.
     *
     * @param resolver the engine's own session, which everything is read and written through
     * @param performer how a service task met along the way gets performed
     * @param actor the user whose action is moving this instance
     * @param routing what decides where tokens go
     */
    InstanceRunner(final ResourceResolver resolver, final ServiceTaskPerformer performer, final String actor,
        final FlowRouting routing)
    {
        this.performer = performer;
        this.actor = actor;
        this.routing = routing;
        this.records = new InstanceRecords(resolver);
    }

    /**
     * Starts a new instance of a workflow version on a host resource and runs it up to its first wait.
     *
     * @param host the resource the workflow drives, which must be {@code wf:WorkflowAttachable}
     * @param version the version to instantiate
     * @return the created instance's resource
     * @throws WorkflowException when the version is not active or its definition cannot be run
     * @throws PersistenceException when the instance cannot be written
     */
    Resource startInstance(final Resource host, final WorkflowVersion version)
        throws WorkflowException, PersistenceException
    {
        if (!version.isActive()) {
            throw new WorkflowDefinitionException("The workflow version " + version.getPath()
                + " is not active, so " + host.getPath() + " cannot be put through it");
        }
        final List<? extends FlowNode> starts = version.getStartEvents();
        if (starts.size() != 1) {
            throw new WorkflowDefinitionException("A workflow needs exactly one start event to be instantiated, but "
                + version.getPath() + " has " + starts.size());
        }
        final Resource instance = this.records.createInstance(host, version);
        runFrom(instance, this.records.createToken(instance, starts.get(0).getElementId()), starts.get(0), null);
        return instance;
    }

    /**
     * Records a task as completed and carries its instance on from there.
     *
     * @param task the task being completed
     * @param outcome what the person decided; gateways downstream route on it
     * @throws WorkflowException when the definition cannot be run on from here
     * @throws PersistenceException when the instance cannot be written
     */
    void completeTask(final TaskInstance task, final String outcome) throws WorkflowException, PersistenceException
    {
        final WorkflowInstance instance = Objects.requireNonNull(task.getWorkflowInstance(),
            "A task always lives inside its instance");
        // Guaranteed by the caller, which cannot authorize the completion without it
        final Activity definition = Objects.requireNonNull(task.getDefinition(),
            "A task is only completed once its definition has been found");
        final Resource instanceResource = this.records.getResource(instance.getPath());
        final Resource token = this.records.findTokenAt(instanceResource, definition.getElementId());
        this.records.completeTask(instanceResource, this.records.getResource(task.getPath()), this.actor, outcome);

        // An activity has exactly one way out. If it leads to a gateway, the walk forks there
        final SequenceFlow flow = this.routing.selectOutgoingFlows(definition, instance).get(0);
        runFrom(instanceResource, token, targetOf(flow), flow);
    }

    /**
     * Fires the boundary timer a task's deadline belongs to.
     *
     * <p>An interrupting timer cancels the task, and the task's token leaves along the timer's arc. A non-interrupting
     * timer leaves the task open and starts a second branch along the timer's arc, with a token of its own.</p>
     *
     * @param task the task whose deadline has passed
     * @param timer the boundary event counting down to it
     * @throws WorkflowException when the definition cannot be run on from here
     * @throws PersistenceException when the instance cannot be written
     */
    void fireDeadline(final TaskInstance task, final IntermediateCatchingEvent timer)
        throws WorkflowException, PersistenceException
    {
        final WorkflowInstance instance = Objects.requireNonNull(task.getWorkflowInstance(),
            "A task always lives inside its instance");
        final Activity definition = Objects.requireNonNull(task.getDefinition(),
            "A task is only expired once its definition has been found");
        final Resource instanceResource = this.records.getResource(instance.getPath());

        if (timer.isInterrupting()) {
            // Gateways downstream see the last recorded outcome, if there is one
            this.records.cancelTask(this.records.getResource(task.getPath()));
            runFrom(instanceResource, this.records.findTokenAt(instanceResource, definition.getElementId()), timer,
                null);
            return;
        }
        this.records.recordFiredTimer(this.records.getResource(task.getPath()), definition, timer);
        runFrom(instanceResource, this.records.createToken(instanceResource, timer.getElementId()), timer, null);
    }

    /**
     * Walks the instance from a node until every branch has to stop: at a user task, at a join still missing a
     * branch, or at an end event.
     *
     * <p>When the queue empties, a join that could not release when its token arrived may be able to now. An
     * inclusive join waits on the branches that can still reach it, and the others have since moved as far as they
     * can. The walk carries on from such a join until nothing can move. This keeps the order the branches are walked
     * in from deciding whether the process gets stuck.</p>
     *
     * @param instance the running instance
     * @param token the token being moved
     * @param from where to carry on from
     * @param arrivedBy the flow the token took to get there, or {@code null} if it did not arrive along one
     * @throws WorkflowException when the definition cannot be run
     * @throws PersistenceException when the instance cannot be written
     */
    private void runFrom(final Resource instance, final Resource token, final FlowNode from,
        final SequenceFlow arrivedBy) throws WorkflowException, PersistenceException
    {
        final Deque<Step> pending = new ArrayDeque<>();
        pending.add(new Step(token, from, arrivedBy));
        int budget = WorkflowEngineImpl.MAX_STEPS;
        while (!pending.isEmpty()) {
            if (budget-- <= 0) {
                throw new WorkflowDefinitionException("The instance " + instance.getPath() + " did not settle within "
                    + WorkflowEngineImpl.MAX_STEPS + " steps; its sequence flows probably form a cycle");
            }
            if (!advanceToken(instance, pending.remove(), pending)) {
                // A terminating end event ended the whole instance, so nothing else queued may move
                return;
            }
            if (pending.isEmpty()) {
                findReleasableJoin(instance).ifPresent(pending::add);
            }
        }
    }

    /**
     * Advances one token through the node it has reached, enqueuing wherever it goes next.
     *
     * @param instance the running instance
     * @param step the token and the node it has reached
     * @param pending the queue to add the positions this step leads to
     * @return {@code false} when the instance has been ended outright and the walk must stop
     * @throws WorkflowException when the definition cannot be run
     * @throws PersistenceException when the instance cannot be written
     */
    private boolean advanceToken(final Resource instance, final Step step, final Deque<Step> pending)
        throws WorkflowException, PersistenceException
    {
        final Resource token = step.token();
        final FlowNode node = step.node();
        this.records.moveToken(token, node, step.arrivedBy());
        if (node instanceof EndEvent) {
            this.records.tagHost(instance, (EndEvent) node);
            if (((EndEvent) node).isTerminate()) {
                this.records.terminateInstance(instance);
                return false;
            }
            this.records.spendToken(instance, token);
            return true;
        }
        if (node instanceof Activity && ((Activity) node).getHandler() == null) {
            // No handler means a user task. Park here and wait for a person
            this.records.createTask(instance, (Activity) node);
            return true;
        }
        if (node instanceof Activity) {
            this.performer.perform((Activity) node, instance);
        } else if (!this.routing.canPassThrough(node)) {
            throw new WorkflowDefinitionException("The engine cannot yet carry an instance through "
                + node.getPath());
        }
        if (!mergeAtJoin(instance, node, token, step.arrivedBy(), pending)) {
            // A join still waiting for another branch; this token stays on it
            return true;
        }
        forkTokens(instance, token, node, pending);
        return true;
    }

    /**
     * Lets a token standing on a node carry on, merging into it the tokens waiting there when the node is a join that
     * releases.
     *
     * @param instance the running instance
     * @param node the node the token is standing on
     * @param token the token that arrived
     * @param arrivedBy the flow it arrived by
     * @param pending the queue, which must not be left holding a token that has been merged away
     * @return {@code true} if the token may carry on, {@code false} while a branch is still missing
     * @throws PersistenceException when the merged tokens cannot be removed
     */
    private boolean mergeAtJoin(final Resource instance, final FlowNode node, final Resource token,
        final SequenceFlow arrivedBy, final Deque<Step> pending) throws PersistenceException
    {
        if (!this.routing.isSynchronizingJoin(node)) {
            return true;
        }
        if (!isJoinReady(instance, node)) {
            return false;
        }
        final List<WorkflowToken> waiting = this.records.findTokensAt(instance, node.getElementId());
        for (final WorkflowToken merged : this.routing.findTokensMergingIntoArrival(node, token.getPath(), arrivedBy,
            waiting)) {
            this.records.deleteToken(this.records.getResource(merged.getPath()));
            // A fork leading straight into its own join queues every branch before any is walked, so a merged
            // token may still be queued. Left there, the walk would move a token that no longer exists
            pending.removeIf(queued -> queued.token().getPath().equals(merged.getPath()));
        }
        return true;
    }

    /**
     * Whether a join has everything it was waiting for, by the tokens standing on it now.
     *
     * @param instance the running instance
     * @param join the join to ask about
     * @return {@code true} if it may release
     */
    private boolean isJoinReady(final Resource instance, final FlowNode join)
    {
        final List<SequenceFlow> arrivedBy = this.records.findTokensAt(instance, join.getElementId()).stream()
            .map(WorkflowToken::getArrivedByFlow)
            .filter(Objects::nonNull)
            .toList();
        return this.routing.canReleaseJoin(join, arrivedBy,
            this.records.findOtherTokenPositions(instance, join.getElementId()));
    }

    /**
     * Finds a join that can release now, though it could not when its tokens arrived. The branches that might have
     * reached it have since gone elsewhere.
     *
     * @param instance the running instance
     * @return a position to carry on from, or empty when nothing more can move
     */
    private Optional<Step> findReleasableJoin(final Resource instance)
    {
        return this.records.asInstance(instance).getTokens().stream()
            .map(WorkflowToken::getCurrentNode)
            .filter(Objects::nonNull)
            .filter(this.routing::isSynchronizingJoin)
            .filter(join -> isJoinReady(instance, join))
            .findFirst()
            .map(join -> {
                final WorkflowToken first = this.records.findTokensAt(instance, join.getElementId()).get(0);
                return new Step(this.records.getResource(first.getPath()), join, first.getArrivedByFlow());
            });
    }

    /**
     * Leaves a node down every outgoing flow it takes, moving the token onto the first and creating one for each of
     * the rest.
     *
     * @param instance the running instance
     * @param token the token leaving the node
     * @param node the node being left
     * @param pending the queue to add the resulting positions to
     * @throws WorkflowException when there is no resolvable way onwards
     * @throws PersistenceException when a token cannot be written
     */
    private void forkTokens(final Resource instance, final Resource token, final FlowNode node,
        final Deque<Step> pending) throws WorkflowException, PersistenceException
    {
        final List<SequenceFlow> flows = this.routing.selectOutgoingFlows(node, this.records.asInstance(instance));
        pending.add(new Step(token, targetOf(flows.get(0)), flows.get(0)));
        for (final SequenceFlow branch : flows.subList(1, flows.size())) {
            final FlowNode target = targetOf(branch);
            pending.add(new Step(this.records.createToken(instance, target.getElementId()), target, branch));
        }
    }

    /**
     * The node a flow chosen by the routing leads to.
     *
     * @param flow a flow {@link FlowRouting#selectOutgoingFlows} returned
     * @return the node it leads to
     */
    private static FlowNode targetOf(final SequenceFlow flow)
    {
        return Objects.requireNonNull(flow.getTarget(), "The routing only selects flows that lead to a node");
    }

    /**
     * One token's position: the token, and the node it is next to be advanced through.
     *
     * <p>The node is carried here rather than read back from the token. A model adapted from a resource is cached on
     * that resource, so reading a position back through the same resource returns the one it had before.</p>
     *
     * @param token the token's resource
     * @param node where it has got to
     * @param arrivedBy the flow it took to get there, or {@code null} if it did not arrive along one
     * @version $Id$
     * @since 0.1.0
     */
    private record Step(Resource token, FlowNode node, SequenceFlow arrivedBy)
    {
    }

    /**
     * How the runner performs a service task it meets along the way. The engine supplies this rather than letting
     * the runner reach for handlers. Performing a service task then means the same thing in a system workflow and
     * in a running instance.
     *
     * @version $Id$
     * @since 0.1.0
     */
    interface ServiceTaskPerformer
    {
        /**
         * Performs one service task.
         *
         * @param activity the activity to perform
         * @param instance the running instance it belongs to
         * @throws WorkflowException when the activity cannot be performed
         * @throws PersistenceException when its writes fail
         */
        void perform(Activity activity, Resource instance) throws WorkflowException, PersistenceException;
    }
}

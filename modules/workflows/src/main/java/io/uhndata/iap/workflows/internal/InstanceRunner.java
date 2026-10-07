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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.tags.models.Taggable;
import io.uhndata.iap.utils.NodeNameUtils;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.EndEvent;
import io.uhndata.iap.workflows.models.FlowNode;
import io.uhndata.iap.workflows.models.IntermediateCatchingEvent;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowInstance;
import io.uhndata.iap.workflows.models.WorkflowInstances;
import io.uhndata.iap.workflows.models.WorkflowToken;
import io.uhndata.iap.workflows.models.WorkflowVersion;

/**
 * The runtime of user workflows: the ones that outlive the request that started them.
 *
 * <p>A system workflow runs straight through and leaves nothing behind. A user workflow persists. It keeps a
 * {@code wf:WorkflowInstance} inside the resource it drives, a {@code wf:WorkflowToken} for each branch in
 * progress, and a {@code wf:TaskInstance} for each thing a person still has to do.</p>
 *
 * <p>Running one is always the same walk. From wherever the token rests, through whatever can be passed
 * automatically, until it has to stop. It stops at a user task, where the token parks and the walk returns,
 * possibly for days. Or at an end event, where the instance finishes and tells the host what the outcome meant.
 * Starting an instance and resuming a parked one are that same walk from different starting points. Both live
 * here.</p>
 *
 * <p>An instance holds one token for each branch in progress. A parallel gateway forks a token into several and
 * joins them back. The walk is therefore a queue of positions rather than a single path, and the instance finishes
 * when its last token is spent.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class InstanceRunner
{
    /** The variable a completed task's outcome is recorded under. Gateways route on it. */
    static final String OUTCOME_VARIABLE = "outcome";

    /**
     * How many nodes one delivery may pass through, counted across all its branches, before the definition is
     * declared broken. Far above anything a real workflow needs, so a definition whose arcs form a cycle fails fast
     * instead of spinning.
     */
    static final int MAX_STEPS = 200;

    private static final String JCR_PRIMARY_TYPE_PROPERTY = "jcr:primaryType";

    private static final String STATUS_PROPERTY = "status";

    private static final String COMPLETED_STATUS = "completed";

    private static final String CURRENT_NODE_ID_PROPERTY = "currentNodeId";

    private static final String START_TIME_PROPERTY = "startTime";

    /** The status a task carries until it is completed or cancelled. */
    private static final String OPEN_STATUS = "created";

    private static final String CANCELLED_STATUS = "cancelled";

    private static final String END_TIME_PROPERTY = "endTime";

    private final ResourceResolver resolver;

    private final ServiceTaskPerformer performer;

    private final String actor;

    private final FlowRouting routing;

    /**
     * Constructor.
     *
     * @param resolver the engine's own session, which everything is read and written through
     * @param performer how a service task met along the way gets performed
     * @param actor the user whose action is moving this instance
     * @param conditions the evaluator for the guards on a gateway's arcs
     */
    InstanceRunner(final ResourceResolver resolver, final ServiceTaskPerformer performer, final String actor,
        final ConditionEvaluator conditions)
    {
        this.resolver = resolver;
        this.performer = performer;
        this.actor = actor;
        this.routing = new FlowRouting(conditions);
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
    Resource start(final Resource host, final WorkflowVersion version)
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
        final Resource instance = createInstance(host, version);
        run(instance, createToken(instance, starts.get(0).getElementId()), starts.get(0));
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
    void complete(final TaskInstance task, final String outcome) throws WorkflowException, PersistenceException
    {
        final WorkflowInstance instance = Objects.requireNonNull(task.getWorkflowInstance(),
            "A task always lives inside its instance");
        // Guaranteed by the caller, which cannot authorize the completion without it
        final Activity definition = Objects.requireNonNull(task.getDefinition(),
            "A task is only completed once its definition has been found");
        final Resource instanceResource = resourceOf(instance.getPath());
        final Resource token = tokenAt(instanceResource, definition.getElementId());

        final ModifiableValueMap properties = modifiable(resourceOf(task.getPath()));
        properties.put(STATUS_PROPERTY, COMPLETED_STATUS);
        properties.put(END_TIME_PROPERTY, Calendar.getInstance());
        properties.put("assignee", this.actor);
        if (outcome != null) {
            properties.put(OUTCOME_VARIABLE, outcome);
            setOutcome(instanceResource, outcome);
        }

        // An activity has exactly one way out. If it leads to a gateway, the walk forks there
        run(instanceResource, token, this.routing.targets(definition, instance).get(0));
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
     * @throws WorkflowException when the definition cannot be run
     * @throws PersistenceException when the instance cannot be written
     */
    private void run(final Resource instance, final Resource token, final FlowNode from)
        throws WorkflowException, PersistenceException
    {
        final Deque<Step> pending = new ArrayDeque<>();
        pending.add(new Step(token, from));
        int budget = MAX_STEPS;
        while (!pending.isEmpty()) {
            if (budget-- <= 0) {
                throw new WorkflowDefinitionException("The instance " + instance.getPath() + " did not settle within "
                    + MAX_STEPS + " steps; its sequence flows probably form a cycle");
            }
            if (!step(instance, pending.remove(), pending)) {
                // A terminating end event ended the whole instance, so nothing else queued may move
                return;
            }
            if (pending.isEmpty()) {
                released(instance).ifPresent(pending::add);
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
    private boolean step(final Resource instance, final Step step, final Deque<Step> pending)
        throws WorkflowException, PersistenceException
    {
        final Resource token = step.token();
        final FlowNode node = step.node();
        modifiable(token).put(CURRENT_NODE_ID_PROPERTY, node.getElementId());
        if (node instanceof EndEvent) {
            tellHost(instance, (EndEvent) node);
            if (((EndEvent) node).isTerminate()) {
                terminate(instance);
                return false;
            }
            spend(instance, token);
            return true;
        }
        if (node instanceof Activity && ((Activity) node).getHandler() == null) {
            // No handler means a user task. Park here and wait for a person
            createTask(instance, (Activity) node);
            return true;
        }
        if (node instanceof Activity) {
            this.performer.perform((Activity) node, instance);
        } else if (!this.routing.passable(node)) {
            throw new WorkflowDefinitionException("The engine cannot yet carry an instance through "
                + node.getPath());
        }
        if (!merged(instance, node, token, pending)) {
            // A join still waiting for another branch; this token stays on it
            return true;
        }
        fork(instance, token, node, pending);
        return true;
    }

    /**
     * Whether a token standing on a join may leave it, merging the tokens it synchronises with when it may.
     *
     * @param instance the running instance
     * @param node the node the token is standing on
     * @param token the token that arrived
     * @param pending the queue, which must not be left holding a token that has been merged away
     * @return {@code true} if the token may carry on, {@code false} while a branch is still missing
     * @throws PersistenceException when the merged tokens cannot be removed
     */
    private boolean merged(final Resource instance, final FlowNode node, final Resource token,
        final Deque<Step> pending) throws PersistenceException
    {
        if (!this.routing.synchronises(node)) {
            return true;
        }
        final List<Resource> arrived = tokensAt(instance, node.getElementId());
        if (!this.routing.releases(node, arrived.size(), elsewhere(instance, node.getElementId()))) {
            return false;
        }
        for (final Resource spent : arrived) {
            if (!spent.getPath().equals(token.getPath())) {
                this.resolver.delete(spent);
                // A fork leading straight into its own join queues every branch before any is walked, so a merged
                // token may still be queued. Left there, the walk would move a token that no longer exists
                pending.removeIf(queued -> queued.token().getPath().equals(spent.getPath()));
            }
        }
        return true;
    }

    /**
     * Where the tokens that are not on a given node are standing. An inclusive join uses this to tell which branches
     * can still reach it.
     *
     * @param instance the running instance
     * @param elementId the node to leave out
     * @return the nodes the other tokens are on, ignoring any whose node is no longer in the workflow
     */
    private List<FlowNode> elsewhere(final Resource instance, final String elementId)
    {
        return adapt(instance).getTokens().stream()
            .filter(token -> !elementId.equals(token.getCurrentNodeId()))
            .map(WorkflowToken::getCurrentNode)
            .filter(Objects::nonNull)
            .toList();
    }

    /**
     * Finds a join that can release now, though it could not when its tokens arrived. The branches that might have
     * reached it have since gone elsewhere.
     *
     * @param instance the running instance
     * @return a position to carry on from, or empty when nothing more can move
     */
    private Optional<Step> released(final Resource instance)
    {
        return adapt(instance).getTokens().stream()
            .map(WorkflowToken::getCurrentNode)
            .filter(Objects::nonNull)
            .filter(this.routing::synchronises)
            .filter(join -> this.routing.releases(join, tokensAt(instance, join.getElementId()).size(),
                elsewhere(instance, join.getElementId())))
            .findFirst()
            .map(join -> new Step(tokensAt(instance, join.getElementId()).get(0), join));
    }

    /**
     * Leaves a node down every arc it takes, moving the token onto the first and creating one for each of the rest.
     *
     * @param instance the running instance
     * @param token the token leaving the node
     * @param node the node being left
     * @param pending the queue to add the resulting positions to
     * @throws WorkflowException when there is no resolvable way onwards
     * @throws PersistenceException when a token cannot be written
     */
    private void fork(final Resource instance, final Resource token, final FlowNode node,
        final Deque<Step> pending) throws WorkflowException, PersistenceException
    {
        final List<FlowNode> targets = this.routing.targets(node, adapt(instance));
        pending.add(new Step(token, targets.get(0)));
        for (final FlowNode branch : targets.subList(1, targets.size())) {
            pending.add(new Step(createToken(instance, branch.getElementId()), branch));
        }
    }

    /**
     * One token's position: the token, and the node it is next to be advanced through.
     *
     * <p>The node is carried here rather than read back from the token. A model adapted from a resource is cached on
     * that resource, so reading a position back through the same resource returns the one it had before.</p>
     *
     * @param token the token's resource
     * @param node where it has got to
     * @version $Id$
     * @since 0.1.0
     */
    private record Step(Resource token, FlowNode node)
    {
    }

    /**
     * The tokens resting on a node.
     *
     * @param instance the running instance
     * @param elementId the node to look at
     * @return their resources, in the order the instance holds them
     */
    private List<Resource> tokensAt(final Resource instance, final String elementId)
    {
        return adapt(instance).getTokens().stream()
            .filter(candidate -> elementId.equals(candidate.getCurrentNodeId()))
            .map(candidate -> resourceOf(candidate.getPath()))
            .toList();
    }

    /**
     * Creates a token resting on a node.
     *
     * @param instance the running instance
     * @param elementId the node it starts on
     * @return the created token's resource
     * @throws PersistenceException when it cannot be written
     */
    private Resource createToken(final Resource instance, final String elementId) throws PersistenceException
    {
        return this.resolver.create(instance, NodeNameUtils.findFreeName(instance, "token"), Map.of(
            JCR_PRIMARY_TYPE_PROPERTY, "wf:WorkflowToken", CURRENT_NODE_ID_PROPERTY, elementId));
    }

    /**
     * Spends a token on the end event it reached, ending that branch. An end event ends a branch, not the process,
     * so the instance closes only when its last token is gone.
     *
     * @param instance the running instance
     * @param token the token that arrived
     * @throws PersistenceException when the instance cannot be written
     */
    private void spend(final Resource instance, final Resource token) throws PersistenceException
    {
        this.resolver.delete(token);
        if (adapt(instance).getTokens().isEmpty()) {
            close(instance);
        }
    }

    /**
     * Tells the host what reaching an end event means, if the end event says so. Every branch that reaches an end
     * event does this, not only the last one.
     *
     * @param instance the running instance
     * @param end the end event reached
     * @throws PersistenceException when the host cannot be tagged
     */
    private void tellHost(final Resource instance, final EndEvent end) throws PersistenceException
    {
        final String hostTag = end.getHostTag();
        if (hostTag != null) {
            // Lifecycle tags are system tags, and placing one is the engine's job, as it is the tag tasks'
            Objects.requireNonNull(host(instance).adaptTo(Taggable.class),
                "A workflow's host is taggable").tag(hostTag, true);
        }
    }

    /**
     * Ends the whole instance at once: every remaining token is discarded, and every task still waiting for
     * somebody is cancelled.
     *
     * <p>This is what {@code terminate} on an end event means. Open tasks are cancelled along with their tokens. A
     * task whose token is gone can never be completed, and would otherwise stay on somebody's desk for good.</p>
     *
     * @param instance the running instance
     * @throws PersistenceException when the instance cannot be written
     */
    private void terminate(final Resource instance) throws PersistenceException
    {
        final WorkflowInstance model = adapt(instance);
        for (final WorkflowToken token : model.getTokens()) {
            this.resolver.delete(resourceOf(token.getPath()));
        }
        for (final TaskInstance task : model.getTaskInstances()) {
            if (OPEN_STATUS.equals(task.getStatus())) {
                final ModifiableValueMap properties = modifiable(resourceOf(task.getPath()));
                properties.put(STATUS_PROPERTY, CANCELLED_STATUS);
                properties.put(END_TIME_PROPERTY, Calendar.getInstance());
            }
        }
        close(instance);
    }

    /**
     * Marks an instance as finished.
     *
     * @param instance the instance to close
     */
    private void close(final Resource instance)
    {
        final ModifiableValueMap properties = modifiable(instance);
        properties.put(STATUS_PROPERTY, COMPLETED_STATUS);
        properties.put(END_TIME_PROPERTY, Calendar.getInstance());
    }

    /**
     * Creates the task a person now has to do; the token stays on it until they do.
     *
     * @param instance the running instance
     * @param activity the user task reached
     * @throws PersistenceException when the task cannot be written
     */
    private void createTask(final Resource instance, final Activity activity) throws PersistenceException
    {
        final String name = NodeNameUtils.findFreeName(instance, activity.getName());
        final Map<String, Object> properties = new HashMap<>(Map.of(
            JCR_PRIMARY_TYPE_PROPERTY, "wf:TaskInstance",
            "taskDefinitionId", activity.getElementId(),
            "label", Objects.requireNonNullElse(activity.getLabel(), activity.getElementId()),
            STATUS_PROPERTY, OPEN_STATUS,
            START_TIME_PROPERTY, Calendar.getInstance()));
        arm(activity, (Calendar) properties.get(START_TIME_PROPERTY), List.of(), properties);
        this.resolver.create(instance, name, properties);
    }

    /**
     * Starts the clock on the deadline a boundary timer gives this task, if one watches it.
     *
     * <p>The deadline is written onto the task, where anything looking for overdue work can find it without running
     * the engine. The earliest timer that has not fired yet is the one armed.</p>
     *
     * <p>Every duration counts from when the task started, not from now. "Remind them after three days, give up after
     * five" means five days from the start, not from the reminder.</p>
     *
     * @param activity the user task being raised
     * @param started when the task began waiting, which every deadline is measured from
     * @param fired the events that have already fired and are not to be armed again
     * @param properties the task's properties, added to in place
     */
    private static void arm(final Activity activity, final Calendar started, final List<String> fired,
        final Map<String, Object> properties)
    {
        activity.getBoundaryEvents().stream()
            .filter(event -> event.getTimerDuration() != null && !fired.contains(event.getElementId()))
            .min(Comparator.comparing(IntermediateCatchingEvent::getTimerDuration))
            .ifPresentOrElse(timer -> {
                final Calendar due = (Calendar) started.clone();
                due.add(Calendar.SECOND, (int) Objects.requireNonNull(timer.getTimerDuration()).toSeconds());
                properties.put("dueDate", due);
                properties.put("dueEventId", timer.getElementId());
            }, () -> {
                // No deadline is left, so the sweep stops finding this task
                properties.remove("dueDate");
                properties.remove("dueEventId");
            });
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
    void expire(final TaskInstance task, final IntermediateCatchingEvent timer)
        throws WorkflowException, PersistenceException
    {
        final WorkflowInstance instance = Objects.requireNonNull(task.getWorkflowInstance(),
            "A task always lives inside its instance");
        final Activity definition = Objects.requireNonNull(task.getDefinition(),
            "A task is only expired once its definition has been found");
        final Resource instanceResource = resourceOf(instance.getPath());
        final ModifiableValueMap properties = modifiable(resourceOf(task.getPath()));

        if (timer.isInterrupting()) {
            properties.put(STATUS_PROPERTY, CANCELLED_STATUS);
            properties.put(END_TIME_PROPERTY, Calendar.getInstance());
            // No assignee and no outcome: nobody acted, and nothing was decided. Gateways downstream see the last
            // recorded outcome, if there is one
            run(instanceResource, tokenAt(instanceResource, definition.getElementId()), timer);
            return;
        }
        final List<String> fired = new ArrayList<>(task.getFiredEvents());
        fired.add(timer.getElementId());
        properties.put("firedEvents", fired.toArray(String[]::new));
        arm(definition, Objects.requireNonNullElseGet(task.getStartTime(), Calendar::getInstance), fired, properties);
        run(instanceResource, createToken(instanceResource, timer.getElementId()), timer);
    }

    /**
     * Creates the instance node inside the host's workflow container.
     *
     * @param host the resource the workflow drives
     * @param version the version being instantiated
     * @return the created instance resource
     * @throws WorkflowException when the host cannot hold workflows
     * @throws PersistenceException when the instance cannot be written
     */
    private Resource createInstance(final Resource host, final WorkflowVersion version)
        throws WorkflowException, PersistenceException
    {
        final Resource container = host.getChild(WorkflowInstances.NODE_NAME);
        if (container == null) {
            throw new WorkflowDefinitionException("The resource " + host.getPath()
                + " cannot hold workflows: it is not wf:WorkflowAttachable");
        }
        final String name = NodeNameUtils.findFreeName(container, definitionName(version));
        final Resource instance = this.resolver.create(container, name, Map.of(
            JCR_PRIMARY_TYPE_PROPERTY, "wf:WorkflowInstance",
            STATUS_PROPERTY, "active",
            START_TIME_PROPERTY, Calendar.getInstance()));
        // Through the JCR API. The node type declares a strict REFERENCE, and Oak rejects a string carrying the
        // right identifier as the wrong type
        final Node node = Objects.requireNonNull(instance.adaptTo(Node.class),
            "A freshly created instance is always backed by a JCR node");
        final Node target = Objects.requireNonNull(resourceOf(version.getPath()).adaptTo(Node.class),
            "A workflow version is always backed by a JCR node");
        try {
            node.setProperty("workflowVersion", target);
        } catch (final RepositoryException e) {
            throw new PersistenceException("Could not point the instance at its workflow version", e);
        }
        return instance;
    }

    /**
     * The name to give an instance: the workflow's own. A host carrying several then reads clearly.
     *
     * @param version the version being instantiated
     * @return a node name base
     */
    private String definitionName(final WorkflowVersion version)
    {
        return Objects.requireNonNull(resourceOf(version.getPath()).getParent(),
            "A workflow version always lives inside its definition").getName();
    }

    /**
     * The token resting on a given node.
     *
     * @param instance the running instance
     * @param elementId the node the token should be on
     * @return the token's resource
     * @throws WorkflowDefinitionException when no token is there
     */
    private Resource tokenAt(final Resource instance, final String elementId) throws WorkflowDefinitionException
    {
        return adapt(instance).getTokens().stream()
            .filter(candidate -> elementId.equals(candidate.getCurrentNodeId()))
            .findFirst()
            .map(candidate -> resourceOf(candidate.getPath()))
            .orElseThrow(() -> new WorkflowDefinitionException("The instance " + instance.getPath()
                + " is no longer waiting at " + elementId));
    }

    /**
     * Records the outcome the instance's gateways will route on, replacing any earlier one.
     *
     * @param instance the running instance
     * @param outcome the outcome to record
     * @throws PersistenceException when the variable cannot be written
     */
    private void setOutcome(final Resource instance, final String outcome) throws PersistenceException
    {
        final Resource existing = instance.getChild(OUTCOME_VARIABLE);
        if (existing == null) {
            this.resolver.create(instance, OUTCOME_VARIABLE, Map.of(
                JCR_PRIMARY_TYPE_PROPERTY, "wf:Variable", "dataType", "string", "stringValue", outcome));
        } else {
            modifiable(existing).put("stringValue", outcome);
        }
    }

    /**
     * The resource a workflow instance drives, two levels up past its container.
     *
     * @param instance the running instance
     * @return the host resource
     */
    private Resource host(final Resource instance)
    {
        return Objects.requireNonNull(Objects.requireNonNull(instance.getParent(),
            "An instance always lives in a container").getParent(), "A container always lives in its host");
    }

    /**
     * The resource at a path the engine has already seen, so it is certainly there.
     *
     * @param path the path to resolve
     * @return the resource
     */
    private Resource resourceOf(final String path)
    {
        return Objects.requireNonNull(this.resolver.getResource(path),
            "The engine's own session can always see " + path);
    }

    /**
     * The model view of an instance the engine itself has just read or written, so it always adapts.
     *
     * @param instance an instance's resource
     * @return the same instance as a model
     */
    private WorkflowInstance adapt(final Resource instance)
    {
        return Objects.requireNonNull(instance.adaptTo(WorkflowInstance.class),
            "A wf:WorkflowInstance resource always adapts to its model");
    }

    /**
     * The writable properties of a node the engine is about to change.
     *
     * @param resource the resource to change
     * @return its properties, writable
     */
    private ModifiableValueMap modifiable(final Resource resource)
    {
        return Objects.requireNonNull(resource.adaptTo(ModifiableValueMap.class),
            "A node the engine is writing is always modifiable");
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

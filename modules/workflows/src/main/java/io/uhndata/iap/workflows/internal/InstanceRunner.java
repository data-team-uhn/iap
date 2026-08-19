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
import java.util.Calendar;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.utils.NodeNameUtils;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.EndEvent;
import io.uhndata.iap.workflows.models.FlowNode;
import io.uhndata.iap.workflows.models.IntermediateCatchingEvent;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowInstance;
import io.uhndata.iap.workflows.models.WorkflowToken;
import io.uhndata.iap.workflows.models.WorkflowVersion;

/**
 * The runtime of <em>user</em> workflows: the ones that outlive the request that started them. Where a system
 * workflow runs straight through and leaves nothing behind, a user workflow persists — a {@code wf:WorkflowInstance}
 * inside the resource it drives, a token recording where it has got to, and a {@code wf:TaskInstance} for each thing
 * a person still has to do.
 *
 * <p>Running one always means the same walk: from wherever the token rests, through whatever can be passed
 * automatically, until either a user task is reached — where the token parks and the walk returns, possibly for
 * days — or an end event is, where the instance finishes and tells the host what the outcome meant. Starting an
 * instance and resuming a parked one are the same walk from different starting points, which is why both live
 * here.</p>
 *
 * <p>An instance holds as many tokens as it has branches in progress. A parallel gateway forks one into several
 * and joins them back, so the walk is a queue of positions to advance rather than a single path, and the instance
 * finishes when the last token is spent rather than when the first end event is reached.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class InstanceRunner
{
    /** The variable a completed task's outcome is recorded under, and that gateways route on. */
    static final String OUTCOME = "outcome";

    /** The name of the container a {@code wf:WorkflowAttachable} host keeps its instances in. */
    static final String INSTANCES = "wf:instances";

    /**
     * How many nodes one delivery may pass through before the definition is declared broken. Counted across every
     * branch of the delivery rather than along one path, since a fork multiplies the visits: it bounds the whole
     * walk, which is what has to terminate.
     */
    private static final int MAX_STEPS = 200;

    private static final String TYPE = "jcr:primaryType";

    private static final String STATUS = "status";

    private static final String COMPLETED = "completed";

    /** The status a task carries until it is completed or cancelled. */
    private static final String OPEN = "created";

    private static final String CANCELLED = "cancelled";

    private static final String CURRENT_NODE_ID = "currentNodeId";

    private static final String START_TIME = "startTime";

    private static final String END_TIME = "endTime";

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
     * @param conditions the evaluator a gateway's guards are asked of
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
     * @throws WorkflowException when the definition cannot be run
     * @throws PersistenceException when the instance cannot be written
     */
    Resource start(final Resource host, final WorkflowVersion version)
        throws WorkflowException, PersistenceException
    {
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
     * @param outcome what the person decided, which is what gateways downstream route on
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
        properties.put(STATUS, COMPLETED);
        properties.put(END_TIME, Calendar.getInstance());
        properties.put("assignee", this.actor);
        if (outcome != null) {
            properties.put(OUTCOME, outcome);
            setOutcome(instanceResource, outcome);
        }

        // An activity has exactly one way out, so leaving it is unambiguous; if that way is a gateway, the walk
        // forks there rather than here
        run(instanceResource, token, this.routing.targets(definition, instance).get(0));
    }

    /**
     * Walks the instance from a node until every branch of it has to stop: a user task to wait at, a join still
     * missing a branch, or an end event to spend the token on.
     *
     * <p>A queue rather than a loop along one path, because a fork turns one position into several and each has to
     * be walked. The order they come out in does not matter: a join fires on the arrival that completes it,
     * whichever that turns out to be, and the ones before it simply park.</p>
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
                // The whole instance is over, so whatever else was queued has nowhere to go
                return;
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
        modifiable(token).put(CURRENT_NODE_ID, node.getElementId());
        if (node.getHostTag() != null) {
            // On arrival, and for every kind of node: what a host's state is depends on where its process has
            // got to, so the state changes as the token does. A node the walk only passes through therefore
            // leaves a state that lasts an instant, which is exactly right — the lasting ones are on the nodes
            // execution stops at, and those are the ones anybody ever sees.
            HostLifecycle.record(hostOf(instance), node);
        }
        if (node instanceof EndEvent) {
            if (((EndEvent) node).isTerminate()) {
                terminate(instance);
                return false;
            }
            spend(instance, token);
            return true;
        }
        if (node instanceof Activity && ((Activity) node).getHandler() == null) {
            // Nothing can perform it automatically, so it is a user task: park here and wait for a person
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
        if (arrived.size() < this.routing.branches(node)) {
            return false;
        }
        // The branches are merged back into the one token that carries on; the others have arrived and are done
        for (final Resource spent : arrived) {
            if (!spent.getPath().equals(token.getPath())) {
                this.resolver.delete(spent);
                // A fork leading straight into its own join queues every branch before any of them is walked, so a
                // token can be merged away while it is still waiting its turn. Leaving it queued would have the walk
                // move a token that no longer exists, which a repository is entitled to refuse
                pending.removeIf(queued -> queued.token().getPath().equals(spent.getPath()));
            }
        }
        return true;
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
     * <p>The node is carried here rather than read back from the token, even though the token records it: a model
     * adapted from a resource is cached on that resource, so a position written and then read back through the same
     * resource answers with the one it had before. Carrying it is also simply what the queue is for.</p>
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
     * Creates a token resting on a node. Named freely rather than fixed, since an instance holds one per branch in
     * progress and they are all alike.
     *
     * @param instance the running instance
     * @param elementId the node it starts on
     * @return the created token's resource
     * @throws PersistenceException when it cannot be written
     */
    private Resource createToken(final Resource instance, final String elementId) throws PersistenceException
    {
        final String name = Objects.requireNonNullElse(NodeNameUtils.findFreeName(instance, "token"), "token");
        return this.resolver.create(instance, name, Map.of(
            TYPE, "wf:WorkflowToken", CURRENT_NODE_ID, elementId));
    }

    /**
     * Spends a token on the end event it reached: that branch is over. The instance is closed only once the last
     * token is gone, since an end event ends a branch rather than the process — a diagram with two of them, or one
     * reached by two branches, is finished when nothing is left running.
     *
     * <p>What arriving here meant to the host has already been recorded by the walk, the same way it is for any
     * other node.</p>
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
     * Ends the whole instance at once: every remaining token is discarded, and every task still waiting for
     * somebody is cancelled.
     *
     * <p>This is what {@code terminate} on an end event means, and why it is a property of an end event rather than
     * a kind of its own: the difference is entirely in what happens to the <em>other</em> branches. The open tasks
     * have to go with their tokens — a task whose token has been discarded can never be completed, and leaving it
     * open would put work on somebody's desk that nothing will ever take off it again.</p>
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
            if (OPEN.equals(task.getStatus())) {
                final ModifiableValueMap properties = modifiable(resourceOf(task.getPath()));
                properties.put(STATUS, CANCELLED);
                properties.put(END_TIME, Calendar.getInstance());
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
        properties.put(STATUS, COMPLETED);
        properties.put(END_TIME, Calendar.getInstance());
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
        final String name = Objects.requireNonNullElse(
            NodeNameUtils.findFreeName(instance, activity.getName()), activity.getName());
        final Map<String, Object> properties = new HashMap<>(Map.of(
            TYPE, "wf:TaskInstance",
            "taskDefinitionId", activity.getElementId(),
            "label", Objects.requireNonNullElse(activity.getLabel(), activity.getElementId()),
            // Copied so the task states its own terms: whoever has to do it can read it without being able to read
            // the definition, and what it offers cannot change under them while it waits
            "offeredOutcomes", activity.getOutcomes().toArray(String[]::new),
            // Recorded for the same reason, and resolved here because "@creator" is a question about this host
            // that nothing reading the task later is holding the host to ask
            "performers", PerformerCheck.resolve(hostOf(instance), activity.getPerformers()).toArray(String[]::new),
            STATUS, OPEN,
            START_TIME, Calendar.getInstance()));
        arm(activity, properties);
        this.resolver.create(instance, name, properties);
    }

    /**
     * Starts the clock on the deadline a boundary timer gives this task, if one watches it.
     *
     * <p>Written onto the task rather than left to be worked out later, for the reason every other copy on a task
     * exists: when the waiting started is a fact about this run, and reading it back from the definition would
     * answer a different question — how long the wait is, not when it ends. It also puts the deadline where
     * anything looking for overdue work can see it without running the engine.</p>
     *
     * <p>The earliest of several timers wins, since that is the one that will actually fire; the rest would have
     * needed a token each anyway.</p>
     *
     * @param activity the user task being raised
     * @param properties the task's properties, added to in place
     */
    private static void arm(final Activity activity, final Map<String, Object> properties)
    {
        activity.getBoundaryEvents().stream()
            .filter(event -> event.getTimerDuration() != null)
            .min(Comparator.comparing(IntermediateCatchingEvent::getTimerDuration))
            .ifPresent(timer -> {
                final Calendar due = Calendar.getInstance();
                due.add(Calendar.SECOND, (int) Objects.requireNonNull(timer.getTimerDuration()).toSeconds());
                properties.put("dueDate", due);
                properties.put("dueEventId", timer.getElementId());
            });
    }

    /**
     * Fires the boundary timer a task's deadline belongs to: the task is cancelled, and execution leaves down the
     * timer's own arc rather than the activity's.
     *
     * <p>Only an interrupting timer can be fired this way, which for now is every timer: a non-interrupting one
     * leaves the work in progress and starts a second path beside it, and an instance has one token.</p>
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
        final Resource token = tokenAt(instanceResource, definition.getElementId());

        final ModifiableValueMap properties = modifiable(resourceOf(task.getPath()));
        properties.put(STATUS, CANCELLED);
        properties.put(END_TIME, Calendar.getInstance());
        // Deliberately no assignee and no outcome: nobody did this, and nothing was decided. A gateway reading the
        // outcome downstream therefore sees whatever the last decision was, or nothing at all, which is why a
        // process that wants to know it timed out routes from the timer's own arc rather than on an outcome.
        run(instanceResource, token, timer);
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
        final Resource container = host.getChild(INSTANCES);
        if (container == null) {
            throw new WorkflowDefinitionException("The resource " + host.getPath()
                + " cannot hold workflows: it is not wf:WorkflowAttachable");
        }
        final String name = Objects.requireNonNullElse(
            NodeNameUtils.findFreeName(container, definitionName(version)), version.getName());
        final Resource instance = this.resolver.create(container, name, Map.of(
            TYPE, "wf:WorkflowInstance",
            STATUS, "active",
            START_TIME, Calendar.getInstance()));
        // Through the JCR API: the node type declares a strict REFERENCE, and a string carrying the right
        // identifier is still the wrong type as far as the commit is concerned
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
     * The name to give an instance: the workflow's own, so that a host carrying several of them reads clearly.
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
        final Resource existing = instance.getChild(OUTCOME);
        if (existing == null) {
            this.resolver.create(instance, OUTCOME, Map.of(
                TYPE, "wf:Variable", "dataType", "string", "stringValue", outcome));
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
    static Resource hostOf(final Resource instance)
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
     * How the runner performs a service task it meets along the way. The engine supplies this rather than the
     * runner reaching for handlers itself, so that performing a service task means the same thing whether it was
     * met in a system workflow or in a running instance.
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

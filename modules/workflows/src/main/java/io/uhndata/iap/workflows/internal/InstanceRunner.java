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
import java.util.Comparator;
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
import io.uhndata.iap.workflows.models.Gateway;
import io.uhndata.iap.workflows.models.IntermediateCatchingEvent;
import io.uhndata.iap.workflows.models.SequenceFlow;
import io.uhndata.iap.workflows.models.StartEvent;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowInstance;
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
 * <p>One token at a time: parallel branches are a later slice, so a gateway picks exactly one way onwards.</p>
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

    /** How many nodes one delivery may pass through before the definition is declared broken. */
    private static final int MAX_STEPS = 50;

    private static final String TYPE = "jcr:primaryType";

    private static final String STATUS = "status";

    private static final String COMPLETED = "completed";

    private static final String CURRENT_NODE_ID = "currentNodeId";

    private static final String START_TIME = "startTime";

    private static final String END_TIME = "endTime";

    private final ResourceResolver resolver;

    private final ServiceTaskPerformer performer;

    private final String actor;

    private final ConditionEvaluator conditions;

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
        this.conditions = conditions;
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
        final List<StartEvent> starts = version.getStartEvents();
        if (starts.size() != 1) {
            throw new WorkflowDefinitionException("A workflow needs exactly one start event to be instantiated, but "
                + version.getPath() + " has " + starts.size());
        }
        final Resource instance = createInstance(host, version);
        final Resource token = this.resolver.create(instance, "token", Map.of(
            TYPE, "wf:WorkflowToken", CURRENT_NODE_ID, starts.get(0).getElementId()));
        run(instance, token, starts.get(0));
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

        run(instanceResource, token, advance(definition, instance));
    }

    /**
     * Walks the instance from a node until it has to stop: a user task to wait at, or an end event to finish on.
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
        FlowNode node = from;
        for (int step = 0; step < MAX_STEPS; step++) {
            modifiable(token).put(CURRENT_NODE_ID, node.getElementId());
            if (node.getHostTag() != null) {
                // On arrival, and for every kind of node: what a host's state is depends on where its process has
                // got to, so the state changes as the token does. A node the walk only passes through therefore
                // leaves a state that lasts an instant, which is exactly right — the lasting ones are on the nodes
                // execution stops at, and those are the ones anybody ever sees.
                HostLifecycle.record(hostOf(instance), node);
            }
            if (node instanceof EndEvent) {
                finish(instance, token);
                return;
            }
            if (node instanceof Activity && ((Activity) node).getHandler() == null) {
                // Nothing can perform it automatically, so it is a user task: park here and wait for a person
                createTask(instance, (Activity) node);
                return;
            }
            if (node instanceof Activity) {
                this.performer.perform((Activity) node, instance);
            } else if (!(node instanceof StartEvent) && !(node instanceof Gateway) && !fired(node)) {
                throw new WorkflowDefinitionException("The engine cannot yet carry an instance through "
                    + node.getPath());
            }
            node = advance(node, adapt(instance));
        }
        throw new WorkflowDefinitionException("The instance " + instance.getPath() + " did not settle within "
            + MAX_STEPS + " steps; its sequence flows probably form a cycle");
    }

    /**
     * Whether the walk is standing on a boundary event because it has just fired, rather than having arrived at
     * something it must wait for.
     *
     * <p>Position is what tells the two apart, and it is enough: an event attached to an activity is never reached
     * by an arc — nothing points at it — so the only way execution can be standing there is that the event
     * happened, and what remains is to leave down its own arc. A free-standing catching event, by contrast, is
     * reached by an arc and must wait, which is precisely what nothing can yet make it stop doing.</p>
     *
     * @param node the node the walk is standing on
     * @return {@code true} if this is a boundary event that has fired
     */
    private static boolean fired(final FlowNode node)
    {
        return node instanceof IntermediateCatchingEvent && ((IntermediateCatchingEvent) node).getActivity() != null;
    }

    /**
     * Follows a node's outgoing arcs. Everything but a gateway has exactly one; a gateway picks between them.
     *
     * @param node the node being left
     * @param instance the running instance, consulted for what a gateway routes on
     * @return the node the chosen arc leads to
     * @throws WorkflowDefinitionException when there is no single resolvable way onwards
     */
    private FlowNode advance(final FlowNode node, final WorkflowInstance instance)
        throws WorkflowDefinitionException
    {
        final List<SequenceFlow> flows = node.getOutgoingFlows();
        final SequenceFlow chosen =
            node instanceof Gateway ? choose((Gateway) node, flows, instance) : only(node, flows);
        final FlowNode next = chosen.getTarget();
        if (next == null) {
            throw new WorkflowDefinitionException("The sequence flow " + chosen.getPath() + " points at "
                + chosen.getTargetRef() + ", which does not exist in this workflow");
        }
        return next;
    }

    /**
     * The single way out of an ordinary node.
     *
     * @param node the node being left
     * @param flows its outgoing arcs
     * @return the only arc
     * @throws WorkflowDefinitionException when there is not exactly one
     */
    private SequenceFlow only(final FlowNode node, final List<SequenceFlow> flows)
        throws WorkflowDefinitionException
    {
        if (flows.size() != 1) {
            throw new WorkflowDefinitionException(node.getPath() + " has " + flows.size()
                + " outgoing sequence flows instead of exactly one");
        }
        return flows.get(0);
    }

    /**
     * Picks a gateway's outgoing arc: the first whose condition holds, or the one marked as the default when none
     * does.
     *
     * <p>The condition is the ordinary structured one, evaluated by the conditions module against the
     * <em>instance</em> — so a guard reads what the execution knows, such as the {@code outcome} the last completed
     * task recorded, through the {@code variable} operand source. An arc with no condition at all holds trivially,
     * which is why the default arc is a separate flag rather than simply the unconditional one: a gateway needs a
     * way to say "otherwise" that does not depend on where in the list it sits.</p>
     *
     * @param gateway the gateway being passed
     * @param flows its outgoing arcs
     * @param instance the running instance, which is what the conditions are asked about
     * @return the arc to follow
     * @throws WorkflowDefinitionException when nothing matches and there is no default
     */
    private SequenceFlow choose(final Gateway gateway, final List<SequenceFlow> flows,
        final WorkflowInstance instance) throws WorkflowDefinitionException
    {
        return flows.stream()
            .filter(flow -> flow.getCondition() != null
                && this.conditions.isSatisfied(flow.getCondition(), instance))
            .findFirst()
            .or(() -> flows.stream().filter(SequenceFlow::isDefault).findFirst())
            .orElseThrow(() -> new WorkflowDefinitionException("No outgoing sequence flow of " + gateway.getPath()
                + " has a condition that holds for " + instance.getPath() + ", and none is marked as the default"));
    }

    /**
     * Ends the instance: the token is spent and the instance is closed. What finishing this way meant to the host
     * has already been recorded by the walk that arrived here, the same way it is for any other node.
     *
     * @param instance the running instance
     * @param token the token that arrived
     * @throws PersistenceException when the instance cannot be written
     */
    private void finish(final Resource instance, final Resource token) throws PersistenceException
    {
        this.resolver.delete(token);
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
            STATUS, "created",
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
        properties.put(STATUS, "cancelled");
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

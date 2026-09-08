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
import java.util.Map;
import java.util.Objects;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.utils.NodeNameUtils;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.EndEvent;
import io.uhndata.iap.workflows.models.FlowNode;
import io.uhndata.iap.workflows.models.Gateway;
import io.uhndata.iap.workflows.models.SequenceFlow;
import io.uhndata.iap.workflows.models.StartEvent;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.Variable;
import io.uhndata.iap.workflows.models.WorkflowInstance;
import io.uhndata.iap.workflows.models.WorkflowInstances;
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
 * <p>This runner creates only one token. Parallel branches are a later slice. A gateway picks exactly one way
 * onwards.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class InstanceRunner
{
    /** The variable a completed task's outcome is recorded under. Gateways route on it. */
    static final String OUTCOME_VARIABLE = "outcome";

    /**
     * How many nodes one delivery may pass through before the definition is declared broken. Far above anything a
     * real workflow needs. It is there so a definition whose arcs form a cycle fails fast instead of spinning.
     */
    static final int MAX_STEPS = 50;

    private static final String JCR_PRIMARY_TYPE_PROPERTY = "jcr:primaryType";

    private static final String STATUS_PROPERTY = "status";

    private static final String COMPLETED_STATUS = "completed";

    private static final String CURRENT_NODE_ID_PROPERTY = "currentNodeId";

    private static final String START_TIME_PROPERTY = "startTime";

    private static final String END_TIME_PROPERTY = "endTime";

    private final ResourceResolver resolver;

    private final ServiceTaskPerformer performer;

    private final String actor;

    /**
     * Constructor.
     *
     * @param resolver the engine's own session, which everything is read and written through
     * @param performer how a service task met along the way gets performed
     * @param actor the user whose action is moving this instance
     */
    InstanceRunner(final ResourceResolver resolver, final ServiceTaskPerformer performer, final String actor)
    {
        this.resolver = resolver;
        this.performer = performer;
        this.actor = actor;
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
            JCR_PRIMARY_TYPE_PROPERTY, "wf:WorkflowToken", CURRENT_NODE_ID_PROPERTY, starts.get(0).getElementId()));
        run(instance, token, starts.get(0));
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
            modifiable(token).put(CURRENT_NODE_ID_PROPERTY, node.getElementId());
            if (node instanceof EndEvent) {
                finish(instance, token, (EndEvent) node);
                return;
            }
            if (node instanceof Activity && ((Activity) node).getHandler() == null) {
                // No handler means a user task. Park here and wait for a person.
                createTask(instance, (Activity) node);
                return;
            }
            if (node instanceof Activity) {
                this.performer.perform((Activity) node, instance);
            } else if (!(node instanceof StartEvent) && !(node instanceof Gateway)) {
                throw new WorkflowDefinitionException("The engine cannot yet carry an instance through "
                    + node.getPath());
            }
            node = advance(node, adapt(instance));
        }
        throw new WorkflowDefinitionException("The instance " + instance.getPath() + " did not settle within "
            + MAX_STEPS + " steps; its sequence flows probably form a cycle");
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
     * Picks a gateway's outgoing arc.
     *
     * <p>Interim semantics, until the conditions module lands. An arc is taken when its
     * {@code conditionExpression} equals the instance's {@code outcome} variable, which holds what the last person
     * to complete a task decided. The arc marked as the default is taken when none matches. That covers the
     * approve-or-reject shape every review process has, and nothing more.</p>
     *
     * @param gateway the gateway being passed
     * @param flows its outgoing arcs
     * @param instance the running instance
     * @return the arc to follow
     * @throws WorkflowDefinitionException when nothing matches and there is no default
     */
    private SequenceFlow choose(final Gateway gateway, final List<SequenceFlow> flows,
        final WorkflowInstance instance) throws WorkflowDefinitionException
    {
        final Variable recorded = instance.getVariable(OUTCOME_VARIABLE);
        final Object outcome = recorded == null ? null : recorded.getValue();
        return flows.stream()
            // Asked this way round: an unrecorded outcome must match nothing, not every arc without a condition
            .filter(flow -> outcome != null && outcome.equals(flow.getConditionExpression()))
            .findFirst()
            .or(() -> flows.stream().filter(SequenceFlow::isDefault).findFirst())
            .orElseThrow(() -> new WorkflowDefinitionException("No outgoing sequence flow of " + gateway.getPath()
                + " matches the outcome " + outcome + ", and none is marked as the default"));
    }

    /**
     * Ends the instance. The token is spent. An end event that says what finishing this way means also tells the
     * host.
     *
     * @param instance the running instance
     * @param token the token that arrived
     * @param end the end event reached
     * @throws PersistenceException when the instance cannot be written
     */
    private void finish(final Resource instance, final Resource token, final EndEvent end)
        throws PersistenceException
    {
        this.resolver.delete(token);
        final ModifiableValueMap properties = modifiable(instance);
        properties.put(STATUS_PROPERTY, COMPLETED_STATUS);
        properties.put(END_TIME_PROPERTY, Calendar.getInstance());
        if (end.getHostTag() != null) {
            modifiable(host(instance)).put(STATUS_PROPERTY, end.getHostTag());
        }
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
        this.resolver.create(instance, name, Map.of(
            JCR_PRIMARY_TYPE_PROPERTY, "wf:TaskInstance",
            "taskDefinitionId", activity.getElementId(),
            "label", Objects.requireNonNullElse(activity.getLabel(), activity.getElementId()),
            STATUS_PROPERTY, "created",
            START_TIME_PROPERTY, Calendar.getInstance()));
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

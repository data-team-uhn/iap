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
import java.util.Calendar;
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

import io.uhndata.iap.tags.models.Taggable;
import io.uhndata.iap.utils.NodeNameUtils;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.EndEvent;
import io.uhndata.iap.workflows.models.FlowNode;
import io.uhndata.iap.workflows.models.IntermediateCatchingEvent;
import io.uhndata.iap.workflows.models.SequenceFlow;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowInstance;
import io.uhndata.iap.workflows.models.WorkflowInstances;
import io.uhndata.iap.workflows.models.WorkflowToken;
import io.uhndata.iap.workflows.models.WorkflowVersion;

/**
 * Reads and writes the nodes a running user workflow keeps: the {@code wf:WorkflowInstance} inside the host, its
 * {@code wf:WorkflowToken}s, its {@code wf:TaskInstance}s, and the outcome variable its gateways route on.
 *
 * <p>Every write goes through the engine's own session and is committed by whoever runs the walk. Deciding where
 * tokens go is {@link InstanceRunner}'s job; this only records it.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class InstanceRecords
{
    /** The variable a completed task's outcome is recorded under. Gateways route on it. */
    static final String OUTCOME_VARIABLE = "outcome";

    private static final String JCR_PRIMARY_TYPE_PROPERTY = "jcr:primaryType";

    private final ResourceResolver resolver;

    /**
     * Constructor.
     *
     * @param resolver the engine's own session, which everything is read and written through
     */
    InstanceRecords(final ResourceResolver resolver)
    {
        this.resolver = resolver;
    }

    /**
     * Creates the instance node inside the host's workflow container. Its name is the workflow's own, so a host
     * carrying several reads clearly.
     *
     * @param host the resource the workflow drives
     * @param version the version being instantiated
     * @return the created instance resource
     * @throws WorkflowDefinitionException when the host cannot hold workflows
     * @throws PersistenceException when the instance cannot be written
     */
    Resource createInstance(final Resource host, final WorkflowVersion version)
        throws WorkflowDefinitionException, PersistenceException
    {
        final Resource container = host.getChild(WorkflowInstances.NODE_NAME);
        if (container == null) {
            throw new WorkflowDefinitionException("The resource " + host.getPath()
                + " cannot hold workflows: it is not wf:WorkflowAttachable");
        }
        final Resource versionResource = getResource(version.getPath());
        final String definitionName = Objects.requireNonNull(versionResource.getParent(),
            "A workflow version always lives inside its definition").getName();
        final Resource instance = this.resolver.create(container, NodeNameUtils.findFreeName(container, definitionName),
            Map.of(JCR_PRIMARY_TYPE_PROPERTY, "wf:WorkflowInstance",
                WorkflowInstance.STATUS_PROPERTY, WorkflowInstance.ACTIVE_STATUS,
                WorkflowInstance.START_TIME_PROPERTY, Calendar.getInstance()));
        // Through the JCR API. The node type declares a strict REFERENCE, and Oak rejects a string carrying the
        // right identifier as the wrong type
        final Node node = Objects.requireNonNull(instance.adaptTo(Node.class),
            "A freshly created instance is always backed by a JCR node");
        final Node target = Objects.requireNonNull(versionResource.adaptTo(Node.class),
            "A workflow version is always backed by a JCR node");
        try {
            node.setProperty("workflowVersion", target);
        } catch (final RepositoryException e) {
            throw new PersistenceException("Could not point the instance at its workflow version", e);
        }
        return instance;
    }

    /**
     * Creates a token resting on a node.
     *
     * @param instance the running instance
     * @param elementId the node it starts on
     * @return the created token's resource
     * @throws PersistenceException when it cannot be written
     */
    Resource createToken(final Resource instance, final String elementId) throws PersistenceException
    {
        return this.resolver.create(instance, NodeNameUtils.findFreeName(instance, "token"), Map.of(
            JCR_PRIMARY_TYPE_PROPERTY, "wf:WorkflowToken", WorkflowToken.CURRENT_NODE_ID_PROPERTY, elementId));
    }

    /**
     * Records that a token has reached a node.
     *
     * @param token the token's resource
     * @param node the node it has reached
     * @param arrivedBy the flow it took to get there, or {@code null} if it did not arrive along one
     */
    void moveToken(final Resource token, final FlowNode node, final SequenceFlow arrivedBy)
    {
        final ModifiableValueMap position = getModifiableProperties(token);
        position.put(WorkflowToken.CURRENT_NODE_ID_PROPERTY, node.getElementId());
        if (arrivedBy == null) {
            position.remove(WorkflowToken.ARRIVED_BY_PROPERTY);
        } else {
            position.put(WorkflowToken.ARRIVED_BY_PROPERTY, arrivedBy.getElementId());
        }
    }

    /**
     * Deletes a token merged into another at a join.
     *
     * @param token the token to delete
     * @throws PersistenceException when it cannot be deleted
     */
    void deleteToken(final WorkflowToken token) throws PersistenceException
    {
        this.resolver.delete(getResource(token.getPath()));
    }

    /**
     * Spends a token on the end event it reached, ending that branch. An end event ends a branch, not the process,
     * so the instance closes only when its last token is gone.
     *
     * @param instance the running instance
     * @param token the token that arrived
     * @throws PersistenceException when the instance cannot be written
     */
    void spendToken(final Resource instance, final Resource token) throws PersistenceException
    {
        this.resolver.delete(token);
        if (asInstance(instance).getTokens().isEmpty()) {
            closeInstance(instance);
        }
    }

    /**
     * The tokens resting on a node.
     *
     * @param instance the running instance
     * @param elementId the node to look at
     * @return the tokens, in the order the instance holds them
     */
    List<WorkflowToken> findTokensAt(final Resource instance, final String elementId)
    {
        return asInstance(instance).getTokens().stream()
            .filter(candidate -> elementId.equals(candidate.getCurrentNodeId()))
            .toList();
    }

    /**
     * The token resting on a given node.
     *
     * @param instance the running instance
     * @param elementId the node the token should be on
     * @return the token's resource
     * @throws WorkflowDefinitionException when no token is there
     */
    Resource findTokenAt(final Resource instance, final String elementId) throws WorkflowDefinitionException
    {
        return findTokensAt(instance, elementId).stream()
            .findFirst()
            .map(candidate -> getResource(candidate.getPath()))
            .orElseThrow(() -> new WorkflowDefinitionException("The instance " + instance.getPath()
                + " is no longer waiting at " + elementId));
    }

    /**
     * Where the tokens that are not on a given node are standing. An inclusive join uses this to tell which branches
     * can still reach it.
     *
     * @param instance the running instance
     * @param elementId the node to leave out
     * @return the nodes the other tokens are on, ignoring any whose node is no longer in the workflow
     */
    List<FlowNode> findOtherTokenPositions(final Resource instance, final String elementId)
    {
        return asInstance(instance).getTokens().stream()
            .filter(token -> !elementId.equals(token.getCurrentNodeId()))
            .map(WorkflowToken::getCurrentNode)
            .filter(Objects::nonNull)
            .toList();
    }

    /**
     * Tags the host with what reaching an end event means, if the end event says so. Every branch that reaches an
     * end event does this, not only the last one.
     *
     * @param instance the running instance
     * @param end the end event reached
     * @throws PersistenceException when the host cannot be tagged
     */
    void tagHost(final Resource instance, final EndEvent end) throws PersistenceException
    {
        final String hostTag = end.getHostTag();
        if (hostTag != null) {
            // Lifecycle tags are system tags, and placing one is the engine's job, as it is the tag tasks'
            Objects.requireNonNull(hostOf(instance).adaptTo(Taggable.class), "A workflow's host is taggable")
                .tag(hostTag, true);
        }
    }

    /**
     * The resource a workflow instance drives, two levels up past its container.
     *
     * @param instance a workflow instance
     * @return the host resource
     */
    static Resource hostOf(final Resource instance)
    {
        return Objects.requireNonNull(Objects.requireNonNull(instance.getParent(),
            "An instance always lives in a container").getParent(), "A container always lives in its host");
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
    void terminateInstance(final Resource instance) throws PersistenceException
    {
        final WorkflowInstance model = asInstance(instance);
        for (final WorkflowToken token : model.getTokens()) {
            deleteToken(token);
        }
        for (final TaskInstance task : model.getTaskInstances()) {
            if (TaskInstance.OPEN_STATUS.equals(task.getStatus())) {
                cancelTask(task);
            }
        }
        closeInstance(instance);
    }

    /**
     * Marks an instance as finished.
     *
     * @param instance the instance to close
     */
    private void closeInstance(final Resource instance)
    {
        final ModifiableValueMap properties = getModifiableProperties(instance);
        properties.put(WorkflowInstance.STATUS_PROPERTY, WorkflowInstance.COMPLETED_STATUS);
        properties.put(WorkflowInstance.END_TIME_PROPERTY, Calendar.getInstance());
    }

    /**
     * Creates the task a person now has to do; the token stays on it until they do.
     *
     * @param instance the running instance
     * @param activity the user task reached
     * @throws PersistenceException when the task cannot be written
     */
    void createTask(final Resource instance, final Activity activity) throws PersistenceException
    {
        final Calendar started = Calendar.getInstance();
        final Map<String, Object> properties = new HashMap<>(Map.of(
            JCR_PRIMARY_TYPE_PROPERTY, "wf:TaskInstance",
            "taskDefinitionId", activity.getElementId(),
            "label", Objects.requireNonNullElse(activity.getLabel(), activity.getElementId()),
            "offeredOutcomes", activity.getOutcomes().toArray(String[]::new),
            TaskInstance.STATUS_PROPERTY, TaskInstance.OPEN_STATUS,
            TaskInstance.START_TIME_PROPERTY, started));
        armNextDeadline(activity, started, List.of(), properties);
        this.resolver.create(instance, NodeNameUtils.findFreeName(instance, activity.getName()), properties);
    }

    /**
     * Records that a person completed a task, and the outcome they chose, which the instance's gateways then route
     * on.
     *
     * @param instance the task's instance
     * @param task the task completed
     * @param actor the user who completed it
     * @param outcome what they decided, or {@code null} for a task that offers no choice
     * @throws PersistenceException when the task or the outcome cannot be written
     */
    void completeTask(final Resource instance, final TaskInstance task, final String actor, final String outcome)
        throws PersistenceException
    {
        final ModifiableValueMap properties = getModifiableProperties(getResource(task.getPath()));
        properties.put(TaskInstance.STATUS_PROPERTY, TaskInstance.COMPLETED_STATUS);
        properties.put(TaskInstance.END_TIME_PROPERTY, Calendar.getInstance());
        properties.put("assignee", actor);
        if (outcome != null) {
            properties.put(OUTCOME_VARIABLE, outcome);
            recordOutcome(instance, outcome);
        }
    }

    /**
     * Records that a task ended without anyone completing it. It gets no assignee and no outcome: nobody acted, and
     * nothing was decided.
     *
     * @param task the task cancelled
     */
    void cancelTask(final TaskInstance task)
    {
        final ModifiableValueMap properties = getModifiableProperties(getResource(task.getPath()));
        properties.put(TaskInstance.STATUS_PROPERTY, TaskInstance.CANCELLED_STATUS);
        properties.put(TaskInstance.END_TIME_PROPERTY, Calendar.getInstance());
    }

    /**
     * Records that a non-interrupting timer has fired on a task that stays open, and arms its next deadline, if any.
     *
     * @param task the task the timer watches
     * @param definition the activity the task was raised from
     * @param timer the timer that fired
     */
    void recordFiredTimer(final TaskInstance task, final Activity definition, final IntermediateCatchingEvent timer)
    {
        final ModifiableValueMap properties = getModifiableProperties(getResource(task.getPath()));
        final List<String> fired = new ArrayList<>(task.getFiredEvents());
        fired.add(timer.getElementId());
        properties.put("firedEvents", fired.toArray(String[]::new));
        armNextDeadline(definition, Objects.requireNonNullElseGet(task.getStartTime(), Calendar::getInstance), fired,
            properties);
    }

    /**
     * Starts the clock on the deadline a boundary timer gives a task, if one watches it.
     *
     * <p>The deadline is written onto the task, where anything looking for overdue work can find it without running
     * the engine. The earliest timer that has not fired yet is the one armed.</p>
     *
     * <p>Every duration counts from when the task started, not from now. "Remind them after three days, give up after
     * five" means five days from the start, not from the reminder. Days and months are counted in the server's own
     * calendar, so a deadline re-armed from the stored start time lands where the first one would have.</p>
     *
     * @param activity the user task
     * @param started when the task began waiting, which every deadline is measured from
     * @param fired the events that have already fired and are not to be armed again
     * @param properties the task's properties, added to in place
     */
    private static void armNextDeadline(final Activity activity, final Calendar started, final List<String> fired,
        final Map<String, Object> properties)
    {
        final Calendar local = Calendar.getInstance();
        local.setTimeInMillis(started.getTimeInMillis());
        final IntermediateCatchingEvent timer = activity.getNextTimer(local, fired);
        if (timer == null) {
            // No deadline is left, so the sweep stops finding this task
            properties.remove("dueDate");
            properties.remove("dueEventId");
        } else {
            properties.put("dueDate", timer.getDeadline(local));
            properties.put("dueEventId", timer.getElementId());
        }
    }

    /**
     * Records the outcome the instance's gateways will route on, replacing any earlier one.
     *
     * @param instance the running instance
     * @param outcome the outcome to record
     * @throws PersistenceException when the variable cannot be written
     */
    private void recordOutcome(final Resource instance, final String outcome) throws PersistenceException
    {
        final Resource existing = instance.getChild(OUTCOME_VARIABLE);
        if (existing == null) {
            this.resolver.create(instance, OUTCOME_VARIABLE, Map.of(
                JCR_PRIMARY_TYPE_PROPERTY, "wf:Variable", "dataType", "string", "stringValue", outcome));
        } else {
            getModifiableProperties(existing).put("stringValue", outcome);
        }
    }

    /**
     * The resource at a path the engine has already seen, so it is certainly there.
     *
     * @param path the path to resolve
     * @return the resource
     */
    Resource getResource(final String path)
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
    WorkflowInstance asInstance(final Resource instance)
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
    private ModifiableValueMap getModifiableProperties(final Resource resource)
    {
        return Objects.requireNonNull(resource.adaptTo(ModifiableValueMap.class),
            "A node the engine is writing is always modifiable");
    }
}

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

import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.utils.UserIds;
import io.uhndata.iap.workflows.api.NoApplicableWorkflowException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.models.StartEvent;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowVersion;

/**
 * What the engine answers without changing anything: which events a user could send to a target, and which
 * system workflow would handle one of them.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class WorkflowQueries
{
    private WorkflowQueries()
    {
    }

    /**
     * The events an actor could send to a target right now.
     *
     * @param serviceResolver the engine's own session
     * @param target the target, backed by the engine's session
     * @param actor the asking user
     * @param evaluator decides whether guards hold
     * @return the event names, in alphabetical order
     * @throws WorkflowException when the actor cannot be looked up
     */
    static Set<String> availableEvents(final ResourceResolver serviceResolver, final Resource target,
        final String actor, final ConditionEvaluator evaluator) throws WorkflowException
    {
        final PerformerCheck performers = PerformerCheck.of(serviceResolver, actor);
        if (target.isResourceType(TaskInstance.RESOURCE_TYPE)) {
            return TaskCompletion.availableEvents(target, performers);
        }
        return SystemWorkflowLocator.availableEvents(serviceResolver, target, evaluator, performers);
    }

    /**
     * The system workflow that would handle an event from the asking user, as their own session reads it. Which one
     * is decided through the engine's session, as receiving the event would decide it, since a guard may look at
     * content the user cannot read. Only the answer is handed over to the user's session.
     *
     * @param serviceResolver the engine's own session
     * @param target the target, backed by the engine's session
     * @param asking the asking user's own session, which the workflow is read through
     * @param evaluator decides whether guards hold
     * @param event the event's name
     * @return the workflow version, or {@code null} when no system workflow would take the event from the user
     * @throws WorkflowException when the user cannot be looked up, several workflows would take the event, or the
     *             user's session cannot read the one that would
     */
    static WorkflowVersion applicableWorkflow(final ResourceResolver serviceResolver, final Resource target,
        final ResourceResolver asking, final ConditionEvaluator evaluator, final String event)
        throws WorkflowException
    {
        if (target.isResourceType(TaskInstance.RESOURCE_TYPE)) {
            return null;
        }
        final StartEvent start;
        try {
            start = SystemWorkflowLocator.find(serviceResolver, target, new WorkflowEvent(event, Map.of()), evaluator);
        } catch (final NoApplicableWorkflowException e) {
            return null;
        }
        if (!PerformerCheck.of(serviceResolver, UserIds.canonical(asking)).admits(start)) {
            return null;
        }
        final String path = Objects.requireNonNull(start.getWorkflowVersion(),
            "A start event found in a workflow version belongs to it").getPath();
        final Resource version = asking.getResource(path);
        if (version == null) {
            throw new WorkflowFailedException("The system workflow " + path + " is not readable by "
                + UserIds.canonical(asking)
                + "; the repository lacks the grant that lets everyone read /SystemWorkflows");
        }
        return Objects.requireNonNull(version.adaptTo(WorkflowVersion.class),
            "A wf:WorkflowVersion resource always adapts to its model");
    }
}

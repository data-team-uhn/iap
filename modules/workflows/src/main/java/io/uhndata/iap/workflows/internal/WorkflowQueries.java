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
import java.util.Set;
import java.util.function.Function;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.workflows.api.NoApplicableWorkflowException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
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
     * Reads the system workflow that would handle an event from an actor, if any.
     *
     * @param <T> what the reader makes of it
     * @param serviceResolver the engine's own session
     * @param target the target, backed by the engine's session
     * @param actor the asking user
     * @param evaluator decides whether guards hold
     * @param event the event's name
     * @param reader what to read from the workflow version
     * @return what the reader returned, or {@code null} when no system workflow would take the event
     * @throws WorkflowException when the actor cannot be looked up
     */
    static <T> T inspect(final ResourceResolver serviceResolver, final Resource target, final String actor,
        final ConditionEvaluator evaluator, final String event, final Function<WorkflowVersion, T> reader)
        throws WorkflowException
    {
        if (target.isResourceType(TaskInstance.RESOURCE_TYPE)) {
            return null;
        }
        final StartEvent start;
        try {
            start = SystemWorkflowLocator.find(serviceResolver, target, new WorkflowEvent(event, Map.of()), evaluator);
        } catch (final NoApplicableWorkflowException | WorkflowDefinitionException e) {
            return null;
        }
        return PerformerCheck.of(serviceResolver, actor).admits(start)
            ? reader.apply(start.getWorkflowVersion()) : null;
    }
}

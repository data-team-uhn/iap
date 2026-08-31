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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.workflows.api.NoApplicableWorkflowException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.models.StartEvent;
import io.uhndata.iap.workflows.models.SystemWorkflowsHomepage;
import io.uhndata.iap.workflows.models.WorkflowDefinition;
import io.uhndata.iap.workflows.models.WorkflowVersion;

/**
 * Finds the system workflow waiting for an event: an active version of an active definition under
 * {@code /SystemWorkflows}, declaring the target's resource type, with a start event catching the event's name
 * whose guard holds for the target. Exactly one may be waiting. None means the event is not acceptable here, at
 * least not in the target's current state; several mean the installed definitions contradict each other.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class SystemWorkflowLocator
{
    private SystemWorkflowLocator()
    {
    }

    /**
     * Finds the single start event waiting for this event on this target.
     *
     * @param serviceResolver the engine's own session, able to read the system workflows tree
     * @param target the resource the event is aimed at, backed by the engine's own session
     * @param event the incoming event
     * @param evaluator decides whether a start event's guard holds
     * @return the matched start event, backed by the service session
     * @throws NoApplicableWorkflowException when nothing is waiting for this event here
     * @throws WorkflowDefinitionException when several start events compete for it
     */
    static StartEvent find(final ResourceResolver serviceResolver, final Resource target, final WorkflowEvent event,
        final ConditionEvaluator evaluator) throws WorkflowException
    {
        final List<StartEvent> matches = waiting(serviceResolver, target, evaluator, event.getName()::equals);
        if (matches.isEmpty()) {
            throw new NoApplicableWorkflowException(
                "Nothing accepts the event " + event.getName() + " on " + target.getPath());
        }
        if (matches.size() > 1) {
            throw contested(target, event.getName(), matches);
        }
        return matches.get(0);
    }

    /**
     * The events an actor could send to this target right now: those caught by a start event waiting on it in its
     * current state, and admitting the actor.
     *
     * @param serviceResolver the engine's own session, able to read the system workflows tree
     * @param target the resource events would be aimed at, backed by the engine's own session
     * @param evaluator decides whether a start event's guard holds
     * @param performers who is asking
     * @return the event names, in alphabetical order
     * @throws WorkflowDefinitionException when several start events wait for one event, which receiving it would
     *             refuse
     * @throws WorkflowFailedException when the actor's group membership cannot be read
     */
    static Set<String> availableEvents(final ResourceResolver serviceResolver, final Resource target,
        final ConditionEvaluator evaluator, final PerformerCheck performers) throws WorkflowException
    {
        final Map<String, List<StartEvent>> byEvent = waiting(serviceResolver, target, evaluator, event -> true)
            .stream()
            .collect(Collectors.groupingBy(StartEvent::getMessageName));
        final Set<String> events = new TreeSet<>();
        for (final Map.Entry<String, List<StartEvent>> waitingFor : byEvent.entrySet()) {
            // Not available but broken: the engine would refuse it, whoever sent it
            if (waitingFor.getValue().size() > 1) {
                throw contested(target, waitingFor.getKey(), waitingFor.getValue());
            }
            if (performers.admits(waitingFor.getValue().get(0), target)) {
                events.add(waitingFor.getKey());
            }
        }
        return events;
    }

    /**
     * The refusal of an event several system workflows wait for: the installed definitions contradict each other.
     *
     * @param target the resource the event is aimed at
     * @param event the event's name
     * @param starts the start events all waiting for it
     * @return the failure to throw
     */
    private static WorkflowDefinitionException contested(final Resource target, final String event,
        final List<StartEvent> starts)
    {
        return new WorkflowDefinitionException("The event " + event + " on " + target.getPath()
            + " is caught by several system workflows: "
            + starts.stream().map(StartEvent::getPath).collect(Collectors.joining(", ")));
    }

    /**
     * The start events waiting on this target in its current state, among those catching the events asked about.
     * Guards are evaluated last, and only for those, since evaluating one reads the target's content.
     *
     * @param serviceResolver the engine's own session, able to read the system workflows tree
     * @param target the resource events would be aimed at, backed by the engine's own session
     * @param evaluator decides whether a start event's guard holds
     * @param asked which event names are asked about
     * @return the start events catching one of them whose guard holds, backed by the service session
     */
    private static List<StartEvent> waiting(final ResourceResolver serviceResolver, final Resource target,
        final ConditionEvaluator evaluator, final Predicate<String> asked)
    {
        final Content context = target.adaptTo(Content.class);
        final Resource home = serviceResolver.getResource(SystemWorkflowsHomepage.PATH);
        final SystemWorkflowsHomepage homepage = home == null ? null : home.adaptTo(SystemWorkflowsHomepage.class);
        return homepage == null ? List.of()
            : homepage.getWorkflows().stream()
                .filter(WorkflowDefinition::isActive)
                .flatMap(definition -> definition.getVersions().stream())
                .filter(WorkflowVersion::isActive)
                .filter(version -> version.getTargetResourceType() != null
                    && target.isResourceType(version.getTargetResourceType()))
                .flatMap(version -> version.getStartEvents().stream())
                .filter(start -> start.getMessageName() != null && asked.test(start.getMessageName()))
                .filter(start -> start.getCondition() == null
                    || context != null && evaluator.applies(start, context))
                .collect(Collectors.toList());
    }
}

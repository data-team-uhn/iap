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
import java.util.stream.Collectors;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.workflows.api.NoApplicableWorkflowException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.StartEvent;
import io.uhndata.iap.workflows.models.SystemWorkflowsHomepage;
import io.uhndata.iap.workflows.models.WorkflowDefinition;
import io.uhndata.iap.workflows.models.WorkflowVersion;

/**
 * Finds the system workflow waiting for an event: an active version of an active definition under
 * {@code /SystemWorkflows}, declaring the target's resource type, with a start event catching the event's name.
 * Exactly one may be waiting — none means the event is not acceptable here, several mean the installed
 * definitions contradict each other.
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
     * @param target the resource the event is aimed at
     * @param event the incoming event
     * @return the matched start event, backed by the service session
     * @throws NoApplicableWorkflowException when nothing is waiting for this event here
     * @throws WorkflowDefinitionException when several start events compete for it
     */
    static StartEvent find(final ResourceResolver serviceResolver, final Resource target, final WorkflowEvent event)
        throws WorkflowException
    {
        final Resource home = serviceResolver.getResource(SystemWorkflowsHomepage.PATH);
        final SystemWorkflowsHomepage homepage = home == null ? null : home.adaptTo(SystemWorkflowsHomepage.class);
        final List<StartEvent> matches = homepage == null ? List.of()
            : homepage.getWorkflows().stream()
                .filter(WorkflowDefinition::isActive)
                .flatMap(definition -> definition.getVersions().stream())
                .filter(WorkflowVersion::isActive)
                .filter(version -> version.getTargetResourceType() != null
                    && target.isResourceType(version.getTargetResourceType()))
                .flatMap(version -> version.getStartEvents().stream())
                .filter(start -> event.getName().equals(start.getMessageName()))
                .collect(Collectors.toList());
        if (matches.isEmpty()) {
            throw new NoApplicableWorkflowException(
                "Nothing accepts the event " + event.getName() + " on " + target.getPath());
        }
        if (matches.size() > 1) {
            throw new WorkflowDefinitionException("The event " + event.getName() + " on " + target.getPath()
                + " is caught by several system workflows: "
                + matches.stream().map(StartEvent::getPath).collect(Collectors.joining(", ")));
        }
        return matches.get(0);
    }
}

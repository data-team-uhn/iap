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
package io.uhndata.iap.extraction.internal;

import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Stands in for the engine: a task context aimed at a resource, carrying an event and a bag of variables the
 * handlers can leave things in for each other.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class TaskContexts
{
    private TaskContexts()
    {
        // Utility
    }

    /**
     * A context for a service task that carries no properties of its own.
     *
     * @param target what the event is about
     * @param payload what the event carries
     * @param variables what earlier steps left behind; filled in by what this step leaves
     * @return the context
     */
    static WorkflowTaskContext of(final Resource target, final Map<String, Object> payload,
        final Map<String, Object> variables)
    {
        return of(target, payload, variables, Map.of());
    }

    /**
     * A context for a service task, including what the step itself says.
     *
     * @param target what the event is about
     * @param payload what the event carries
     * @param variables what earlier steps left behind; filled in by what this step leaves
     * @param properties what the activity on the diagram carries, such as the requirement it names
     * @return the context
     */
    static WorkflowTaskContext of(final Resource target, final Map<String, Object> payload,
        final Map<String, Object> variables, final Map<String, Object> properties)
    {
        // Built before it is stubbed in: Mockito rejects a mock created inside a when(...) argument
        final Activity activity = activity(properties);
        final WorkflowTaskContext context = Mockito.mock(WorkflowTaskContext.class);
        Mockito.when(context.getActivity()).thenReturn(activity);
        Mockito.when(context.getTarget()).thenReturn(target);
        Mockito.when(context.getResourceResolver()).thenReturn(target.getResourceResolver());
        Mockito.when(context.getActor()).thenReturn("demo-researcher");
        Mockito.when(context.getEvent()).thenReturn(new WorkflowEvent("test", payload));
        Mockito.when(context.getVariable(Mockito.anyString()))
            .thenAnswer(invocation -> variables.get(invocation.<String>getArgument(0)));
        Mockito.doAnswer(invocation -> {
            variables.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(context).setVariable(Mockito.anyString(), Mockito.any());
        return context;
    }

    /**
     * The step on the diagram, answering the properties it was given and nothing for the rest. Never null: the
     * engine always runs a handler from an activity, and the interface says so.
     */
    private static Activity activity(final Map<String, Object> properties)
    {
        final Activity activity = Mockito.mock(Activity.class);
        // Only the String form: every activity property a handler reads here is one, and a raw Class matcher
        // would make the stubbing unchecked, which this build treats as an error.
        Mockito.when(activity.get(Mockito.anyString(), Mockito.eq(String.class)))
            .thenAnswer(invocation -> {
                final Object value = properties.get(invocation.<String>getArgument(0));
                return value instanceof String[] many ? (many.length == 0 ? null : many[0]) : value;
            });
        // And the list form, for a property that names several things, such as the documents a step reads.
        // A single value reads as a list of one, as a ValueMap converts it.
        Mockito.when(activity.get(Mockito.anyString(), Mockito.eq(String[].class)))
            .thenAnswer(invocation -> {
                final Object value = properties.get(invocation.<String>getArgument(0));
                return value instanceof String one ? new String[] { one } : value;
            });
        return activity;
    }
}

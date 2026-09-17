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
     * A context for a service task.
     *
     * @param target what the event is about
     * @param payload what the event carries
     * @param variables what earlier steps left behind; filled in by what this step leaves
     * @return the context
     */
    static WorkflowTaskContext of(final Resource target, final Map<String, Object> payload,
        final Map<String, Object> variables)
    {
        final WorkflowTaskContext context = Mockito.mock(WorkflowTaskContext.class);
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
}

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

import org.apache.sling.api.resource.Resource;

import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The resource the built-in service tasks act on: the one the execution created, if it created one, or else the
 * resource the event was aimed at.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ExecutionHost
{
    private ExecutionHost()
    {
    }

    /**
     * The resource the executing task acts on.
     *
     * @param context the executing task's context
     * @return the created resource, or the target when nothing was created
     * @throws WorkflowDefinitionException when the recorded path leads nowhere
     */
    static Resource of(final WorkflowTaskContext context) throws WorkflowDefinitionException
    {
        final Object created = context.getVariable(WorkflowResult.CREATED_PATH_VARIABLE);
        if (!(created instanceof String)) {
            return context.getTarget();
        }
        final Resource host = context.getResourceResolver().getResource((String) created);
        if (host == null) {
            throw new WorkflowDefinitionException("Nothing was created at " + created + " to act on");
        }
        return host;
    }
}

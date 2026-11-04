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
package io.uhndata.iap.workflows.spi;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowResult;

/**
 * What a workflow execution is working on: the resource its event was aimed at, the variables its steps left
 * behind, and the session it reads and writes through. A node asks it what to act on.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface Execution
{
    /**
     * The resource the triggering event was aimed at, e.g. the homepage a creation was requested under.
     *
     * @return a resource, backed by the engine's own session
     */
    @NotNull
    Resource getTarget();

    /**
     * One variable of this execution: what an earlier node left behind.
     *
     * @param name the variable name
     * @return the value, or {@code null} if nothing set it
     */
    @Nullable
    Object getVariable(@NotNull String name);

    /**
     * The session to read and write repository content with.
     *
     * <p>It is the engine's own, and it is privileged. The repository will not stop a handler from doing
     * anything, because what the firing user may do was decided from the definition before the handler ran. A
     * handler that wants to treat something as invisible or forbidden has to say so itself.</p>
     *
     * <p>The engine owns the session. Handlers must not commit, revert or close it: the whole execution lands in
     * one commit at quiescence.</p>
     *
     * @return the engine's resource resolver
     */
    @NotNull
    ResourceResolver getResourceResolver();

    /**
     * What this execution has created so far, as an earlier step recorded in
     * {@link WorkflowResult#CREATED_PATH_VARIABLE}.
     *
     * @return the created resource, or {@code null} when nothing was created
     * @throws WorkflowDefinitionException when the recorded path leads nowhere
     */
    @Nullable
    default Resource getCreated() throws WorkflowDefinitionException
    {
        final Object created = getVariable(WorkflowResult.CREATED_PATH_VARIABLE);
        if (!(created instanceof String)) {
            return null;
        }
        final Resource resource = getResourceResolver().getResource((String) created);
        if (resource == null) {
            throw new WorkflowDefinitionException("Nothing was created at " + created + " to act on");
        }
        return resource;
    }
}

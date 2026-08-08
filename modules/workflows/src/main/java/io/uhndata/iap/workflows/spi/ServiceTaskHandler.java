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

import org.apache.sling.api.resource.PersistenceException;
import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.workflows.api.WorkflowException;

/**
 * A piece of work the platform performs on its own when a workflow reaches a service task — creating an entity,
 * calling an external service, sending a notification. The activity node names the handler in its {@code handler}
 * property, and the engine dispatches to the registered component with that {@link #getName() name}; the
 * activity's other properties are the handler's configuration.
 *
 * <p>This is the extension point through which projects plug their own behavior into workflows without touching
 * the platform: register a component with a new name, and any workflow definition may name it.</p>
 *
 * <p>Handlers work inside a transaction they do not own. All writes go through the context's resource resolver
 * and are committed by the engine, in one commit for the whole run to quiescence — a handler that committed on
 * its own would break the promise that an event either fully happened or didn't happen at all.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface ServiceTaskHandler
{
    /**
     * The name activities use to point at this handler, e.g. {@code createEntity}.
     *
     * @return a stable, unique name
     */
    @NotNull
    String getName();

    /**
     * Perform the work. Repository writes go through {@link WorkflowTaskContext#getResourceResolver()} and are
     * left uncommitted for the engine; results for downstream nodes and for the channel that fired the event are
     * recorded with {@link WorkflowTaskContext#setVariable}.
     *
     * @param context what the work is about
     * @throws WorkflowException when the work cannot be done, typed by whose fault that is — most commonly
     *         {@link io.uhndata.iap.workflows.api.InvalidPayloadException} for unusable event data
     * @throws PersistenceException when a repository operation fails; the engine translates repository-level
     *         denials and constraint violations into the matching {@link WorkflowException}
     */
    void execute(@NotNull WorkflowTaskContext context) throws WorkflowException, PersistenceException;
}

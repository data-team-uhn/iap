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
package io.uhndata.iap.schemas.editing.internal.handlers;

import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;

/**
 * Tells whether a workflow's target is a schema, and what to say when it is not.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class SchemaContent
{
    private SchemaContent()
    {
        // Utility class
    }

    /**
     * The target as a schema, if it is one.
     *
     * @param target the workflow's target
     * @return the schema model; {@code null} if the target is not a schema
     */
    @Nullable
    static Schema asSchema(@NotNull final Resource target)
    {
        return target.isResourceType(Schema.RESOURCE_TYPE) ? target.adaptTo(Schema.class) : null;
    }

    /**
     * The refusal for a workflow that runs a handler on a type the handler does not serve: a mistake in the workflow
     * definition.
     *
     * @param handler the handler's name, such as {@code createSchemaVersion}
     * @param target the target it was run against
     * @return the exception to throw
     */
    static WorkflowDefinitionException unsupportedTarget(final String handler, final Resource target)
    {
        return new WorkflowDefinitionException(
            "The " + handler + " handler does not serve " + target.getResourceType());
    }
}

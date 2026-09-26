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
package io.uhndata.iap.schemas.editing.internal;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.schemas.models.LifecycleState;
import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;

/**
 * What the schema handlers share: telling a schema from a version, and writing the {@code lifecycle} tag.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class SchemaContent
{
    private static final String TAGS = "tags";

    private SchemaContent()
    {
        // Utility class
    }

    /**
     * The target as a schema, if it is one.
     *
     * @param target the workflow's target
     * @return the schema model, or {@code null} when the target is something else
     */
    @Nullable
    static Schema asSchema(@NotNull final Resource target)
    {
        return target.isResourceType(Schema.RESOURCE_TYPE) ? target.adaptTo(Schema.class) : null;
    }

    /**
     * The target as a schema version, if it is one.
     *
     * @param target the workflow's target
     * @return the version model, or {@code null} when the target is something else
     */
    @Nullable
    static SchemaVersion asVersion(@NotNull final Resource target)
    {
        return target.isResourceType(SchemaVersion.RESOURCE_TYPE) ? target.adaptTo(SchemaVersion.class) : null;
    }

    /**
     * The refusal for a workflow attached to a type its handler does not serve: a definition mistake.
     *
     * @param handler the handler's name
     * @param target the target it was run against
     * @return the exception to throw
     */
    static WorkflowDefinitionException unsupportedTarget(final String handler, final Resource target)
    {
        return new WorkflowDefinitionException(
            "The " + handler + " handler serves schemas and schema versions, not " + target.getResourceType());
    }

    /**
     * Replaces whatever lifecycle tag the content carries with the given one, leaving its other tags alone. The
     * states are exclusive, and placing a tag does not remove the others by itself.
     *
     * @param target the schema or version to tag
     * @param state the new state, or {@code null} to leave no lifecycle tag at all
     * @throws PersistenceException when the content cannot be modified
     */
    static void setLifecycle(@NotNull final Resource target, @Nullable final LifecycleState state)
        throws PersistenceException
    {
        final ModifiableValueMap values = target.adaptTo(ModifiableValueMap.class);
        if (values == null) {
            throw new PersistenceException("The resource " + target.getPath() + " cannot be modified");
        }
        final Set<String> tags = new LinkedHashSet<>(Arrays.asList(values.get(TAGS, new String[0])));
        Arrays.stream(LifecycleState.values()).map(LifecycleState::getTag).forEach(tags::remove);
        if (state != null) {
            tags.add(state.getTag());
        }
        values.put(TAGS, tags.toArray(new String[0]));
    }
}

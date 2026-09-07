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

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;

import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.workflows.api.WorkflowFailedException;

/**
 * Rewrites the revision token of the entity an event was aimed at. When two commits set the same property, Oak
 * merges them if the values agree and refuses the second if they differ. A value that always differs makes the
 * second of two racing commits fail.
 *
 * <p>A counter will not do: computing the next value means reading the current one, and two writers that read
 * 1 both write 2, which merges.</p>
 *
 * <p>An event may be aimed at a part of an entity, such as a task. The token then goes on the entity enclosing
 * it.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class EntityRevision
{
    private EntityRevision()
    {
    }

    /**
     * Rewrites the token on the nearest entity at or above the target. Does nothing when there is none, or when
     * this session has removed it.
     *
     * @param target the resource the event was aimed at, an entity or a part of one
     * @throws WorkflowFailedException when the engine's session cannot write to the entity
     */
    static void stamp(final Resource target) throws WorkflowFailedException
    {
        final Optional<Resource> entity = enclosingEntity(target);
        if (entity.isPresent()) {
            rewrite(entity.get());
        }
    }

    /**
     * Finds the nearest entity at or above a resource, as its session sees it now.
     *
     * @param target the resource
     * @return the entity, or empty when there is none or the session has removed it
     */
    private static Optional<Resource> enclosingEntity(final Resource target)
    {
        return Stream.iterate(target, Objects::nonNull, Resource::getParent)
            .filter(resource -> resource.isResourceType(Entity.RESOURCE_TYPE))
            .findFirst()
            .map(entity -> entity.getResourceResolver().getResource(entity.getPath()));
    }

    /**
     * Puts a new token on an entity.
     *
     * @param entity the entity
     * @throws WorkflowFailedException when the engine's session cannot write to it
     */
    private static void rewrite(final Resource entity) throws WorkflowFailedException
    {
        final ModifiableValueMap properties = entity.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            throw new WorkflowFailedException("The workflow engine cannot write to " + entity.getPath()
                + "; its service user is missing rights");
        }
        properties.put(Entity.REVISION_PROPERTY, UUID.randomUUID().toString());
    }
}

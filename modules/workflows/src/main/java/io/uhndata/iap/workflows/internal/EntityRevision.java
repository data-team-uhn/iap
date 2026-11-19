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

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;

import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.workflows.api.WorkflowFailedException;

/**
 * Rewrites the revision token of each entity a delivery changed. When two commits set the same property, Oak
 * merges them if the values agree and refuses the second if they differ. A value that always differs makes the
 * second of two racing commits fail.
 *
 * <p>A counter will not do: computing the next value means reading the current one, and two writers that read
 * 1 both write 2, which merges.</p>
 *
 * <p>A delivery may change a part of an entity, such as a task. The token then goes on the entity enclosing it. A
 * removed node changed the entity that held it.</p>
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
     * Rewrites the token on the nearest entity at or above each changed resource. A resource part of no entity changes
     * no token.
     *
     * @param resolver the engine's session, holding the delivery's changes
     * @param changed the absolute paths of the resources the delivery changed, entities or parts of one; a path this
     *            session has removed stands for the nearest node above it that remains
     * @throws WorkflowFailedException when the engine's session cannot write to an entity
     */
    static void stamp(final ResourceResolver resolver, final Collection<String> changed)
        throws WorkflowFailedException
    {
        for (final Resource entity : enclosingEntities(resolver, changed)) {
            rewrite(entity);
        }
    }

    /**
     * Finds the nearest entity at or above each resource, as the session sees it now.
     *
     * @param resolver the session
     * @param paths the absolute paths of the resources
     * @return the entities, one for each path that has one
     */
    private static List<Resource> enclosingEntities(final ResourceResolver resolver,
        final Collection<String> paths)
    {
        return paths.stream()
            .map(path -> nearestRemaining(resolver, path))
            .flatMap(Optional::stream)
            .map(EntityRevision::enclosingEntity)
            .flatMap(Optional::stream)
            .toList();
    }

    /**
     * Finds the resource at a path, or the nearest one above it when the session has removed it.
     *
     * @param resolver the session
     * @param path the absolute path
     * @return the resource; empty if nothing on the path remains
     */
    private static Optional<Resource> nearestRemaining(final ResourceResolver resolver, final String path)
    {
        return Stream.iterate(path, Objects::nonNull, ResourceUtil::getParent)
            .map(resolver::getResource)
            .filter(Objects::nonNull)
            .findFirst();
    }

    /**
     * Finds the nearest entity at or above a resource.
     *
     * @param resource the resource
     * @return the entity; empty when there is none
     */
    private static Optional<Resource> enclosingEntity(final Resource resource)
    {
        return Stream.iterate(resource, Objects::nonNull, Resource::getParent)
            .filter(candidate -> candidate.isResourceType(Entity.RESOURCE_TYPE))
            .findFirst();
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

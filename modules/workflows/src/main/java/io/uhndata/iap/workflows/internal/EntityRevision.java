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

import java.util.UUID;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;

import io.uhndata.iap.entities.models.Entity;

/**
 * Rewrites the revision token of the entity a change was about. Oak merges concurrent writes that agree and
 * refuses those that differ, so a value that always differs is what makes the second of two racing commits
 * fail rather than silently win.
 *
 * <p>A counter will not do: computing the next value means reading the current one, and two writers that read
 * 1 both write 2, which merges.</p>
 *
 * <p>The token goes on the enclosing entity, not on the node the event named. Two answers saved at once are
 * separate nodes and never collide, but both change the same submission.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class EntityRevision
{
    /** The property carrying the token, declared on {@code data:Entity}. */
    static final String PROPERTY = "revision";

    private EntityRevision()
    {
    }

    /**
     * Rewrites the token on the nearest enclosing entity, and does nothing when there is none.
     *
     * @param target the resource the change was aimed at, an entity or a part of one
     */
    static void stamp(final Resource target)
    {
        for (Resource resource = target; resource != null; resource = resource.getParent()) {
            if (resource.isResourceType(Entity.RESOURCE_TYPE)) {
                final ModifiableValueMap properties = resource.adaptTo(ModifiableValueMap.class);
                if (properties != null) {
                    properties.put(PROPERTY, UUID.randomUUID().toString());
                }
                return;
            }
        }
    }
}

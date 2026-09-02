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
 * Marks the entity a change is about, so that two changes made at once cannot both quietly succeed.
 *
 * <p>The repository merges concurrent writes that agree and refuses those that differ, so a lost update is
 * only ever caught by writing something that is guaranteed to differ. Every commit replaces this token with a
 * fresh one, which turns "we both changed this and one of us was working from a stale picture" from a silent
 * wrong answer into a refusal the caller can retry.</p>
 *
 * <p><strong>Why a token and not a version number.</strong> Computing the next number means reading the
 * current one, and two writers reading the same number write the same number — which is exactly the case the
 * repository merges, so a counter fails to lock in the situation it exists for. Nothing here needs ordering
 * either: the question being asked is "has this changed since I looked", which is about identity. Where order
 * is wanted it belongs to the action log, which is append-only and timestamped.</p>
 *
 * <p>Stamped on the enclosing <em>entity</em> rather than on whatever the event happened to name, because that
 * is the thing people act on concurrently: two answers saved at once are two different nodes and would never
 * collide on their own, while both plainly change the same submission.</p>
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
     * Replaces the revision token of the entity enclosing the given resource, if there is one.
     *
     * <p>Silent when the change is not about an entity at all — a definition, or configuration — since there is
     * then nothing whose concurrent editing this protects.</p>
     *
     * @param target the resource the change was aimed at, which may be the entity or a part of one
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

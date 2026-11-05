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
package io.uhndata.iap.entities.models;

import java.util.Objects;
import java.util.stream.Stream;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.content.models.Content;

/**
 * The model of an entity part: a {@code data:EntityPart} node, a piece of an entity, such as an answer in a
 * submission. Beyond what every content node has, it finds the entity the part belongs to.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = EntityPart.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class EntityPart extends Content
{
    /** The resource type of {@code data:EntityPart}, which the resource type of every entity part extends. */
    public static final String RESOURCE_TYPE = "data/EntityPart";

    /**
     * The entity this part belongs to: the nearest entity above it.
     *
     * @return the entity; {@code null} if no entity holds this part
     */
    @Nullable
    public Entity getEntity()
    {
        return Stream.iterate(this.resource.getParent(), Objects::nonNull, Resource::getParent)
            .filter(ancestor -> ancestor.isResourceType(Entity.RESOURCE_TYPE))
            .findFirst()
            .map(entity -> entity.adaptTo(Entity.class))
            .orElse(null);
    }
}

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

import java.util.Calendar;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.utils.Referenceable;

/**
 * The model of an entity: a {@code data:Entity} node, a thing stored as a whole, such as a submission. Beyond what
 * every content node has, it reads the entity's {@code jcr:uuid}, and when and by whom it was last modified.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Entity.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Entity extends Content implements Referenceable
{
    /** The resource type of {@code data:Entity}, which the resource type of every entity extends. */
    public static final String RESOURCE_TYPE = "data/Entity";

    @ValueMapValue(name = "jcr:lastModified")
    private Calendar lastModified;

    @ValueMapValue(name = "jcr:lastModifiedBy")
    private String lastModifiedBy;

    /**
     * The identifier of this entity, which stays the same for its whole life.
     *
     * @return the {@code jcr:uuid}
     */
    @Override
    @NotNull
    public String getIdentifier()
    {
        return this.identifier;
    }

    /**
     * When the content of this entity last changed.
     *
     * @return a copy of {@code jcr:lastModified}, which Oak sets when the node is created; {@code null} if the node
     *         has none
     */
    @Nullable
    public Calendar getLastModified()
    {
        return this.lastModified == null ? null : (Calendar) this.lastModified.clone();
    }

    /**
     * The user whose session last changed the content of this entity.
     *
     * @return a username, or {@code oak:unknown} for a commit that no user made; {@code null} if the node has no
     *         {@code jcr:lastModifiedBy}
     */
    @Nullable
    public String getLastModifiedBy()
    {
        return this.lastModifiedBy;
    }
}

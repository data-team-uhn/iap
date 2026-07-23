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

import io.uhndata.iap.content.models.Content;

/**
 * A Sling Model wrapping an {@code iap:Entity} node, a first-class, standalone, versionable data entity. On top of
 * the generic {@code iap:Content} properties, it exposes the stable identifier and versioning metadata specific to
 * entities.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Entity.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Entity extends Content
{
    /** The {@code sling:resourceType} of an {@code iap:Entity} node. */
    public static final String RESOURCE_TYPE = "iap/Entity";

    @ValueMapValue(name = "jcr:uuid")
    private String identifier;

    @ValueMapValue(name = "jcr:lastModified")
    private Calendar lastModified;

    @ValueMapValue(name = "jcr:lastModifiedBy")
    private String lastModifiedBy;

    /**
     * The stable unique identifier of the resource, valid for its whole lifetime.
     *
     * @return an UUID
     */
    public String getIdentifier()
    {
        return this.identifier;
    }

    /**
     * The date when the resource was last modified.
     *
     * @return a calendar, or {@code null} if the resource was never modified
     */
    public Calendar getLastModified()
    {
        return this.lastModified;
    }

    /**
     * The user that last modified the resource.
     *
     * @return a user name, or {@code null} if the resource was never modified
     */
    public String getLastModifiedBy()
    {
        return this.lastModifiedBy;
    }
}

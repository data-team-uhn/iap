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
package io.uhndata.iap.content.models;

import java.util.Calendar;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.SlingObject;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;

/**
 * A Sling Model wrapping an {@code iap:Content} node, the base type of all IAP data nodes. It exposes the generic
 * properties shared by all content nodes, hiding the underlying resource/JCR access from its users.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = "iap/Content",
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Content
{
    @SlingObject
    protected Resource resource;

    @ValueMapValue(name = "jcr:created")
    private Calendar created;

    @ValueMapValue(name = "jcr:createdBy")
    private String createdBy;

    /**
     * The path of the wrapped resource.
     *
     * @return an absolute repository path
     */
    public String getPath()
    {
        return this.resource.getPath();
    }

    /**
     * The name of the wrapped resource, the last segment of its path.
     *
     * @return a node name
     */
    public String getName()
    {
        return this.resource.getName();
    }

    /**
     * The specific type of the wrapped resource, e.g. {@code iap/Homepage}.
     *
     * @return a resource type name
     */
    public String getType()
    {
        return this.resource.getResourceType();
    }

    /**
     * The date when the resource was created.
     *
     * @return a calendar, or {@code null} if the creation date is not recorded
     */
    public Calendar getCreated()
    {
        return this.created;
    }

    /**
     * The user that created the resource.
     *
     * @return a user name, or {@code null} if the creator is not recorded
     */
    public String getCreatedBy()
    {
        return this.createdBy;
    }
}

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
package io.uhndata.iap.schemas.models;

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.entities.models.Entity;

/**
 * A Sling Model wrapping a {@code sch:Schema} node: a named schema. It only identifies the schema, and its
 * {@link SchemaVersion versions} hold what a submission is asked for.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Schema.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Schema extends Entity
{
    /** The {@code sling:resourceType} of a {@code sch:Schema} node. */
    public static final String RESOURCE_TYPE = "sch/Schema";

    @ValueMapValue
    private String title;

    /**
     * The schema's name, as people read it.
     *
     * @return the title
     */
    @NotNull
    public String getTitle()
    {
        return this.title;
    }

    /**
     * The versions of this schema.
     *
     * @return the versions, in stored order; empty if there are none
     */
    @NotNull
    public List<SchemaVersion> getVersions()
    {
        return this.getChildren(SchemaVersion.RESOURCE_TYPE, SchemaVersion.class);
    }
}

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

import io.uhndata.iap.entities.models.Entity;

/**
 * A Sling Model wrapping a {@code sch:Schema} node, a named institutional schema required for approving
 * submissions. The schema itself is only a container identifying the schema; the actual requirements live in its
 * {@code sch:SchemaVersion} children.
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

    @ValueMapValue
    private boolean active;

    /**
     * The human-readable name of the schema.
     *
     * @return a title
     */
    public String getTitle()
    {
        return this.title;
    }

    /**
     * Whether new submissions may be created against this schema.
     *
     * @return {@code true} if the schema accepts new submissions
     */
    public boolean isActive()
    {
        return this.active;
    }

    /**
     * The defined versions of this schema.
     *
     * @return a list of schema versions, empty if none
     */
    public List<SchemaVersion> getVersions()
    {
        return this.getChildren(SchemaVersion.RESOURCE_TYPE, SchemaVersion.class);
    }

    /**
     * The version of this schema that new submissions are currently created against. At most one version is
     * expected to be active at a time.
     *
     * @return the active schema version, or {@code null} if none of the versions are active
     */
    public SchemaVersion getActiveVersion()
    {
        return this.getVersions().stream()
            .filter(SchemaVersion::isActive)
            .findFirst()
            .orElse(null);
    }
}

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
package io.uhndata.iap.datarequirement.models;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.tags.models.Taggable;

/**
 * A Sling Model wrapping a {@code datareq:CatalogueVersion} node: one published state of a catalogue, and the
 * unit a selection is made against.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = CatalogueVersion.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class CatalogueVersion extends Entity
{
    /** The {@code sling:resourceType} of a {@code datareq:CatalogueVersion} node. */
    public static final String RESOURCE_TYPE = "datareq/CatalogueVersion";

    /** The lifecycle tag of the version a catalogue currently offers. */
    private static final String ACTIVE_TAG = "active";

    @ValueMapValue
    private String version;

    @ValueMapValue
    private String description;

    /**
     * The readable version label, e.g. "2026-08". The node itself is named {@code v1}, {@code v2}.
     *
     * @return a free-form label such as {@code 2026-08}, not the node name
     */
    @NotNull
    public String getVersion()
    {
        return this.version;
    }

    /**
     * What changed in this version, for whoever is deciding whether to publish it.
     *
     * @return a description, or {@code null} if none was given
     */
    @Nullable
    public String getDescription()
    {
        return this.description;
    }

    /**
     * Whether this is the version its catalogue currently offers: whether it carries the {@code active} lifecycle
     * tag. At most one version of a catalogue is expected to be active at a time.
     *
     * @return {@code true} if it carries the tag, {@code false} if it does not or the tags service is unavailable
     */
    public boolean isActive()
    {
        final Taggable tags = this.as(Taggable.class);
        return tags != null && tags.hasOwnTag(ACTIVE_TAG);
    }

    /**
     * The source systems this version covers, in the order the catalogue puts them.
     *
     * @return a list of databases, empty if none
     */
    @NotNull
    public List<Database> getDatabases()
    {
        return this.getChildren(Database.RESOURCE_TYPE, Database.class);
    }

    /**
     * The key of every field in this version, as a selection records them.
     *
     * @return field keys, each {@code database/collection/field} as {@link Field#getKey()} builds it; empty if this
     *     version holds no field
     */
    @NotNull
    public Set<String> getFieldKeys()
    {
        return this.getDatabases().stream()
            .flatMap(database -> database.getCollections().stream())
            .flatMap(collection -> collection.getFieldKeys().stream())
            .collect(Collectors.toSet());
    }

    /**
     * The catalogue this version belongs to.
     *
     * @return a catalogue, or {@code null} if its parent node is not a {@code datareq:Catalogue}
     */
    @Nullable
    public Catalogue getCatalogue()
    {
        return this.getParent(Catalogue.RESOURCE_TYPE, Catalogue.class);
    }
}

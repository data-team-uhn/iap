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

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.entities.models.Entity;

/**
 * A Sling Model wrapping a {@code datareq:CatalogueVersion} node: one published state of a catalogue, and the
 * unit a selection is made against.
 *
 * <p>A version is immutable in practice. A source system that gains or loses fields is republished as a new
 * version, which is what lets a selection keep meaning exactly what it meant when it was made — a submission
 * filed last quarter renders against the catalogue it was filed against, not against one with holes in it.</p>
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

    @ValueMapValue
    private String version;

    @ValueMapValue
    private String description;

    @ValueMapValue
    private boolean active;

    /**
     * The readable version label, e.g. "2026-08". The node itself is named {@code v1}, {@code v2} — a dot in a
     * node name breaks Sling path resolution, so the label lives here instead.
     *
     * @return a version label
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
     * Whether new selections are made against this version. At most one version of a catalogue is expected to be
     * active at a time; the others stay readable, because the selections made against them still name them.
     *
     * @return {@code true} if this is the version a new selection would use
     */
    public boolean isActive()
    {
        return this.active;
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
     * Looks up one field by the key a selection records it as.
     *
     * <p>This is how a stored selection is read back: it holds keys, and this is what turns one into the field it
     * named. A key that no longer resolves is not an error — it is a field this version does not have, which is
     * worth telling a reader about rather than hiding.</p>
     *
     * @param key a key of the form produced by {@link Field#getKey()}
     * @return the field that key names, or {@code null} if this version has no such field
     */
    @Nullable
    public Field getField(@NotNull final String key)
    {
        // Walked rather than resolved by path: a key is built from source identifiers, which are not node names,
        // so there is no path to construct. Catalogues are hundreds of fields, not millions
        return this.getFields().stream()
            .filter(field -> key.equals(field.getKey()))
            .findFirst()
            .orElse(null);
    }

    /**
     * Every field in this version, flattened out of the database and collection levels.
     *
     * @return a list of fields, empty if this version holds none
     */
    @NotNull
    public List<Field> getFields()
    {
        return this.getDatabases().stream()
            .flatMap(database -> database.getCollections().stream())
            .flatMap(collection -> collection.getFields().stream())
            .toList();
    }

    /**
     * The catalogue this version belongs to.
     *
     * @return a catalogue, or {@code null} if this version is not sitting inside one
     */
    @Nullable
    public Catalogue getCatalogue()
    {
        return this.getParent(Catalogue.RESOURCE_TYPE, Catalogue.class);
    }
}

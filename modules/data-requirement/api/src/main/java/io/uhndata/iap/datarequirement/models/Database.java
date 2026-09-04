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

import io.uhndata.iap.entities.models.EntityPart;

/**
 * A Sling Model wrapping a {@code datareq:Database} node: one source system within a catalogue version.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Database.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Database extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code datareq:Database} node. */
    public static final String RESOURCE_TYPE = "datareq/Database";

    @ValueMapValue
    private String identifier;

    @ValueMapValue
    private String label;

    @ValueMapValue
    private String description;

    /**
     * The source system's own name. This is the first of the three identifiers a field key is built from, so it
     * is not something to reword: renaming it rewrites every key naming this database.
     *
     * @return an identifier
     */
    @NotNull
    public String getIdentifier()
    {
        return this.identifier;
    }

    /**
     * What a reader is shown. Falls back to the {@link #getIdentifier() identifier}.
     *
     * @return a label, never empty
     */
    @NotNull
    public String getLabel()
    {
        return this.label == null || this.label.isEmpty() ? this.identifier : this.label;
    }

    /**
     * What this source system holds, for a submitter deciding whether to look inside it.
     *
     * @return a description, or {@code null} if none was given
     */
    @Nullable
    public String getDescription()
    {
        return this.description;
    }

    /**
     * The collections in this database, in the order the catalogue puts them.
     *
     * @return a list of collections, empty if none
     */
    @NotNull
    public List<Collection> getCollections()
    {
        return this.getChildren(Collection.RESOURCE_TYPE, Collection.class);
    }
}

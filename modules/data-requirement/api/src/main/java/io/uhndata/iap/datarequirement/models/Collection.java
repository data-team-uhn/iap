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
 * A Sling Model wrapping a {@code datareq:Collection} node: a named group of fields within a database.
 *
 * <p>What the interface calls a collection, whatever the source system calls it — the identifier keeps the
 * source's own word for the group, so an export and this catalogue agree.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Collection.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Collection extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code datareq:Collection} node. */
    public static final String RESOURCE_TYPE = "datareq/Collection";

    @ValueMapValue
    private String identifier;

    @ValueMapValue
    private String label;

    /**
     * The source system's own name for this group of fields.
     *
     * @return an identifier
     */
    @NotNull
    public String getIdentifier()
    {
        return this.identifier;
    }

    /**
     * What a reader is shown. Falls back to the {@link #getIdentifier() identifier}, so an uncurated catalogue
     * still names its collections.
     *
     * @return a label, never empty
     */
    @NotNull
    public String getLabel()
    {
        return this.label == null || this.label.isEmpty() ? this.identifier : this.label;
    }

    /**
     * The fields in this collection, in the order the catalogue puts them.
     *
     * @return a list of fields, empty if none
     */
    @NotNull
    public List<Field> getFields()
    {
        return this.getChildren(Field.RESOURCE_TYPE, Field.class);
    }

    /**
     * The database this collection belongs to.
     *
     * @return a database, or {@code null} if this collection is not sitting inside one
     */
    @Nullable
    public Database getDatabase()
    {
        return this.getParent(Database.RESOURCE_TYPE, Database.class);
    }
}

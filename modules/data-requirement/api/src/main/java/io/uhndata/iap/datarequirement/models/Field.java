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

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.entities.models.EntityPart;

/**
 * A Sling Model wrapping a {@code datareq:Field} node: one field a submitter may ask for.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Field.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Field extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code datareq:Field} node. */
    public static final String RESOURCE_TYPE = "datareq/Field";

    /** Separates the three identifiers a {@link #getKey() key} is built from. */
    public static final String KEY_SEPARATOR = "/";

    @ValueMapValue
    private String identifier;

    @ValueMapValue
    private String label;

    @ValueMapValue
    private String description;

    @ValueMapValue
    private String cardinality;

    @ValueMapValue
    private String dataType;

    // Boxed rather than primitive, and that is the whole point: absent has to stay distinguishable from
    // false, so that a source nobody has assessed cannot read as one assessed and found clear
    @ValueMapValue
    private Boolean phi;

    @ValueMapValue
    private String example;

    /**
     * The source system's own name for this field.
     *
     * @return an identifier
     */
    @NotNull
    public String getIdentifier()
    {
        return this.identifier;
    }

    /**
     * What a reader is shown. Falls back to the {@link #getIdentifier() identifier}, so a catalogue that has not
     * been curated still names its fields rather than showing blanks.
     *
     * @return a label, never empty
     */
    @NotNull
    public String getLabel()
    {
        return this.label == null || this.label.isEmpty() ? this.identifier : this.label;
    }

    /**
     * What this field holds, in the source's own words.
     *
     * @return a description, or {@code null} if the source gave none
     */
    @Nullable
    public String getDescription()
    {
        return this.description;
    }

    /**
     * How many values the field takes, in the source's notation.
     *
     * @return a cardinality, or {@code null} if the source gave none
     */
    @Nullable
    public String getCardinality()
    {
        return this.cardinality;
    }

    /**
     * The field's type in the source system.
     *
     * @return a type name, or {@code null} if the source gave none
     */
    @Nullable
    public String getDataType()
    {
        return this.dataType;
    }

    /**
     * Whether this field holds information that can identify a person.
     *
     * <p>Three answers, not two: {@code null} means nobody has assessed this field, which is not the same as
     * having assessed it and found it clear. A catalogue whose fields are unassessed says nothing about
     * identifiability rather than quietly reassuring a submitter.</p>
     *
     * @return {@code true} or {@code false} where the catalogue says so, {@code null} where it does not
     */
    @Nullable
    public Boolean getPhi()
    {
        return this.phi;
    }

    /**
     * A sample value.
     *
     * @return an example, or {@code null} if the source gave none
     */
    @Nullable
    public String getExample()
    {
        return this.example;
    }

    /**
     * The collection this field belongs to.
     *
     * @return a collection, or {@code null} if this field is not sitting inside one
     */
    @Nullable
    public Collection getCollection()
    {
        return this.getParent(Collection.RESOURCE_TYPE, Collection.class);
    }

    /**
     * How a chosen field is recorded: the identifiers of its database, its collection and itself, joined.
     *
     * <p>This is the compatibility contract between a catalogue and every selection ever made against one. It is
     * built from identifiers rather than from node identity on purpose — a catalogue version is a full copy, so
     * the same field in two versions is two different nodes, and a key that changed between them would orphan
     * every selection that named it.</p>
     *
     * @return a key, or {@code null} if this field is not sitting inside a collection inside a database
     */
    @Nullable
    public String getKey()
    {
        final Collection collection = this.getCollection();
        final Database database = collection == null ? null : collection.getDatabase();
        if (database == null) {
            return null;
        }
        return database.getIdentifier() + KEY_SEPARATOR + collection.getIdentifier() + KEY_SEPARATOR
            + this.identifier;
    }
}

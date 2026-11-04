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
import org.jetbrains.annotations.Nullable;

/**
 * A Sling Model wrapping a {@code datareq:Field} node: one field a submitter may ask for.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Field.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Field extends CatalogueItem
{
    /** The {@code sling:resourceType} of a {@code datareq:Field} node. */
    public static final String RESOURCE_TYPE = "datareq/Field";

    /** Separates the three identifiers a {@link #getKey() key} is built from. */
    public static final String KEY_SEPARATOR = "/";

    @ValueMapValue
    private String description;

    @ValueMapValue
    private String cardinality;

    @ValueMapValue
    private String dataType;

    @ValueMapValue
    private Boolean phi;

    @ValueMapValue
    private String example;

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
     * @return a cardinality in the source's notation, such as {@code 0..1}, or {@code null} if the source gave none
     */
    @Nullable
    public String getCardinality()
    {
        return this.cardinality;
    }

    /**
     * The field's type in the source system.
     *
     * @return a type name in the source's vocabulary, such as {@code date}, or {@code null} if the source gave none
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
     * having assessed it and found it clear.</p>
     *
     * @return {@code true} or {@code false} as the catalogue says, or {@code null} if nobody has assessed the field
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
     * @return a collection, or {@code null} if its parent node is not a {@code datareq:Collection}
     */
    @Nullable
    public Collection getCollection()
    {
        return this.getParent(Collection.RESOURCE_TYPE, Collection.class);
    }

    /**
     * How a chosen field is recorded: the identifiers of its database, its collection and itself, joined.
     *
     * <p>Every selection made against the catalogue holds keys of this form, so a key must not change from one
     * version to the next. It is built from source identifiers, because each version is a full copy and holds
     * nodes of its own.</p>
     *
     * @return a field key, {@code database/collection/field}, or {@code null} if the field is not inside a
     *     collection inside a database
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
            + this.getIdentifier();
    }
}

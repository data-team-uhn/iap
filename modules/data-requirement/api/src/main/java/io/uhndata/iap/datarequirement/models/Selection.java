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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.Requirement;

/**
 * A Sling Model wrapping a {@code datareq:Selection} node: the fields chosen in answer to one data requirement,
 * held as a part of the submission that made them. Each field is held by its {@link Field#getKey() key}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Selection.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Selection extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code datareq:Selection} node. */
    public static final String RESOURCE_TYPE = "datareq/Selection";

    /** The node type of a selection, for creating one. */
    public static final String NODE_TYPE = "datareq:Selection";

    @ValueMapValue
    private String fulfills;

    @ValueMapValue
    private String catalogueVersion;

    @ValueMapValue
    private String[] fields;

    /**
     * The requirement this selection answers.
     *
     * @return a requirement
     */
    @NotNull
    public Requirement getFulfills()
    {
        return Objects.requireNonNull(this.getReference(this.fulfills, Requirement.class),
            "A selection names the requirement it answers, which whoever can read the selection can read too");
    }

    /**
     * Whether this selection answers a given requirement.
     *
     * @param requirement a requirement
     * @return {@code true} if it is the requirement this selection names
     */
    public boolean fulfills(@NotNull final Requirement requirement)
    {
        return requirement.getIdentifier().equals(this.fulfills);
    }

    /**
     * The catalogue version these fields were chosen from.
     *
     * @return a catalogue version, or {@code null} until the first save records one, or if this session cannot
     *     read the referenced node
     */
    @Nullable
    public CatalogueVersion getCatalogueVersion()
    {
        return this.getReference(this.catalogueVersion, CatalogueVersion.class);
    }

    /**
     * The keys of the chosen fields, in the order they were stored.
     *
     * @return field keys, each {@code database/collection/field}, empty if nothing has been chosen
     */
    @NotNull
    public List<String> getFieldKeys()
    {
        return this.fields == null ? List.of() : Arrays.asList(this.fields.clone());
    }
}

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

import io.uhndata.iap.schemas.models.Requirement;

/**
 * A Sling Model wrapping a {@code datareq:DataRequirement} node: a requirement asking the submitter which data
 * they need, answered by choosing fields out of a catalogue.
 *
 * <p>It names the catalogue rather than one of its versions, so republishing a source system needs no new schema
 * version. The version a submitter chose from is bound to their {@link Selection}.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = Requirement.class, resourceType = DataRequirement.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class DataRequirement extends Requirement
{
    /** The {@code sling:resourceType} of a {@code datareq:DataRequirement} node. */
    public static final String RESOURCE_TYPE = "datareq/DataRequirement";

    @ValueMapValue
    private String catalogue;

    /**
     * The catalogue whose fields this requirement offers.
     *
     * @return a catalogue, or {@code null} if this session cannot read the referenced node
     */
    @Nullable
    public Catalogue getCatalogue()
    {
        return this.getReference(this.catalogue, Catalogue.class);
    }

    /**
     * The catalogue version the first save of a selection would bind it to now.
     *
     * @return the catalogue's active version, or {@code null} if there is no catalogue or it has no active version
     */
    @Nullable
    public CatalogueVersion getCurrentVersion()
    {
        final Catalogue offered = this.getCatalogue();
        return offered == null ? null : offered.getActiveVersion();
    }
}

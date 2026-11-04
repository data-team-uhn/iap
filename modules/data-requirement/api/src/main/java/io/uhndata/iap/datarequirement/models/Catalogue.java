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
 * A Sling Model wrapping a {@code datareq:Catalogue} node: what data a submitter may be asked to choose from.
 *
 * <p>The catalogue itself only names a set of source systems. What it holds lives in its
 * {@code datareq:CatalogueVersion} children, so a catalogue can be republished without disturbing anything already
 * filed against it.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Catalogue.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Catalogue extends Entity
{
    /** The {@code sling:resourceType} of a {@code datareq:Catalogue} node. */
    public static final String RESOURCE_TYPE = "datareq/Catalogue";

    @ValueMapValue
    private String title;

    @ValueMapValue
    private String description;

    /**
     * The human-readable name of the catalogue.
     *
     * @return a title
     */
    @NotNull
    public String getTitle()
    {
        return this.title;
    }

    /**
     * What this catalogue covers, for a schema author choosing between several.
     *
     * @return a description, or {@code null} if none was given
     */
    @Nullable
    public String getDescription()
    {
        return this.description;
    }

    /**
     * The published versions of this catalogue.
     *
     * @return a list of versions, empty if none
     */
    @NotNull
    public List<CatalogueVersion> getVersions()
    {
        return this.getChildren(CatalogueVersion.RESOURCE_TYPE, CatalogueVersion.class);
    }

    /**
     * The version the first save of a selection binds it to: the one carrying the {@code active} lifecycle tag. A
     * selection already bound keeps its own version when this one changes.
     *
     * @return the active version, or {@code null} if none of the versions are active
     */
    @Nullable
    public CatalogueVersion getActiveVersion()
    {
        return this.getVersions().stream()
            .filter(CatalogueVersion::isActive)
            .findFirst()
            .orElse(null);
    }
}

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

import java.util.Objects;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.schemas.models.Requirement;

/**
 * A Sling Model wrapping a {@code datareq:DataRequirement} node: a requirement asking the submitter which data
 * they need, answered by choosing fields out of one version of a catalogue.
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
     * The catalogue version this requirement offers to choose from.
     *
     * @return a catalogue version
     * @throws NullPointerException if the {@code catalogue} reference does not lead to a catalogue version
     */
    @NotNull
    public CatalogueVersion getCatalogueVersion()
    {
        return Objects.requireNonNull(findCatalogueVersion(),
            "A data requirement names a catalogue version, and everyone may read catalogues");
    }

    /**
     * The catalogue version this requirement offers to choose from, for a caller that must report a broken
     * reference rather than fail on it. A reference resolves to any node it points at, so the type is checked here.
     *
     * @return a catalogue version, or {@code null} if the {@code catalogue} reference is missing, cannot be read, or
     *     points at something that is not a {@code datareq:CatalogueVersion}
     */
    @Nullable
    public CatalogueVersion findCatalogueVersion()
    {
        final CatalogueVersion pinned = this.getReference(this.catalogue, CatalogueVersion.class);
        return pinned != null && pinned.isOfType(CatalogueVersion.RESOURCE_TYPE) ? pinned : null;
    }

    /**
     * The selection answering this requirement among the parts of a submission.
     *
     * @param submission the submission whose parts are searched
     * @return the selection, or {@code null} if the submission holds none for this requirement
     */
    @Nullable
    public Selection findSelectionIn(@NotNull final Content submission)
    {
        return submission.getChildren(Selection.RESOURCE_TYPE, Selection.class).stream()
            .filter(selection -> selection.fulfills(this))
            .findFirst()
            .orElse(null);
    }
}

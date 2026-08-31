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
 * they need, answered by choosing fields out of a catalogue.
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
     * @return a catalogue
     */
    @NotNull
    public Catalogue getCatalogue()
    {
        return Objects.requireNonNull(this.getReference(this.catalogue, Catalogue.class),
            "A data requirement names a catalogue, and everyone may read catalogues");
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

    /**
     * The catalogue version a selection is read against: the one it records, or else the catalogue's active one.
     *
     * <p>Once a selection records a version, that version stays. A submitter who started before a new version was
     * published carries on with the version they started in. A reviewer reading a filed request sees what was on
     * offer at the time.</p>
     *
     * @param selection the selection answering this requirement, or {@code null} if there is none yet
     * @return a catalogue version, or {@code null} if the selection records none and the catalogue has no active
     *     version
     */
    @Nullable
    public CatalogueVersion getVersionFor(@Nullable final Selection selection)
    {
        final CatalogueVersion recorded = selection == null ? null : selection.getCatalogueVersion();
        return recorded == null ? this.getCatalogue().getActiveVersion() : recorded;
    }
}

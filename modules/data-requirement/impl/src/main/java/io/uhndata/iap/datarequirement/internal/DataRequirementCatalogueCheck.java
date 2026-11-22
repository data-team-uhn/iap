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
package io.uhndata.iap.datarequirement.internal;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.datarequirement.models.Catalogue;
import io.uhndata.iap.datarequirement.models.CatalogueVersion;
import io.uhndata.iap.datarequirement.models.DataRequirement;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.workflows.spi.ValidityCheck;

/**
 * Checks that every data requirement of a schema version names a catalogue version that is open: one tagged
 * {@code active} in a catalogue that is not retired. A retired catalogue version is no longer offered to new schema
 * versions. The schema versions already published with it keep it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ValidityCheck.class)
public class DataRequirementCatalogueCheck implements ValidityCheck
{
    @Override
    public boolean appliesTo(final Resource target)
    {
        return target.isResourceType(SchemaVersion.RESOURCE_TYPE);
    }

    @Override
    public List<String> check(final Resource target)
    {
        final SchemaVersion version = Objects.requireNonNull(target.adaptTo(SchemaVersion.class),
            "A schema version adapts to its model");
        return version.getChildren(DataRequirement.RESOURCE_TYPE, DataRequirement.class).stream()
            .map(this::findProblem)
            .flatMap(Optional::stream)
            .toList();
    }

    /**
     * What is wrong with the catalogue version a data requirement names.
     *
     * @param requirement a data requirement of the schema version being checked
     * @return one sentence naming the requirement and the problem; empty if it names an open catalogue version
     */
    private Optional<String> findProblem(final DataRequirement requirement)
    {
        final String named = "Data requirement \"" + requirement.getLabel() + "\"";
        final CatalogueVersion catalogueVersion = requirement.findCatalogueVersion();
        if (catalogueVersion == null) {
            return Optional.of(named + " names no catalogue version");
        }
        if (!catalogueVersion.isActive()) {
            return Optional.of(named + " offers " + describe(catalogueVersion)
                + ", which is not open to new schema versions");
        }
        return Optional.empty();
    }

    /**
     * How a problem names a catalogue version.
     *
     * @param catalogueVersion the catalogue version
     * @return its label and its catalogue's title, or its path when it is not inside a catalogue
     */
    private String describe(final CatalogueVersion catalogueVersion)
    {
        final Catalogue catalogue = catalogueVersion.getCatalogue();
        return catalogue == null ? catalogueVersion.getPath()
            : "version " + catalogueVersion.getVersion() + " of \"" + catalogue.getTitle() + "\"";
    }
}

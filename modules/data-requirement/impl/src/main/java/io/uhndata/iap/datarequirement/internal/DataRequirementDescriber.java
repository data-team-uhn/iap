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

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;

import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.datarequirement.models.CatalogueVersion;
import io.uhndata.iap.datarequirement.models.DataRequirement;
import io.uhndata.iap.datarequirement.models.Selection;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.RequirementDescriber;

/**
 * What a data requirement adds to a form: which catalogue version to browse, and what has been chosen out of it
 * already.
 *
 * <p><strong>The version is the selection's, not the catalogue's current one.</strong> Once the first save has bound
 * the selection to a version, the form offers that version. A submitter who started before a republication carries
 * on with the catalogue they started in. A reviewer reading a filed request sees what was on offer at the time.
 * Before the first save, the form offers the catalogue's active version.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = RequirementDescriber.class)
public class DataRequirementDescriber implements RequirementDescriber
{
    @Override
    public boolean handles(final Requirement requirement)
    {
        return requirement instanceof DataRequirement;
    }

    @Override
    public void describe(final Requirement generic, final Submission submission, final JsonObjectBuilder json)
    {
        final DataRequirement requirement = (DataRequirement) generic;
        final Selection selection = Selections.findSelection(submission, requirement);
        // Stated always: an empty list means nothing has been chosen yet
        final JsonArrayBuilder chosen = Json.createArrayBuilder();
        if (selection != null) {
            selection.getFieldKeys().forEach(chosen::add);
        }
        json.add("fields", chosen);

        final CatalogueVersion version = findCatalogueVersion(requirement, selection);
        if (version != null) {
            // The path only: the browser loads the catalogue itself. A version is hundreds of fields, too many to
            // copy into every requirement on the form
            json.add("catalogueVersion", version.getPath());
            json.add("catalogueVersionLabel", version.getVersion());
        }
    }

    /**
     * The version this requirement is answered against: the one the selection is bound to, or else the catalogue's
     * active one.
     *
     * @param requirement the requirement being described
     * @param selection the selection answering it, or {@code null} if the submission holds none
     * @return a catalogue version, or {@code null} if the selection is bound to none and the catalogue has no
     *     active version
     */
    private CatalogueVersion findCatalogueVersion(final DataRequirement requirement, final Selection selection)
    {
        final CatalogueVersion chosen = selection == null ? null : selection.getCatalogueVersion();
        return chosen == null ? requirement.getCurrentVersion() : chosen;
    }
}

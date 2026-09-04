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
 * <p><strong>The version is the submission's, not the catalogue's current one.</strong> Once a selection exists it
 * names the version it was made against, and that is what the form offers — so a submitter who started before a
 * republication carries on with the catalogue they started in, and a reviewer reading a filed request sees what
 * was actually on offer at the time. Only a requirement nothing has answered yet falls back to whatever the
 * catalogue is publishing now.</p>
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
        final Selection selection = Selections.of(submission, requirement);
        // Stated always, empty meaning "nothing chosen yet": a reader has to tell that from a requirement whose
        // selection could not be read at all, and both are things the form says out loud
        final JsonArrayBuilder chosen = Json.createArrayBuilder();
        if (selection != null) {
            selection.getFieldKeys().forEach(chosen::add);
        }
        json.add("fields", chosen);

        final CatalogueVersion version = version(requirement, selection);
        if (version != null) {
            // The path, because the browser loads the catalogue itself rather than being handed it inline: a
            // version is hundreds of fields, and every requirement on the form would otherwise carry a copy
            json.add("catalogueVersion", version.getPath());
            json.add("catalogueVersionLabel", version.getVersion());
        }
    }

    /**
     * The version this requirement is to be answered against: the one already chosen from, or failing that
     * whatever the catalogue publishes now.
     *
     * @param requirement the requirement being described
     * @param selection what has been chosen already, or {@code null} if nothing has
     * @return a catalogue version, or {@code null} if neither can be resolved
     */
    private CatalogueVersion version(final DataRequirement requirement, final Selection selection)
    {
        final CatalogueVersion chosen = selection == null ? null : selection.getCatalogueVersion();
        return chosen == null ? requirement.getCurrentVersion() : chosen;
    }
}

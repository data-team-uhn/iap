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
        final Selection selection = requirement.findSelectionIn(submission);
        // Always present: an empty list means nothing has been chosen yet
        final JsonArrayBuilder chosen = Json.createArrayBuilder();
        if (selection != null) {
            selection.getFieldKeys().forEach(chosen::add);
        }
        json.add("fields", chosen);
        final CatalogueVersion version = requirement.getCatalogueVersion();
        // The path only: the browser loads the catalogue itself. A version is hundreds of fields, too many to copy
        // into every requirement on the form
        json.add("catalogueVersion", version.getPath());
        json.add("catalogueVersionLabel", version.getVersion());
    }
}

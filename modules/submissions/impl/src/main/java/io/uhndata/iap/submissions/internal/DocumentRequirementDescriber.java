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
package io.uhndata.iap.submissions.internal;

import java.util.Arrays;
import java.util.Objects;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.submissions.models.Document;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.RequirementDescriber;

/**
 * What a document requirement adds to a form: which types it takes, the blank to start from if it offers one, and
 * what has already been attached for it.
 *
 * <p>All three are here because an upload control cannot be drawn without them, and the form projection is the only
 * place that says which requirements currently apply — reading them off the schema instead would mean a control
 * offering to answer something this submission is not being asked.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = RequirementDescriber.class)
public class DocumentRequirementDescriber implements RequirementDescriber
{
    @Override
    public boolean handles(final Requirement requirement)
    {
        return requirement instanceof DocumentRequirement;
    }

    @Override
    public void describe(final Requirement generic, final Submission submission,
        final JsonObjectBuilder json)
    {
        final DocumentRequirement requirement = (DocumentRequirement) generic;
        // Stated always, not only when false: an upload control marks the optional case, and it should do so
        // because the form said so rather than because a key was missing
        json.add("required", requirement.isRequired());
        final JsonArrayBuilder accepted = Json.createArrayBuilder();
        // Absent means "no restriction", which a reader has to be able to tell from a list that happens to be
        // empty — so the key is always there and it is the emptiness that carries the meaning
        Arrays.stream(Objects.requireNonNullElse(requirement.getAcceptedFileTypes(), new String[0]))
            .forEach(accepted::add);
        json.add("acceptedFileTypes", accepted);
        final Resource template = requirement.getTemplate();
        if (template != null) {
            json.add("template", template.getPath());
        }
        // Named rather than counted, so that a form reopened later says which document is there. Without this an
        // upload control looks the same before and after, and the way to check would be to leave the page
        final JsonArrayBuilder attached = Json.createArrayBuilder();
        submission.getDocuments().stream()
            .filter(document -> fulfills(document, requirement))
            .map(document -> Objects.toString(document.getTitle(), document.getName()))
            .forEach(attached::add);
        json.add("attached", attached);
    }

    /**
     * Whether one document was attached in answer to one requirement.
     *
     * @param document the attached document
     * @param requirement the requirement in question
     * @return {@code true} if the document says it fulfills that requirement
     */
    private boolean fulfills(final Document document, final Requirement requirement)
    {
        final Requirement fulfilled = document.getFulfills();
        return fulfilled != null && requirement.getPath().equals(fulfilled.getPath());
    }
}

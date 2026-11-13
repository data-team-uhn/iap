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
package io.uhndata.iap.schemas.editing.internal.checks;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.workflows.spi.ValidityCheck;

/**
 * Checks that every validation, classification and form names only documents of the same schema version, and only
 * documents that are parsed. A name the version lacks, or a document that skips parsing, would leave that upload
 * unchecked, or the form unread, for every submission of the version.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ValidityCheck.class)
public class DocumentReferencesCheck extends SchemaVersionCheck
{
    @Override
    public List<String> check(final Resource version)
    {
        // Read through the same models the reading uses, so both agree on what each part reads
        final SchemaVersion model = Objects.requireNonNull(version.adaptTo(SchemaVersion.class),
            "A schema version adapts to its model");
        final Map<String, DocumentRequirement> documents = model.getDocumentRequirements().stream()
            .collect(Collectors.toMap(DocumentRequirement::getName, Function.identity()));
        return model.getRequirements().stream()
            .flatMap(reader -> reader.getReadDocumentNames().stream()
                .map(name -> findProblem(documents.get(name), name))
                .filter(Objects::nonNull)
                .map(problem -> DraftParts.describe(getResource(version, reader), version) + " reads the document "
                    + problem))
            .collect(Collectors.toList());
    }

    private static String findProblem(final DocumentRequirement document, final String name)
    {
        if (document == null) {
            return "\"" + name + "\", which is not in this version";
        }
        return document.isParsingSkipped() ? "\"" + name + "\", which is never parsed" : null;
    }

    private static Resource getResource(final Resource version, final Requirement requirement)
    {
        return Objects.requireNonNull(version.getResourceResolver().getResource(requirement.getPath()),
            "A requirement the version lists is in the version");
    }
}

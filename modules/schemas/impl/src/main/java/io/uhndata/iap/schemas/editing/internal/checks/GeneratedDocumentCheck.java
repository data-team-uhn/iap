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
import java.util.Optional;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.workflows.spi.ValidityCheck;

/**
 * Check that every generated document has a {@code .docx} template to be filled in. A submitter cannot upload a
 * generated document, so without one the requirement could never be met, and every submission against the version
 * would stay incomplete.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ValidityCheck.class)
public class GeneratedDocumentCheck extends SchemaVersionCheck
{
    /** The media type of a {@code .docx} file. */
    static final String DOCX_MIME_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Override
    public List<String> check(final Resource version)
    {
        return DraftParts.ofType(version, DraftParts.DOCUMENT)
            .filter(part -> part.getValueMap().get("generated", false))
            .map(part -> problem(part, version))
            .flatMap(Optional::stream)
            .toList();
    }

    private static Optional<String> problem(final Resource part, final Resource version)
    {
        final Resource template = part.getChild("template");
        if (template == null) {
            return Optional.of(DraftParts.describe(part, version) + " is generated, but has no template");
        }
        if (!DOCX_MIME_TYPE.equals(template.getValueMap().get("jcr:content/jcr:mimeType", String.class))) {
            return Optional.of(DraftParts.describe(part, version)
                + " is generated, but its template is not a .docx file");
        }
        return Optional.empty();
    }
}

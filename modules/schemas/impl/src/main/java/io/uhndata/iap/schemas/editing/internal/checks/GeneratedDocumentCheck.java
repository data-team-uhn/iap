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

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.documents.generation.api.GeneratedDocument;
import io.uhndata.iap.workflows.spi.ValidityCheck;

/**
 * Check that every generated document has a {@code .docx} template to be filled in. Without one nothing could be
 * generated.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ValidityCheck.class)
public class GeneratedDocumentCheck extends SchemaVersionCheck
{
    private static final String GENERIC_TYPE = "application/octet-stream";

    /** The part of a {@code .docx} that holds the document's body. */
    private static final String WORD_DOCUMENT_PART = "word/document.xml";

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
        if (!isDocx(template)) {
            return Optional.of(DraftParts.describe(part, version)
                + " is generated, but its template is not a .docx file");
        }
        return Optional.empty();
    }

    private static boolean isDocx(final Resource template)
    {
        final String type = template.getValueMap().get("jcr:content/jcr:mimeType", String.class);
        if (GeneratedDocument.DOCX_MIME_TYPE.equals(type)) {
            return true;
        }
        // A file loaded with the bundle's content, or put in by hand, is named "template" with no extension to tell
        // its type by, and is recorded as generic: then what it holds decides
        return (type == null || GENERIC_TYPE.equals(type)) && holdsAWordDocument(template);
    }

    /**
     * Whether a file is a zip holding the main part every {@code .docx} has.
     *
     * @param template the template file
     * @return {@code false} when it cannot be read, or holds anything else
     */
    private static boolean holdsAWordDocument(final Resource template)
    {
        try (ZipInputStream zip = new ZipInputStream(Objects.requireNonNull(template.adaptTo(InputStream.class),
            "The node type makes a template a file, and a file always has content"))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (WORD_DOCUMENT_PART.equals(entry.getName())) {
                    return true;
                }
            }
            return false;
        } catch (final IOException e) {
            return false;
        }
    }
}

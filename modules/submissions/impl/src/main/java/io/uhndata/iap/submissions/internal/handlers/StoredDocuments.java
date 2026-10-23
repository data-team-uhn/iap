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
package io.uhndata.iap.submissions.internal.handlers;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.utils.ReferenceUtils;

/**
 * Where a submission's documents are written: a {@code sub:Document} per requirement, a {@code sub:DocumentVersion}
 * per file, and the file's bytes. Shared by the handler storing what a submitter uploads and the one storing what
 * the platform generates, so that both end up shaped the same and every reader treats them alike.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class StoredDocuments
{
    /** The name the node type gives a version's file. */
    static final String FILE_NODE = "file";

    /** The name the node type gives the document inside a file. */
    static final String UPLOADED_FILE_NODE = "uploadedFile";

    private static final String PRIMARY_TYPE = "jcr:primaryType";

    private static final String TITLE_PROPERTY = "title";

    /** Where the document records what it fulfills. */
    private static final String FULFILLS_PROPERTY = "fulfills";

    private StoredDocuments()
    {
        // Utility class
    }

    /**
     * The document answering the requirement, created by the first file for it. A later file is a new version of
     * the same document, so the document's title follows the newest file.
     *
     * @param target the submission's resource
     * @param submission the submission the document belongs to
     * @param requirement the requirement the file answers
     * @param title what to call the document
     * @param resolver the resolver to write with
     * @return the document resource
     * @throws PersistenceException when the document cannot be written
     */
    static Resource findOrCreateDocument(final Resource target, final Submission submission,
        final DocumentRequirement requirement, final String title, final ResourceResolver resolver)
        throws PersistenceException
    {
        final Resource existing = submission.getDocuments().stream()
            .filter(document -> document.isFulfilling(requirement))
            .findFirst()
            .map(document -> resolver.getResource(document.getPath()))
            .orElse(null);
        if (existing != null) {
            Objects.requireNonNull(existing.adaptTo(ModifiableValueMap.class),
                "A document read through a writing resolver is always modifiable").put(TITLE_PROPERTY, title);
            return existing;
        }
        // A UUID rather than the file's name: two documents may legitimately be called the same thing, and a name
        // taken from what somebody uploaded is a name chosen by them for a node in our tree
        final Resource document = resolver.create(target, UUID.randomUUID().toString(),
            Map.of(PRIMARY_TYPE, "sub:Document", TITLE_PROPERTY, title));
        ReferenceUtils.setReference(document, FULFILLS_PROPERTY, requirement);
        return document;
    }

    /**
     * Add a version to a document, with the {@code sub:File} its content goes under.
     *
     * @param document the document resource
     * @param resolver the resolver to write with
     * @return the new version's {@code sub:File}
     * @throws PersistenceException when the version cannot be created
     */
    static Resource addVersion(final Resource document, final ResourceResolver resolver)
        throws PersistenceException
    {
        // Not named by its number, which is its position among the document's versions
        final Resource version = resolver.create(document, UUID.randomUUID().toString(),
            Map.of(PRIMARY_TYPE, "sub:DocumentVersion"));
        return resolver.create(version, FILE_NODE, Map.of(PRIMARY_TYPE, "sub:File"));
    }

    /**
     * Store some bytes as an {@code nt:file} child of a {@code sub:File}.
     *
     * <p>Written through the JCR API rather than the resolver, because a binary is a {@code jcr:data} property on an
     * {@code nt:resource} child and streaming into it is what keeps the file out of the heap.</p>
     *
     * @param stored the {@code sub:File} to store the content under
     * @param name the child's name, {@link #UPLOADED_FILE_NODE} for the document itself
     * @param content the bytes, closed once read
     * @param mimeType what the bytes are
     * @throws PersistenceException when the content cannot be stored
     */
    static void write(final Resource stored, final String name, final InputStream content, final String mimeType)
        throws PersistenceException
    {
        final Node node = Objects.requireNonNull(stored.adaptTo(Node.class),
            "A freshly created file is always backed by a JCR node");
        try (InputStream bytes = content) {
            final Node fileNode = node.addNode(name, "nt:file");
            final Node resource = fileNode.addNode("jcr:content", "nt:resource");
            resource.setProperty("jcr:data", fileNode.getSession().getValueFactory().createBinary(bytes));
            // Recorded because the repository has to serve the file back with a type
            resource.setProperty("jcr:mimeType", mimeType);
        } catch (final RepositoryException | IOException e) {
            throw new PersistenceException("Could not store the file", e);
        }
    }
}

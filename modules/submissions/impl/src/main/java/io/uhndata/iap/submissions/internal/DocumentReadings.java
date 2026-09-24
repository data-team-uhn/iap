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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Value;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.DocumentVersion;
import io.uhndata.iap.submissions.models.Extraction;

/**
 * The answers read out of a document, dropped when the document goes away or is replaced.
 *
 * <p>A suggestion nobody touched goes as a whole: the file it came from is gone or superseded, and keeping the
 * value would make it look like something the submitter typed, and would stop the next reading from filling it
 * in. An answer the submitter confirmed, changed or typed keeps its value.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class DocumentReadings
{
    private DocumentReadings()
    {
        // Static helper
    }

    /**
     * Drop every reading of a document about to be deleted, including the readings behind answers the submitter
     * kept: Oak refuses to delete a revision that is still referenced.
     *
     * @param resolver the session to write through
     * @param document the document's resource
     * @throws PersistenceException when a reading cannot be removed
     */
    static void dropAll(final ResourceResolver resolver, final Resource document) throws PersistenceException
    {
        drop(resolver, document, false);
    }

    /**
     * Drop the untouched suggestions read from a document that is getting a new version, so the new file is
     * read for them. What the submitter settled stays as it is, with the reading it came from.
     *
     * @param resolver the session to write through
     * @param document the document's resource, before the new version is added
     * @throws PersistenceException when a suggestion cannot be removed
     */
    static void dropSuggestions(final ResourceResolver resolver, final Resource document)
        throws PersistenceException
    {
        drop(resolver, document, true);
    }

    private static void drop(final ResourceResolver resolver, final Resource document, final boolean onlySuggestions)
        throws PersistenceException
    {
        try {
            final Resource submission = Objects.requireNonNull(document.getParent(),
                "A document is always part of a submission");
            final Set<String> versions = getVersionIdentifiers(document);
            for (final Resource reading : getReadings(submission, versions, onlySuggestions)) {
                resolver.delete(reading);
            }
        } catch (final RepositoryException e) {
            throw new PersistenceException("Could not drop the reading of " + document.getPath(), e);
        }
    }

    /** The identifiers of a document's revisions, which is what a reference stores. */
    private static Set<String> getVersionIdentifiers(final Resource document) throws RepositoryException
    {
        final Set<String> versions = new HashSet<>();
        for (final Resource child : document.getChildren()) {
            if (isNodeType(child, DocumentVersion.RESOURCE_TYPE, "sub:DocumentVersion")) {
                versions.add(getIdentifier(child));
            }
        }
        return versions;
    }

    /**
     * What to delete: the whole answer when it is a suggestion nobody touched, otherwise its readings of these
     * revisions, unless only suggestions are to go.
     */
    private static List<Resource> getReadings(final Resource submission, final Set<String> versions,
        final boolean onlySuggestions) throws RepositoryException
    {
        final List<Resource> readings = new ArrayList<>();
        for (final Resource child : submission.getChildren()) {
            if (!isNodeType(child, Answer.RESOURCE_TYPE, "sub:Answer")) {
                continue;
            }
            final List<Resource> dropped = new ArrayList<>();
            for (final Resource extraction : child.getChildren()) {
                if (isNodeType(extraction, Extraction.RESOURCE_TYPE, "sub:Extraction")
                    && isReading(extraction, versions)) {
                    dropped.add(extraction);
                }
            }
            if (isUntouchedSuggestion(child, dropped)) {
                readings.add(child);
            } else if (!onlySuggestions) {
                readings.addAll(dropped);
            }
        }
        return readings;
    }

    /**
     * Whether an answer is only a suggestion from these readings: every reading it has is one of them, none was
     * confirmed, and it still holds exactly what the model suggested.
     */
    private static boolean isUntouchedSuggestion(final Resource answer, final List<Resource> dropped)
    {
        if (dropped.isEmpty()) {
            return false;
        }
        final Answer model = Objects.requireNonNull(answer.adaptTo(Answer.class),
            "An answer node always reads as an answer");
        final List<Extraction> all = model.getExtractions();
        if (all.size() != dropped.size() || all.stream().anyMatch(Extraction::isReviewed)) {
            return false;
        }
        final List<String> suggested = model.getSuggestedValues();
        final String[] value = model.getValue();
        return !suggested.isEmpty() && value != null && Arrays.asList(value).equals(suggested);
    }

    /** Whether a reading used one of these revisions: its own sources, or a quote that names one of them. */
    private static boolean isReading(final Resource extraction, final Set<String> versions)
        throws RepositoryException
    {
        if (isReferencing(extraction, "sources", versions)) {
            return true;
        }
        for (final Resource evidence : extraction.getChildren()) {
            if (isReferencing(evidence, "source", versions)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a reference property points at one of these nodes. Absent counts as no. */
    private static boolean isReferencing(final Resource resource, final String property,
        final Set<String> identifiers) throws RepositoryException
    {
        final Node node = Objects.requireNonNull(resource.adaptTo(Node.class),
            "A node read from the repository is always backed by JCR");
        if (!node.hasProperty(property)) {
            return false;
        }
        final Property stored = node.getProperty(property);
        if (stored.isMultiple()) {
            for (final Value value : stored.getValues()) {
                if (identifiers.contains(value.getString())) {
                    return true;
                }
            }
            return false;
        }
        return identifiers.contains(stored.getString());
    }

    private static String getIdentifier(final Resource resource) throws RepositoryException
    {
        return Objects.requireNonNull(resource.adaptTo(Node.class),
            "A document read from the repository is always backed by a JCR node").getIdentifier();
    }

    private static boolean isNodeType(final Resource resource, final String resourceType, final String primaryType)
    {
        return resource.isResourceType(resourceType)
            || primaryType.equals(resource.getValueMap().get("jcr:primaryType", String.class));
    }
}

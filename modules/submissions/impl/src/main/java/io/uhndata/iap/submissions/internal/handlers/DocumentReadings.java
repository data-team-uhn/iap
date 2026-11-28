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

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.submissions.models.Document;
import io.uhndata.iap.submissions.models.DocumentVersion;
import io.uhndata.iap.submissions.models.Extraction;
import io.uhndata.iap.submissions.models.Submission;

/**
 * Drops the extraction runs that read a document about to be removed. A run keeps a strong reference to each
 * revision it read, and Oak refuses to delete a revision that is still referenced. The answers stay: a reading
 * never wrote their values.
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
     * Deletes every run that read one of the document's revisions.
     *
     * @param resolver the session to write through
     * @param submission the submission holding the answers
     * @param document the document about to be removed
     * @throws PersistenceException when a run cannot be deleted
     */
    static void dropAll(final ResourceResolver resolver, final Submission submission, final Document document)
        throws PersistenceException
    {
        final Set<String> versions = document.getVersions().stream()
            .map(DocumentVersion::getPath)
            .collect(Collectors.toSet());
        for (final Extraction run : submission.getAnswers().stream()
            .flatMap(answer -> answer.getExtractions().stream())
            .filter(run -> run.getSources().stream().map(DocumentVersion::getPath).anyMatch(versions::contains))
            .toList()) {
            resolver.delete(Objects.requireNonNull(resolver.getResource(run.getPath()),
                "A run the answer just listed can be read by the same resolver"));
        }
    }
}

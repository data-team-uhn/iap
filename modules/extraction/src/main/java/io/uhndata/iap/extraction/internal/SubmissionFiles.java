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
package io.uhndata.iap.extraction.internal;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.apache.sling.api.resource.Resource;

import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.submissions.models.Document;
import io.uhndata.iap.submissions.models.DocumentVersion;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;

/**
 * Helpers that find a submission and its files for the reading steps. A document's file is always its latest
 * upload, and documents come in the order the repository lists them.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class SubmissionFiles
{
    /** What the submitter is told when no document a reading can read has been uploaded. */
    static final String NO_DOCUMENT_MESSAGE = "No document to read has been uploaded";

    private SubmissionFiles()
    {
        // Utility
    }

    /**
     * The submission a node belongs to: the node itself when it is a submission, or the closest submission above.
     *
     * @param part a submission, or any node under one
     * @return the submission's resource, or {@code null} when the node is not under one
     */
    static Resource findSubmission(final Resource part)
    {
        Resource current = part;
        while (current != null && !current.isResourceType(Submission.RESOURCE_TYPE)) {
            current = current.getParent();
        }
        return current;
    }

    /**
     * The submission resource as a model. Throws {@code NullPointerException} when the resource is not a
     * submission.
     *
     * @param target the submission's resource
     * @return the model, never {@code null}
     */
    static Submission getSubmission(final Resource target)
    {
        return Objects.requireNonNull(target.adaptTo(Submission.class),
            "The extraction workflows only apply to submissions");
    }

    /**
     * The latest upload of every document, in the order the repository lists the documents.
     *
     * @param submission the submission
     * @return the current files; documents with no upload yet are skipped
     */
    static List<File> getCurrentFiles(final Submission submission)
    {
        return getCurrentFiles(submission.getDocuments().stream());
    }

    /**
     * The latest upload of every document whose requirement does not skip parsing, in the order the repository
     * lists the documents. These are the only files ever parsed, so they are the only ones a reading can read.
     *
     * @param submission the submission
     * @return the current files to parse; documents with no upload yet are skipped
     */
    static List<File> getFilesToParse(final Submission submission)
    {
        return getCurrentFiles(submission.getDocuments().stream().filter(document -> !isParsingSkipped(document)));
    }

    private static List<File> getCurrentFiles(final Stream<Document> documents)
    {
        return documents.map(SubmissionFiles::getCurrentFile).filter(Objects::nonNull).toList();
    }

    private static File getCurrentFile(final Document document)
    {
        final DocumentVersion version = document.getCurrentVersion();
        return version == null ? null : version.getFile();
    }

    private static boolean isParsingSkipped(final Document document)
    {
        return document.getFulfills() instanceof final DocumentRequirement requirement
            && requirement.isParsingSkipped();
    }

    /**
     * The latest upload for one document requirement, whatever its parse status.
     *
     * @param submission the submission
     * @param requirement the name of the document requirement in the schema version
     * @return the latest upload, or {@code null} when no document for that requirement has an upload
     */
    static File getCurrentFile(final Submission submission, final String requirement)
    {
        for (final Document document : submission.getDocuments()) {
            final Requirement fulfilled = document.getFulfills();
            final File file = getCurrentFile(document);
            if (fulfilled != null && requirement.equals(fulfilled.getName()) && file != null) {
                return file;
            }
        }
        return null;
    }

    /**
     * The schema's document requirement with the given name.
     *
     * @param submission the submission whose schema version is searched
     * @param name the document requirement's node name
     * @return the requirement, or {@code null} when the schema version has none by that name
     */
    static DocumentRequirement findDocumentRequirement(final Submission submission, final String name)
    {
        return submission.getSchemaVersion().getDocumentRequirements().stream()
            .filter(document -> document.getName().equals(name))
            .findFirst()
            .orElse(null);
    }

    /**
     * Whether a document a model should read cannot be read: a required one, whose condition holds, was never
     * uploaded, which the submission view should have prevented, or its upload failed to parse. An optional one
     * left out is not missing, and neither is a name the schema does not know. Asked once every parse has settled.
     *
     * @param submission the submission
     * @param name the document requirement's name
     * @return {@code true} when the document should be there and cannot be read
     */
    static boolean isDocumentMissing(final Submission submission, final String name)
    {
        final File file = getCurrentFile(submission, name);
        if (file == null) {
            final DocumentRequirement document = findDocumentRequirement(submission, name);
            return document != null && document.isRequired() && submission.isApplicable(document);
        }
        return !ParsePropertyNames.STATUS_COMPLETED.equals(file.getParseStatus());
    }

    /**
     * What the submitter is told when a document a model should read is missing.
     *
     * @param label the document requirement's label
     * @return the message
     */
    static String describeMissingDocument(final String label)
    {
        return "The " + label + " is missing, please upload it";
    }

    /**
     * Whether no parse is still waiting, so what can be read now is all there will be.
     *
     * @param submission the submission
     * @return {@code true} when no current file's parse is queued, including files never sent to be parsed
     */
    static boolean isEveryParseSettled(final Submission submission)
    {
        for (final File file : getCurrentFiles(submission)) {
            // A file never sent to be parsed has no status, and is not waiting for anything
            if (ParsePropertyNames.STATUS_QUEUED.equals(file.getParseStatus())) {
                return false;
            }
        }
        return true;
    }
}

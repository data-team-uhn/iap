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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.sling.api.resource.Resource;

import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.submissions.models.Document;
import io.uhndata.iap.submissions.models.DocumentVersion;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;

/**
 * The files of a submission, as the extraction steps need them: the latest upload of every document, in the order
 * the repository lists the documents.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class SubmissionFiles
{
    private SubmissionFiles()
    {
        // Utility
    }

    /**
     * The submission a part of one belongs to.
     *
     * @param part any node under a submission
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
     * The submission a resource is, read as a model.
     *
     * @param target the submission's resource
     * @return the model
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
     * @return its current files, skipping documents with no upload yet
     */
    static List<File> getCurrentFiles(final Submission submission)
    {
        final List<File> files = new ArrayList<>();
        for (final Document document : submission.getDocuments()) {
            final DocumentVersion version = document.getCurrentVersion();
            final File file = version == null ? null : version.getFile();
            if (file != null) {
                files.add(file);
            }
        }
        return files;
    }

    /**
     * Why there is nothing to read: no upload, a failed parse (with its reason), a parse still queued, or uploads
     * never sent.
     *
     * @param submission the submission
     * @return the reason, ready to show
     */
    static String describeNothingToRead(final Submission submission)
    {
        final List<File> files = getCurrentFiles(submission);
        if (files.isEmpty()) {
            return "No document has been uploaded";
        }
        for (final File file : files) {
            if (ParsePropertyNames.STATUS_FAILED.equals(file.getParseStatus())) {
                return describeUnreadable();
            }
        }
        for (final File file : files) {
            if (ParsePropertyNames.STATUS_QUEUED.equals(file.getParseStatus())) {
                return "The document has not been read yet";
            }
        }
        return "No uploaded document could be read";
    }

    /**
     * The first file whose parse finished, in the order the repository lists the documents. The intake reads it
     * when its step names no documents.
     *
     * @param submission the submission
     * @return the file, or {@code null} when no parse has finished
     */
    static File findFirstParsed(final Submission submission)
    {
        for (final File file : getCurrentFiles(submission)) {
            if (ParsePropertyNames.STATUS_COMPLETED.equals(file.getParseStatus())) {
                return file;
            }
        }
        return null;
    }

    /**
     * What the submitter is told when a file could not be parsed. The parse's own reason stays on the file and
     * in the log: it names the daemon, its paths and its workers, which tells the submitter nothing they can act
     * on.
     *
     * @return the message, ready to show
     */
    static String describeUnreadable()
    {
        return "The document could not be read. Please try again";
    }

    /**
     * The latest upload answering one document requirement, whatever its parse did.
     *
     * @param submission the submission
     * @param requirement the name of the document requirement, as the schema version calls it
     * @return the latest upload for it, or {@code null} when nothing is attached or nothing was uploaded
     */
    static File getCurrentFile(final Submission submission, final String requirement)
    {
        for (final Document document : submission.getDocuments()) {
            final Requirement fulfilled = document.getFulfills();
            final DocumentVersion version = document.getCurrentVersion();
            final File file = version == null ? null : version.getFile();
            if (fulfilled != null && requirement.equals(fulfilled.getName()) && file != null) {
                return file;
            }
        }
        return null;
    }

    /**
     * Whether no parse is still going, so that what there is to read is all there will be.
     *
     * @param submission the submission
     * @return {@code true} when every file's parse has ended, one way or the other, or was never asked for
     */
    static boolean isEveryParseSettled(final Submission submission)
    {
        for (final File file : getCurrentFiles(submission)) {
            // A file never sent has no status at all, which is not a parse still going
            if (ParsePropertyNames.STATUS_QUEUED.equals(file.getParseStatus())) {
                return false;
            }
        }
        return true;
    }
}

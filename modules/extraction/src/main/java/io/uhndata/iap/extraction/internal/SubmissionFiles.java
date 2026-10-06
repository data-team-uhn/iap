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
 * the documents were attached.
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
    static Resource submissionOf(final Resource part)
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
    static Submission submission(final Resource target)
    {
        return Objects.requireNonNull(target.adaptTo(Submission.class),
            "The extraction workflows only apply to submissions");
    }

    /**
     * The latest upload of every document, in document order.
     *
     * @param submission the submission
     * @return its current files, skipping documents with no upload yet
     */
    static List<File> currentFiles(final Submission submission)
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
     * Why there is nothing to read, said as precisely as what is recorded allows.
     *
     * <p>Called only once {@link #firstParsed} has answered nothing, which one of three things explains:
     * nothing was uploaded, what was uploaded has not been read yet, or reading it failed. The third is the
     * one worth spelling out, because the parse wrote down what went wrong - the daemon it could not reach,
     * the answer it did not understand - and that is what somebody needs in order to fix it.
     *
     * @param submission the submission
     * @return the reason, ready to show
     */
    static String whyNothingToRead(final Submission submission)
    {
        final List<File> files = currentFiles(submission);
        if (files.isEmpty()) {
            return "No document has been uploaded";
        }
        for (final File file : files) {
            if (ParsePropertyNames.STATUS_FAILED.equals(file.getParseStatus())) {
                return whyUnreadable(file);
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
     * The first file the parse finished for, which is the one the intake reads.
     *
     * @param submission the submission
     * @return the file, or {@code null} when no parse has finished
     */
    static File firstParsed(final Submission submission)
    {
        for (final File file : currentFiles(submission)) {
            if (ParsePropertyNames.STATUS_COMPLETED.equals(file.getParseStatus())) {
                return file;
            }
        }
        return null;
    }

    /**
     * Why one file cannot be read, including the reason the parse wrote down when it left one.
     *
     * @param file a file whose parse failed
     * @return the reason, ready to show
     */
    static String whyUnreadable(final File file)
    {
        final String reason = file.getParseError();
        return reason == null || reason.isBlank()
            ? "The document could not be read" : "The document could not be read. " + reason;
    }

    /**
     * The latest upload answering one document requirement, whatever its parse did.
     *
     * @param submission the submission
     * @param requirement the name of the document requirement, as the schema version calls it
     * @return the latest upload for it, or {@code null} when nothing is attached or nothing was uploaded
     */
    static File currentFor(final Submission submission, final String requirement)
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
    static boolean allParsesSettled(final Submission submission)
    {
        for (final File file : currentFiles(submission)) {
            // A file never sent has no status at all, which is not a parse still going
            if (ParsePropertyNames.STATUS_QUEUED.equals(file.getParseStatus())) {
                return false;
            }
        }
        return true;
    }
}

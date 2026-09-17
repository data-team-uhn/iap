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
import java.util.Set;

import org.apache.sling.api.resource.Resource;

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
    /** The parse states that mean a parse is still going. */
    private static final Set<String> IN_PROGRESS = Set.of("queued", "active");

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
     * The first file the parse finished for, which is the one the gate and the intake read.
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
     * Whether no parse is still going, so that what there is to read is all there will be.
     *
     * @param submission the submission
     * @return {@code true} when every file's parse has ended, one way or the other, or was never asked for
     */
    static boolean allParsesSettled(final Submission submission)
    {
        for (final File file : currentFiles(submission)) {
            final String status = file.getParseStatus();
            // A file never sent has no status, and an immutable set refuses to be asked about null
            if (status != null && IN_PROGRESS.contains(status)) {
                return false;
            }
        }
        return true;
    }
}

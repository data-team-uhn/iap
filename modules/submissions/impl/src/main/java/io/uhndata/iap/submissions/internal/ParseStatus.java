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

import io.uhndata.iap.submissions.models.Document;
import io.uhndata.iap.submissions.models.DocumentVersion;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;

/**
 * Where the uploads of a submission stand with the parse, and whether its documents are being read, from what the
 * extraction module writes: each file's {@code parseStatus} and the submission's {@code extractionStatus}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class ParseStatus
{
    /** A file's {@code parseStatus} once its parse failed, was lost, or was stopped. */
    private static final String FAILED_STATUS = "failed";

    /** A file's {@code parseStatus} while it waits on the daemon. */
    private static final String QUEUED_STATUS = "queued";

    /** Where a submission records how far reading its documents got. */
    private static final String EXTRACTION_STATUS_PROPERTY = "extractionStatus";

    /** The {@code extractionStatus} while documents are parsed or read. */
    private static final String RUNNING_STATUS = "running";

    private ParseStatus()
    {
        // Static helper
    }

    /**
     * Whether a current upload has a failed parse. Only then does sending it again do anything.
     *
     * @param submission the submission
     * @return {@code true} when at least one current upload failed to parse
     */
    public static boolean hasFailedParse(final Submission submission)
    {
        return submission.getDocuments().stream()
            .map(ParseStatus::getCurrentFile)
            .anyMatch(ParseStatus::isFailed);
    }

    /**
     * Why the first failed upload failed, as the parse wrote it. For the browser console only: it names the
     * daemon and its paths.
     *
     * @param submission the submission
     * @return the parse's own reason, or {@code null} when no current upload failed with one
     */
    public static String findParseError(final Submission submission)
    {
        return submission.getDocuments().stream()
            .map(ParseStatus::getCurrentFile)
            .filter(ParseStatus::isFailed)
            .map(File::getParseError)
            .filter(error -> error != null && !error.isBlank())
            .findFirst()
            .orElse(null);
    }

    /**
     * Whether no current upload still waits on the daemon. A file never sent has no status, and does not count as
     * waiting.
     *
     * @param submission the submission
     * @return {@code true} when no parse is queued
     */
    public static boolean isParsed(final Submission submission)
    {
        return submission.getDocuments().stream().noneMatch(ParseStatus::isParsing);
    }

    /**
     * Whether a document's current upload still waits on the daemon.
     *
     * @param document the document
     * @return {@code true} while its parse is queued
     */
    public static boolean isParsing(final Document document)
    {
        final File file = getCurrentFile(document);
        return file != null && QUEUED_STATUS.equals(file.getParseStatus());
    }

    /**
     * Whether the submission's documents are being parsed or read right now.
     *
     * @param submission the submission
     * @return {@code true} while its {@code extractionStatus} is {@code running}
     */
    public static boolean isReading(final Submission submission)
    {
        return RUNNING_STATUS.equals(submission.get(EXTRACTION_STATUS_PROPERTY, String.class));
    }

    private static boolean isFailed(final File file)
    {
        return file != null && FAILED_STATUS.equals(file.getParseStatus());
    }

    private static File getCurrentFile(final Document document)
    {
        final DocumentVersion version = document.getCurrentVersion();
        return version == null ? null : version.getFile();
    }
}

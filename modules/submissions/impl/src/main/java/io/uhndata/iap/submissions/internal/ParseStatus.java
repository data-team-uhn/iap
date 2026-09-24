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
 * What the daemon has done with the uploads on a submission, read off each file's {@code parseStatus}.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ParseStatus
{
    /** What an upload's {@code parseStatus} says when the daemon never produced anything for it. */
    private static final String FAILED = "failed";

    /** What an upload's {@code parseStatus} says while the daemon has not answered yet. */
    private static final String QUEUED = "queued";

    /** Where a submission records how far reading its documents got. */
    private static final String EXTRACTION_STATUS = "extractionStatus";

    /** What that says while documents are being parsed or read. */
    private static final String RUNNING = "running";

    private ParseStatus()
    {
    }

    /**
     * Whether any current upload is sitting on a failed parse, which is the one kind of failure asking again
     * can do something about.
     *
     * <p>Not the same question as the reading having failed. A reading fails for reasons of its own - the model
     * refusing or giving an answer that cannot be read - and sending the same document to the daemon a second
     * time does nothing about any of them. This decides whether the view offers to try again, so it has to mean
     * exactly the case where trying again is worth the click.</p>
     *
     * @param submission the submission being read
     * @return {@code true} when at least one upload failed to parse
     */
    static boolean hasFailed(final Submission submission)
    {
        for (final Document document : submission.getDocuments()) {
            final File file = getFile(document);
            if (file != null && FAILED.equals(file.getParseStatus())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether every current upload has left the daemon. A file never sent has no status, which is not a parse
     * still going; one still {@code queued} is.
     *
     * @param submission the submission being read
     * @return {@code true} when no upload is waiting on the daemon
     */
    static boolean isSettled(final Submission submission)
    {
        for (final Document document : submission.getDocuments()) {
            if (isParsing(document)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a document's current upload is still with the daemon.
     *
     * @param document the document
     * @return {@code true} while its parse is queued
     */
    static boolean isParsing(final Document document)
    {
        final File file = getFile(document);
        return file != null && QUEUED.equals(file.getParseStatus());
    }

    /**
     * Whether the submission's documents are being read right now.
     *
     * @param submission the submission
     * @return {@code true} while its extraction status is {@code running}
     */
    static boolean isRunning(final Submission submission)
    {
        return RUNNING.equals(submission.get(EXTRACTION_STATUS, String.class));
    }

    private static File getFile(final Document document)
    {
        final DocumentVersion version = document.getCurrentVersion();
        return version == null ? null : version.getFile();
    }
}

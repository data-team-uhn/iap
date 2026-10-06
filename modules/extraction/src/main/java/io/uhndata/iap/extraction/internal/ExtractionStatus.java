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

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;

/**
 * Where reading answers out of a submission's documents got to, as recorded on the submission for whoever is
 * looking at it. The states are the ones the submission view knows: {@link #RUNNING} shows a spinner, the rest
 * stop it and say why.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ExtractionStatus
{
    /** The property on the submission holding the state. */
    static final String PROPERTY = "extractionStatus";

    /** The property on the submission holding a short message for the person looking at it. */
    static final String MESSAGE = "extractionMessage";

    /**
     * The property on the submission saying a job has taken the reading. Every parse that lands queues a job, so
     * this is what keeps the second one from paying a model to read the same document again. Taken back down when
     * new documents are sent to be parsed, which is a new reading to be had.
     */
    static final String READING_CLAIMED = "extractionReadingClaimed";

    /**
     * The property naming the claim's own value. Two jobs racing to claim would both write {@code true}, and the
     * repository merges two identical changes without a conflict, so both would read. A value of each job's own
     * makes the two writes differ, and the second commit is refused.
     */
    static final String READING_CLAIMED_BY = "extractionReadingClaimedBy";

    /** Documents are being parsed or read; answers may still appear. */
    static final String RUNNING = "running";

    /** Every answer that could be read has been recorded. */
    static final String DONE = "done";

    /** Parsing or reading failed for a reason other than the document itself. */
    static final String FAILED = "failed";

    /** What the view is told when the submitter stopped the reading. */
    static final String STOPPED = "Reading was stopped.";

    private ExtractionStatus()
    {
        // Constants and one helper
    }

    /**
     * Take a reading's claim down, so the next reading can take it.
     *
     * @param submission the submission's resource, through a session that may write it
     * @throws PersistenceException if the submission cannot be written
     */
    static void releaseClaim(final Resource submission) throws PersistenceException
    {
        final ModifiableValueMap properties = submission.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            throw new PersistenceException("Not allowed to release the reading of " + submission.getPath());
        }
        releaseClaim(properties);
    }

    /**
     * The same, on properties already open for writing.
     *
     * @param properties the submission's properties
     */
    static void releaseClaim(final ModifiableValueMap properties)
    {
        properties.remove(READING_CLAIMED);
        properties.remove(READING_CLAIMED_BY);
    }

    /**
     * Record where extraction got to on a submission.
     *
     * @param submission the submission's resource, through a session that may write it
     * @param status one of the states above
     * @param message a short message for the person looking at it, or {@code null} to clear it
     * @throws PersistenceException if the submission cannot be written
     */
    static void record(final Resource submission, final String status, final String message)
        throws PersistenceException
    {
        final ModifiableValueMap properties = submission.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            throw new PersistenceException("Not allowed to record the extraction state on " + submission.getPath());
        }
        properties.put(PROPERTY, status);
        if (message == null) {
            properties.remove(MESSAGE);
        } else {
            properties.put(MESSAGE, message);
        }
    }
}

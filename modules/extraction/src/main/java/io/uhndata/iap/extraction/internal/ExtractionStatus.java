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

import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.utils.VersioningUtils;
import io.uhndata.iap.workflows.api.InvalidStateException;

/**
 * The reading status saved on a submission, for the submission view. {@link #STATUS_RUNNING} shows a spinner;
 * the other states stop it, and the message says why.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ExtractionStatus
{
    /** The property on the submission holding the state. */
    static final String STATUS_PROPERTY = "extractionStatus";

    /** The property on the submission holding a short message for the person looking at it. */
    static final String MESSAGE_PROPERTY = "extractionMessage";

    /**
     * The property on the submission saying a job has taken the reading. Every finished parse queues a job, so
     * this stops a second job from paying the model to read the same document again. It is removed when new
     * documents are sent to be parsed, when the submitter asks to read again or stops the reading, and when a
     * reading gives up.
     */
    static final String READING_CLAIMED_PROPERTY = "extractionReadingClaimed";

    /**
     * The property holding the id of the job that has the claim. If two jobs race, both write {@code true}, and
     * the repository merges two equal changes without a conflict, so both would read. Writing each job's own id
     * makes the writes differ, so the second commit is refused. The id also lets a job that Sling runs again
     * after a restart see that the claim is still its own.
     */
    static final String READING_CLAIMED_BY_PROPERTY = "extractionReadingClaimedBy";

    /**
     * The property on the submission saying a reading ended with the request unable to go on, such as when
     * a document is not what the request asks for. The message says why. Nothing removes it: the same documents would
     * get the same answer, so no new reading or parse is let start.
     */
    static final String BLOCKED_PROPERTY = "extractionBlocked";

    /** Documents are being parsed or read; answers may still appear. */
    static final String STATUS_RUNNING = "running";

    /** Every answer that could be read has been recorded. */
    static final String STATUS_DONE = "done";

    /** Parsing or reading failed; the message says why. Runs saved by earlier steps may stay. */
    static final String STATUS_FAILED = "failed";

    /** What the view is told when the submitter stopped the reading. */
    static final String STOPPED_MESSAGE = "Reading was stopped.";

    private ExtractionStatus()
    {
        // Constants and helpers
    }

    /**
     * Removes the reading claim, so the next reading can take it.
     *
     * @param submission the submission's resource, through a session that may write it
     * @throws PersistenceException if the submission cannot be written
     */
    static void releaseClaim(final Resource submission) throws PersistenceException
    {
        releaseClaim(getProperties(submission));
    }

    /**
     * Removes the reading claim from properties already open for writing.
     *
     * @param properties the submission's properties
     */
    static void releaseClaim(final ModifiableValueMap properties)
    {
        properties.remove(READING_CLAIMED_PROPERTY);
        properties.remove(READING_CLAIMED_BY_PROPERTY);
    }

    /**
     * Marks the request as unable to go on after this reading. It stays so for good.
     *
     * @param submission the submission's resource, through a session that may write it
     * @throws PersistenceException if the submission cannot be written
     */
    static void markBlocked(final Resource submission) throws PersistenceException
    {
        getProperties(submission).put(BLOCKED_PROPERTY, true);
    }

    /**
     * Whether a reading of the submission is going on.
     *
     * @param submission the submission
     * @return {@code true} when its status is {@link #STATUS_RUNNING}
     */
    static boolean isRunning(final Resource submission)
    {
        return STATUS_RUNNING.equals(submission.getValueMap().get(STATUS_PROPERTY, String.class));
    }

    /**
     * Whether a reading of the submission is going on, read from its model.
     *
     * @param submission the submission
     * @return {@code true} when its status is {@link #STATUS_RUNNING}
     */
    static boolean isRunning(final Submission submission)
    {
        return STATUS_RUNNING.equals(submission.get(STATUS_PROPERTY, String.class));
    }

    /**
     * Saves the reading status and message on a submission.
     *
     * @param submission the submission's resource, through a session that may write it
     * @param status one of the {@code STATUS_} constants
     * @param message a short message for the person looking at it, or {@code null} to clear it
     * @throws PersistenceException if the submission cannot be written
     */
    static void recordStatus(final Resource submission, final String status, final String message)
        throws PersistenceException
    {
        final ModifiableValueMap properties = getProperties(submission);
        properties.put(STATUS_PROPERTY, status);
        if (message == null) {
            properties.remove(MESSAGE_PROPERTY);
        } else {
            properties.put(MESSAGE_PROPERTY, message);
        }
    }

    /**
     * Ends the reading as {@link #STATUS_DONE} and releases the claim, so the next reading can take it.
     *
     * @param submission the submission's resource, through a session that may write it
     * @param message a short message for the person looking at it, or {@code null} to clear it
     * @throws PersistenceException if the submission cannot be written
     */
    static void endReading(final Resource submission, final String message) throws PersistenceException
    {
        releaseClaim(submission);
        recordStatus(submission, STATUS_DONE, message);
    }

    /**
     * Checks the submission out and opens its properties for writing.
     *
     * @param submission the submission's resource, through a session that may write it
     * @return the submission's properties
     * @throws PersistenceException if the submission cannot be checked out or written
     */
    static ModifiableValueMap getWritableProperties(final Resource submission) throws PersistenceException
    {
        VersioningUtils.checkOut(submission);
        return getProperties(submission);
    }

    /**
     * Checks the submission out for a job to write what it read, once sure the job still holds the reading. After a
     * Stop or a Read again the reading is someone else's, and a late result must not land on it.
     *
     * @param submission the submission's resource, through a session that may write it
     * @param jobId the job's id, which the claim holds
     * @throws InvalidStateException if the reading is no longer this job's
     * @throws PersistenceException if the submission cannot be checked out or written
     */
    static void checkOutForJob(final Resource submission, final String jobId)
        throws InvalidStateException, PersistenceException
    {
        final ModifiableValueMap properties = getWritableProperties(submission);
        if (!jobId.equals(properties.get(READING_CLAIMED_BY_PROPERTY, String.class))) {
            throw new InvalidStateException("The reading of " + submission.getPath() + " is no longer this job's");
        }
    }

    private static ModifiableValueMap getProperties(final Resource submission) throws PersistenceException
    {
        final ModifiableValueMap properties = submission.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            throw new PersistenceException("Not allowed to write the reading of " + submission.getPath());
        }
        return properties;
    }
}

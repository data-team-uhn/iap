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

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;

import io.uhndata.iap.utils.VersioningUtils;

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
     * The property on the submission saying the last reading ended with the request unable to go on, such as when
     * a document is not what the request asks for. The message says why. It is removed when a new reading starts.
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
        final ModifiableValueMap properties = submission.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            throw new PersistenceException("Not allowed to release the reading of " + submission.getPath());
        }
        releaseClaim(properties);
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
     * Marks the request as unable to go on after this reading, until a new reading starts.
     *
     * @param submission the submission's resource, through a session that may write it
     * @throws PersistenceException if the submission cannot be written
     */
    static void markBlocked(final Resource submission) throws PersistenceException
    {
        final ModifiableValueMap properties = submission.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            throw new PersistenceException("Not allowed to mark " + submission.getPath() + " as blocked");
        }
        properties.put(BLOCKED_PROPERTY, true);
    }

    /**
     * Leaves a note for the person looking at the submission, without changing the status. The step that ends
     * the reading ({@code finishReading}) keeps it, unless it has a message of its own.
     *
     * @param submission the submission's resource, through a session that may write it
     * @param message the note
     * @throws PersistenceException if the submission cannot be written
     */
    static void recordNote(final Resource submission, final String message) throws PersistenceException
    {
        final ModifiableValueMap properties = submission.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            throw new PersistenceException("Not allowed to record a note on " + submission.getPath());
        }
        properties.put(MESSAGE_PROPERTY, message);
    }

    /**
     * Saves the reading status and message on a submission. Setting {@code running} also clears the blocked mark.
     *
     * @param submission the submission's resource, through a session that may write it
     * @param status one of the {@code STATUS_} constants
     * @param message a short message for the person looking at it, or {@code null} to clear it
     * @throws PersistenceException if the submission cannot be written
     */
    static void recordStatus(final Resource submission, final String status, final String message)
        throws PersistenceException
    {
        final ModifiableValueMap properties = submission.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            throw new PersistenceException("Not allowed to record the extraction state on " + submission.getPath());
        }
        properties.put(STATUS_PROPERTY, status);
        if (STATUS_RUNNING.equals(status)) {
            // A new reading decides afresh whether the request can go on
            properties.remove(BLOCKED_PROPERTY);
        }
        if (message == null) {
            properties.remove(MESSAGE_PROPERTY);
        } else {
            properties.put(MESSAGE_PROPERTY, message);
        }
    }

    /**
     * Checks out the submission when it is versionable, so it can be written. A checked-in submission is
     * read-only, with everything under it. A checkout lasts, so one call before the first write covers every
     * write after it.
     *
     * @param submission the submission's resource, through a session that may check it out
     * @throws PersistenceException if it cannot be checked out
     */
    static void checkOut(final Resource submission) throws PersistenceException
    {
        final Node node = submission.adaptTo(Node.class);
        try {
            if (node != null && node.isNodeType("mix:versionable")) {
                VersioningUtils.checkOut(node);
            }
        } catch (final RepositoryException e) {
            throw new PersistenceException("Cannot check out " + submission.getPath(), e);
        }
    }
}

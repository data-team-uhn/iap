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

import java.util.Objects;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.spi.Payloads;

/**
 * The payload of the events a reading job sends to the submission's system workflows, read the same way by every
 * handler that takes one.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ReadingEvents
{
    /** The payload entry naming the job, whose id the claim holds. */
    static final String JOB_KEY = "job";

    /** The payload entry holding the message for the submitter; absent when there is nothing to say. */
    static final String MESSAGE_KEY = "message";

    /** The id of the reading a job was queued for, under this name in the job's properties and in its events. */
    static final String READING_ID_KEY = "readingId";

    /** The payload entry naming the decision requirement that was asked, by path. */
    static final String REQUIREMENT_KEY = "requirement";

    /** The payload entry naming the file that was read, by path. */
    static final String FILE_KEY = "file";

    /** The payload entry holding what the model answered, a {@link FieldResult}. */
    static final String RESULT_KEY = "result";

    private ReadingEvents()
    {
        // Constants and helpers
    }

    /**
     * Reads the id of the job that sent the event.
     *
     * @param event the event
     * @return the job's id
     * @throws InvalidPayloadException if the event names no job
     */
    static String getJobId(final WorkflowEvent event) throws InvalidPayloadException
    {
        return Payloads.requireText(event, JOB_KEY, "The event does not name the job");
    }

    /**
     * Whether the event comes from a job queued for the reading going on now.
     *
     * @param event the event
     * @param submission the submission's properties
     * @return {@code true} when the event's reading id is the submission's
     */
    static boolean isForCurrentReading(final WorkflowEvent event, final ValueMap submission)
    {
        return isCurrentReading(Objects.toString(event.get(READING_ID_KEY), null), submission);
    }

    /**
     * Whether a reading id is the one of the reading going on now. The job and the steps it fires both ask it.
     *
     * @param readingId the id a job was queued with, or {@code null} when it has none
     * @param submission the submission's properties
     * @return {@code true} when the id is the submission's
     */
    static boolean isCurrentReading(final String readingId, final ValueMap submission)
    {
        return Objects.equals(readingId, submission.get(ExtractionStatus.READING_ID_PROPERTY, String.class));
    }

    /**
     * Finds an uploaded file the event names.
     *
     * @param resolver the session to read through
     * @param path the file's path, from the event
     * @return the file
     * @throws InvalidPayloadException if there is no uploaded file at that path
     */
    static File getFile(final ResourceResolver resolver, final String path) throws InvalidPayloadException
    {
        final Resource file = resolver.getResource(path);
        if (file == null || !file.isResourceType(File.RESOURCE_TYPE)) {
            throw new InvalidPayloadException("The event names no document at " + path);
        }
        return file.adaptTo(File.class);
    }
}

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
package io.uhndata.iap.documents.internal;

import java.util.Calendar;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

import io.uhndata.iap.documents.api.ParseOutcome;

/**
 * The record of one parse job, as the dispatch, the callback, a stop and the sweep read and settle it.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class JobRecord
{
    private final Resource node;

    /**
     * Wraps a job's record.
     *
     * @param node the job's node under {@link ParseJob#JOBS_PATH}
     */
    JobRecord(final Resource node)
    {
        this.node = node;
    }

    /**
     * Whether the job has an outcome, good or bad.
     *
     * @return {@code true} once the job completed or failed
     */
    boolean isSettled()
    {
        final String status = getStatus();
        return ParseJob.STATUS_COMPLETED.equals(status) || ParseJob.STATUS_FAILED.equals(status);
    }

    /**
     * Whether the job is still waiting for its outcome.
     *
     * @return {@code true} while the job is queued or active
     */
    boolean isWaiting()
    {
        final String status = getStatus();
        return ParseJob.STATUS_QUEUED.equals(status) || ParseJob.STATUS_ACTIVE.equals(status);
    }

    /**
     * Whether the job completed.
     *
     * @return {@code true} for a completed job
     */
    boolean isCompleted()
    {
        return ParseJob.STATUS_COMPLETED.equals(getStatus());
    }

    /**
     * Whether a stop settled the job.
     *
     * @return {@code true} for a stopped job
     */
    boolean isStopped()
    {
        return ParseJob.STATUS_FAILED.equals(getStatus())
            && ParseOutcome.STOPPED_ERROR.equals(getProperties().get(ParseJob.PN_ERROR, String.class));
    }

    /**
     * Settles the job as failed, without committing. A failed job has no outputs to read, so any are dropped.
     *
     * @param error what went wrong, as the job's waiters will be told
     * @throws PersistenceException if the session cannot change the node
     */
    void markFailed(final String error) throws PersistenceException
    {
        final ModifiableValueMap properties = this.node.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            throw new PersistenceException("The job node cannot be modified");
        }
        properties.put(ParseJob.PN_STATUS, ParseJob.STATUS_FAILED);
        properties.put(ParseJob.PN_ERROR, error);
        properties.put(ParseJob.PN_FINISHED, Calendar.getInstance());
        properties.remove(ParseJob.PN_OUTPUTS);
    }

    private String getStatus()
    {
        return getProperties().get(ParseJob.PN_STATUS, String.class);
    }

    private ValueMap getProperties()
    {
        return this.node.getValueMap();
    }
}

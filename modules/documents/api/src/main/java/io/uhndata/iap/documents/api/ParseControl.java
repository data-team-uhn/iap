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
package io.uhndata.iap.documents.api;

import org.jetbrains.annotations.Nullable;

/**
 * Stops a parse that nobody needs any more.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface ParseControl
{
    /**
     * Stops one parse that has not finished.
     *
     * <p>A queued or active job is marked failed with the error {@code Stopped}. Then the daemon is asked to drop
     * the conversion, without waiting for its answer. The job keeps its target, so the sweep later hands this
     * failure to whoever still waits on the job, then deletes the record and its staging folder.</p>
     *
     * <p>A job that already has an outcome is left as it is, and the daemon is not asked. A record that changes
     * while it is being stopped is also left as it is, but the daemon is still asked, because the change may be
     * the dispatch and not an outcome. A valid job id with no record is still sent to the daemon, which answers
     * {@code unknown}. Nothing is sent for {@code null} or for a value that is not a job id.</p>
     *
     * @param jobId the id of the job to stop, may be {@code null}
     * @return {@code true} when no job record names the staging folder, so the caller should delete it;
     *         {@code false} when a record names it, or may name it, and the sweep deletes the folder with the record
     */
    boolean stopParse(@Nullable String jobId);
}

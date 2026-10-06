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
 * Stopping a parse nobody needs any more. The daemon is asked to drop the conversion, and the job record is marked
 * failed. A job that already has an outcome is left alone.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface ParseControl
{
    /**
     * Stop one parse.
     *
     * <p>The daemon is asked to drop the conversion, without waiting for its answer. A queued or running job is
     * marked failed with the error {@code Stopped}; it keeps its target, so the sweep hands that failure to whoever
     * still waits on the job, and later drops the record together with its staging folder. A job that already has
     * an outcome, or gets one while it is being stopped, is left as it is. An unknown identifier is still sent to
     * the daemon, which answers {@code unknown}, and nothing else happens.</p>
     *
     * @param jobId the identifier of the job to stop
     * @return {@code true} only when no job record names the staging folder, so the caller may remove it;
     *         {@code false} when a record does, or may, and the sweep removes the folder with it
     */
    boolean abandon(@Nullable String jobId);
}

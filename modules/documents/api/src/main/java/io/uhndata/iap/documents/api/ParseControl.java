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
 * Stopping a parse that has not finished. The daemon is asked to drop the conversion, the staging folder is wiped,
 * and the job record is deleted, which is the same finishing a completed parse gets once its outcome has been taken.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface ParseControl
{
    /**
     * Stop one parse and forget it.
     *
     * <p>A job the daemon has already finished is left for its callback: deleting that record here would drop an
     * outcome somebody is still waiting on. A job that is queued or running is stopped, its folder is removed, and
     * its record goes with it. An unknown identifier does nothing.</p>
     *
     * @param jobId the identifier of the job to stop
     */
    void abandon(@Nullable String jobId);
}

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

/**
 * How a parse ended, for the node it was queued for.
 *
 * @param jobId the job's identifier
 * @param target the repository path the parse was queued for, see {@link ParseService#queue}
 * @param succeeded whether the daemon produced anything
 * @param error what went wrong, when it did not; {@code null} on success
 * @param markdownPath where the Markdown rendition is, on the shared volume; {@code null} on failure
 * @param tokens roughly how large the document turned out to be, as the daemon measured it; {@code null} when
 *            it did not say
 * @param offeredAgain whether the sweep offers it again after no handler took it, minutes after it settled; the
 *            request that queued the parse is long over, so its target is as it will stay
 * @version $Id$
 * @since 0.1.0
 */
public record ParseOutcome(String jobId, String target, boolean succeeded, String error, String markdownPath,
    Long tokens, boolean offeredAgain)
{
    /** The error of a parse that was stopped before it finished, see {@link ParseControl#stopParse}. */
    public static final String STOPPED_ERROR = "Stopped";

    /**
     * Whether the parse was stopped before it finished, rather than failing on its own.
     *
     * @return {@code true} for a stopped parse
     */
    public boolean isStopped()
    {
        return !this.succeeded && STOPPED_ERROR.equals(this.error);
    }
}

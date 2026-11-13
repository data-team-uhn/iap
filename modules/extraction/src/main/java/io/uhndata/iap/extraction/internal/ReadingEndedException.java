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

/**
 * The reading ended under the job: the job gave it up, or a Stop or a Read again took it. The job stops there, and
 * {@link ReadingJobs} ends it quietly, since whatever ended the reading already said why.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ReadingEndedException extends Exception
{
    private static final long serialVersionUID = 1L;

    /**
     * A reading the job gave up itself.
     */
    ReadingEndedException()
    {
        super("The job gave the reading up");
    }

    /**
     * A reading something else took from the job.
     *
     * @param cause why the engine refused the job's outcome
     */
    ReadingEndedException(final Throwable cause)
    {
        super("The reading is no longer this job's", cause);
    }
}

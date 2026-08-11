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

/**
 * The vocabulary of a document parse job. A job is one node under {@link #JOBS_PATH}, named by its identifier,
 * created when a parse is requested and updated as the parse progresses, so that its state can be polled at any
 * time. Java owns the lifecycle: the daemon accepts a dispatch, converts in the background, and reports the outcome
 * to the callback endpoint, which records it here — the daemon itself keeps no job state.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ParseJob
{
    /** The Sling job topic under which parse jobs are queued. */
    static final String TOPIC = "iap/documents/parse";

    /** The absolute path of the node holding all the parse job nodes. */
    static final String JOBS_PATH = "/var/documents/jobs";

    /** The path of the endpoint the daemon calls back with parse outcomes. */
    static final String CALLBACK_PATH = "/system/documents/parseCallback";

    /** The environment variable carrying the shared callback JWT, same name on both sides. */
    static final String TOKEN_VARIABLE = "IAP_DOCLING_CALLBACK_JWT";

    /** The name of the subservice performing all repository access to the job nodes. */
    static final String SUBSERVICE = "parse-jobs";

    /** The name of the property holding the job identifier, a random UUID. */
    static final String PN_JOB_ID = "jobId";

    /** The name of the property holding the current status, one of the {@code STATUS_*} values. */
    static final String PN_STATUS = "status";

    /** The name of the property holding the path of the document to parse, as seen by the daemon. */
    static final String PN_PATH = "path";

    /** The name of the property holding whether the document should also be chunked. */
    static final String PN_CHUNK = "chunk";

    /** The name of the property holding the moment the job was created. */
    static final String PN_CREATED = "created";

    /** The name of the property holding the moment the daemon call started. */
    static final String PN_STARTED = "started";

    /** The name of the property holding the moment the job completed or failed. */
    static final String PN_FINISHED = "finished";

    /** The name of the property holding the paths of the produced outputs, once completed. */
    static final String PN_OUTPUTS = "outputs";

    /** The name of the property holding what went wrong, once failed. */
    static final String PN_ERROR = "error";

    /** The job is created and waiting for a free worker. */
    static final String STATUS_QUEUED = "queued";

    /** The daemon is parsing the document. */
    static final String STATUS_ACTIVE = "active";

    /** The parse finished and the outputs are recorded on the job. */
    static final String STATUS_COMPLETED = "completed";

    /** The parse could not be completed; the error is recorded on the job. */
    static final String STATUS_FAILED = "failed";

    private ParseJob()
    {
        // Constants only
    }

    /**
     * The path of the node backing a job.
     *
     * @param jobId the identifier of the job
     * @return an absolute path under {@link #JOBS_PATH}
     */
    static String nodePath(final String jobId)
    {
        return JOBS_PATH + "/" + jobId;
    }
}

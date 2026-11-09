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

import java.io.StringReader;

import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

/**
 * Reads what the daemon answered to a dispatch, for {@link ParseJobConsumer}.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class DaemonReply
{
    /** How much of a non-JSON daemon answer is kept as the error message. */
    private static final int ERROR_EXCERPT_LENGTH = 200;

    private DaemonReply()
    {
        // Utility
    }

    /**
     * Whether a daemon answer is the asynchronous accept the consumer expects, rather than a synchronous parse
     * result or some other 2xx body.
     *
     * @param jobId the job that was dispatched
     * @param body the response body
     * @return {@code true} only for {@code {"job_id": <same>, "status": "queued"}}
     */
    static boolean isAsyncAccept(final String jobId, final String body)
    {
        if (body == null || body.isBlank()) {
            return false;
        }
        try (JsonReader reader = Json.createReader(new StringReader(body))) {
            final JsonObject json = reader.readObject();
            return ParseJob.STATUS_QUEUED.equals(json.getString(ParseJob.PN_STATUS, null))
                && jobId.equals(json.getString(ParseJob.JSON_JOB_ID, null));
        } catch (final JsonException | ClassCastException e) {
            return false;
        }
    }

    /**
     * Extract the daemon's own error message from a refusal, falling back to an excerpt of the raw body when it is
     * not the usual {@code {"error": "..."}}.
     *
     * @param body the response body
     * @return a message safe to record on the job node
     */
    static String getErrorMessage(final String body)
    {
        if (body == null || body.isBlank()) {
            return "(empty response)";
        }
        try (JsonReader reader = Json.createReader(new StringReader(body))) {
            final String error = reader.readObject().getString(ParseJob.PN_ERROR, null);
            if (error != null) {
                return error;
            }
        } catch (final JsonException | ClassCastException e) {
            // Not JSON, fall through to the raw excerpt
        }
        return body.length() > ERROR_EXCERPT_LENGTH ? body.substring(0, ERROR_EXCERPT_LENGTH) + "…" : body;
    }
}

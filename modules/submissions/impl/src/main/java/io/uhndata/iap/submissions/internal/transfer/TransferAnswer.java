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
package io.uhndata.iap.submissions.internal.transfer;

import java.io.StringReader;

import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import io.uhndata.iap.httprequests.api.HttpResponse;
import io.uhndata.iap.utils.JsonResponses;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;

/**
 * How the sending instance reads the answer of the instance it sent a submission to.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class TransferAnswer
{
    private static final int CONFLICT_STATUS = 409;

    private static final int CLIENT_ERRORS = 400;

    private static final int SERVER_ERRORS = 500;

    private TransferAnswer()
    {
        // Static helpers only
    }

    /**
     * Turns an answer into the outcome of the transfer. The submission was taken when the answer says so, or when it
     * says the submission is already there, sent from here before.
     *
     * @param destination the instance that answered
     * @param response its answer
     * @throws InvalidStateException when it refuses the submission as it stands, e.g. knowing nobody by that email
     * @throws InvalidPayloadException when it cannot use the request at all
     * @throws WorkflowFailedException when it failed
     */
    static void read(final String destination, final HttpResponse response) throws WorkflowException
    {
        if (response.isSuccessful()) {
            return;
        }
        final JsonObject refusal = refusal(response.getBody());
        final int status = response.getStatusCode();
        if (status == CONFLICT_STATUS
            && TransferFormat.ALREADY_RECEIVED.equals(refusal.getString(JsonResponses.STATUS_KEY, null))) {
            return;
        }
        final String said = destination + " did not take the submission: "
            + refusal.getString(JsonResponses.ERROR_KEY, String.valueOf(status));
        if (status == CONFLICT_STATUS) {
            throw new InvalidStateException(said);
        }
        if (status >= CLIENT_ERRORS && status < SERVER_ERRORS) {
            throw new InvalidPayloadException(said);
        }
        throw new WorkflowFailedException(said);
    }

    /**
     * What a refusal says, or nothing when it is not JSON: a proxy in the way answers in its own words.
     */
    private static JsonObject refusal(final String body)
    {
        try (JsonReader reader = Json.createReader(new StringReader(body))) {
            return reader.readObject();
        } catch (final JsonException e) {
            return JsonValue.EMPTY_JSON_OBJECT;
        }
    }
}

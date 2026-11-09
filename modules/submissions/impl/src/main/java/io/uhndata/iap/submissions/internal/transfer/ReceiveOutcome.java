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

import java.io.IOException;

import jakarta.json.Json;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletResponse;

import io.uhndata.iap.utils.JsonResponses;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.NoApplicableWorkflowException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowException;

/**
 * What the receiving instance answers the instance that sent it a submission, in the shape every command is answered
 * in: an outcome the sender can switch on, with the submission's path, or an error with its reason.
 *
 * @param status the HTTP status
 * @param outcome the outcome, or {@link JsonResponses#ERROR_OUTCOME} for a refusal
 * @param path where the submission is, for an outcome that has one
 * @param error why it was refused, for a refusal
 * @version $Id$
 * @since 0.1.0
 */
record ReceiveOutcome(int status, String outcome, String path, String error)
{
    /** The outcome of a submission taken in. */
    static final String CREATED_OUTCOME = "created";

    /**
     * The submission was taken in.
     *
     * @param path where it now is
     * @return a {@code 201}
     */
    static ReceiveOutcome created(final String path)
    {
        return new ReceiveOutcome(HttpServletResponse.SC_CREATED, CREATED_OUTCOME, path, null);
    }

    /**
     * The submission is already here, from the instance sending it again, which counts it as delivered.
     *
     * @param path where it is
     * @return a {@code 409} whose outcome is {@link TransferFormat#ALREADY_RECEIVED}
     */
    static ReceiveOutcome alreadyReceived(final String path)
    {
        return new ReceiveOutcome(HttpServletResponse.SC_CONFLICT, TransferFormat.ALREADY_RECEIVED, path, null);
    }

    /**
     * The submission was refused.
     *
     * @param status the HTTP status
     * @param error why, for the people on the sending instance
     * @return the refusal
     */
    static ReceiveOutcome refused(final int status, final String error)
    {
        return new ReceiveOutcome(status, JsonResponses.ERROR_OUTCOME, null, error);
    }

    /**
     * What the workflows' refusal means to the sender, as the platform's own event endpoint answers it.
     *
     * @param refusal why the workflows did not take the submission
     * @return the refusal
     */
    static ReceiveOutcome of(final WorkflowException refusal)
    {
        if (refusal instanceof InvalidStateException || refusal instanceof NoApplicableWorkflowException) {
            return refused(HttpServletResponse.SC_CONFLICT, refusal.getMessage());
        }
        if (refusal instanceof NotAuthorizedException) {
            return refused(HttpServletResponse.SC_FORBIDDEN, refusal.getMessage());
        }
        if (refusal instanceof InvalidPayloadException) {
            return refused(HttpServletResponse.SC_BAD_REQUEST, refusal.getMessage());
        }
        // Not the sender's to act on, and the reason belongs in this instance's log rather than another's
        return refused(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "The submission could not be taken in; the reason is in the receiving instance's log");
    }

    /**
     * Writes this answer.
     *
     * @param response the HTTP response to write into
     * @throws IOException if the response cannot be written
     */
    void write(final SlingJakartaHttpServletResponse response) throws IOException
    {
        if (this.error != null) {
            JsonResponses.writeError(response, this.status, this.error);
        } else {
            JsonResponses.write(response, this.status, this.outcome, Json.createObjectBuilder().add("path", this.path));
        }
    }
}

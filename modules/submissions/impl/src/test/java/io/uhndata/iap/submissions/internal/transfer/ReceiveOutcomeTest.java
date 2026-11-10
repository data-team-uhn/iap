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

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.NoApplicableWorkflowException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link ReceiveOutcome}: what the sending instance is told, in the shape every command is answered
 * in, and that refusals are answered the way the platform's own event endpoint answers them.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ReceiveOutcomeTest
{
    private static final String PATH = "/Submissions/ab/cd/s1";

    @Test
    void aSubmissionTakenInSaysWhereItIs() throws Exception
    {
        final JsonObject body = written(ReceiveOutcome.created(PATH), 201);

        assertEquals("created", body.getString("status"));
        assertEquals(PATH, body.getString("path"));
    }

    @Test
    void aSubmissionAlreadyHereIsAnOutcomeTheSenderCanSwitchOn() throws Exception
    {
        final JsonObject body = written(ReceiveOutcome.alreadyReceived(PATH), 409);

        assertEquals("alreadyReceived", body.getString("status"));
        assertEquals(PATH, body.getString("path"));
    }

    @Test
    void aRefusalSaysWhy() throws Exception
    {
        final JsonObject body = written(ReceiveOutcome.refused(409, "Nobody here"), 409);

        assertEquals("error", body.getString("status"));
        assertEquals("Nobody here", body.getString("error"));
    }

    @Test
    void theWorkflowsRefusalsAreAnsweredAsTheEventEndpointAnswersThem()
    {
        assertEquals(409, ReceiveOutcome.of(new InvalidStateException("not now")).status());
        assertEquals("not now", ReceiveOutcome.of(new InvalidStateException("not now")).error());
        assertEquals(409, ReceiveOutcome.of(new NoApplicableWorkflowException("nothing waits")).status());
        assertEquals(403, ReceiveOutcome.of(new NotAuthorizedException("not you")).status());
        assertEquals(400, ReceiveOutcome.of(new InvalidPayloadException("not like that")).status());
    }

    @Test
    void aFailureHereDoesNotTellTheSenderWhy()
    {
        // The reason can name this instance's infrastructure, and is in its own log
        final ReceiveOutcome failed = ReceiveOutcome.of(new WorkflowFailedException("Oak segment store unreachable"));

        assertEquals(500, failed.status());
        assertEquals("The submission could not be taken in; the reason is in the receiving instance's log",
            failed.error());
    }

    private static JsonObject written(final ReceiveOutcome outcome, final int status) throws Exception
    {
        final SlingJakartaHttpServletResponse response = Mockito.mock(SlingJakartaHttpServletResponse.class);
        final StringWriter body = new StringWriter();
        Mockito.when(response.getWriter()).thenReturn(new PrintWriter(body));

        outcome.write(response);

        Mockito.verify(response).setStatus(status);
        return Json.createReader(new StringReader(body.toString())).readObject();
    }
}

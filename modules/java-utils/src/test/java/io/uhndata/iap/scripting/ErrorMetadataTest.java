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
package io.uhndata.iap.scripting;

import java.io.StringReader;

import javax.script.SimpleBindings;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.servlet.RequestDispatcher;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ErrorMetadata}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ErrorMetadataTest
{
    private SlingJakartaHttpServletRequest request;

    private SlingJakartaHttpServletResponse response;

    private ErrorMetadata error;

    @BeforeEach
    void setUp()
    {
        this.request = mock(SlingJakartaHttpServletRequest.class);
        this.response = mock(SlingJakartaHttpServletResponse.class);
        this.error = new ErrorMetadata();
    }

    private void init()
    {
        final SimpleBindings bindings = new SimpleBindings();
        bindings.put("jakartaRequest", this.request);
        bindings.put("jakartaResponse", this.response);
        this.error.init(bindings);
    }

    @Test
    void exposesTheRecordedStatusCodeAndMessage()
    {
        when(this.request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE)).thenReturn(403);
        when(this.request.getAttribute(RequestDispatcher.ERROR_MESSAGE)).thenReturn("Access denied to /some/path");

        init();

        assertEquals(403, this.error.getStatusCode());
        assertEquals("Access denied to /some/path", this.error.getStatusMessage());
    }

    @Test
    void setsTheStatusCodeOnTheResponse()
    {
        when(this.request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE)).thenReturn(404);

        init();

        verify(this.response).setStatus(404);
    }

    @Test
    void defaultsToInternalServerErrorWhenNoStatusCodeWasRecorded()
    {
        init();

        assertEquals(500, this.error.getStatusCode());
        assertEquals("Internal Server Error", this.error.getStatusMessage());
        verify(this.response).setStatus(500);
    }

    @Test
    void defaultsToTheReasonPhraseWhenNoMessageWasRecorded()
    {
        when(this.request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE)).thenReturn(423);

        init();

        assertEquals("Locked", this.error.getStatusMessage());
    }

    @Test
    void defaultsToTheReasonPhraseWhenTheRecordedMessageIsBlank()
    {
        when(this.request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE)).thenReturn(409);
        when(this.request.getAttribute(RequestDispatcher.ERROR_MESSAGE)).thenReturn("  ");

        init();

        assertEquals("Conflict", this.error.getStatusMessage());
    }

    @Test
    void fallsBackToAGenericMessageForUnknownStatusCodes()
    {
        when(this.request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE)).thenReturn(418);

        init();

        assertEquals("Error", this.error.getStatusMessage());
    }

    @Test
    void returnsNoJsonWhenTheClientDidNotAskForIt()
    {
        init();

        assertNull(this.error.getJson());
    }

    @Test
    void returnsTheErrorAsJsonWhenTheClientAsksForIt()
    {
        when(this.request.getHeader("Accept")).thenReturn("application/json");
        when(this.request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE)).thenReturn(404);
        when(this.request.getAttribute(RequestDispatcher.ERROR_MESSAGE)).thenReturn("No \"thing\" here");

        init();

        final JsonObject json = Json.createReader(new StringReader(this.error.getJson())).readObject();
        assertEquals("error", json.getString("status"));
        assertEquals("No \"thing\" here", json.getString("status.message"));
        assertEquals(404, json.getInt("status.code"));
    }
}

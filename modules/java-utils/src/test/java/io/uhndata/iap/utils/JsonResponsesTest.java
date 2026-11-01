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
package io.uhndata.iap.utils;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link JsonResponses}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class JsonResponsesTest
{
    private final StringWriter output = new StringWriter();

    private SlingJakartaHttpServletResponse response;

    @BeforeEach
    void setUp() throws Exception
    {
        this.response = Mockito.mock(SlingJakartaHttpServletResponse.class);
        Mockito.when(this.response.getWriter()).thenReturn(new PrintWriter(this.output, true));
    }

    @Test
    void writesAnOutcomeAlone() throws Exception
    {
        JsonResponses.write(this.response, 200, "ok");

        assertEquals(Json.createObjectBuilder().add("status", "ok").build(), parse());
        Mockito.verify(this.response).setStatus(200);
        Mockito.verify(this.response).setContentType("application/json");
        Mockito.verify(this.response).setCharacterEncoding("UTF-8");
    }

    @Test
    void writesAnOutcomeWithItsDetails() throws Exception
    {
        JsonResponses.write(this.response, 201, "created", Json.createObjectBuilder().add("path", "/Things/one"));

        assertEquals(Json.createObjectBuilder().add("status", "created").add("path", "/Things/one").build(), parse());
        Mockito.verify(this.response).setStatus(201);
    }

    @Test
    void refusesDetailsThatSetTheOutcomeOrAnError() throws Exception
    {
        assertThrows(IllegalArgumentException.class, () -> JsonResponses.write(this.response, 200, "ok",
            Json.createObjectBuilder().add("status", "other")));
        assertThrows(IllegalArgumentException.class, () -> JsonResponses.write(this.response, 200, "ok",
            Json.createObjectBuilder().add("error", "hidden")));
        assertEquals("", this.output.toString());
    }

    @Test
    void writesAnErrorWithItsReason() throws Exception
    {
        JsonResponses.writeError(this.response, 400, "The query is not valid");

        assertEquals(Json.createObjectBuilder().add("status", "error").add("error", "The query is not valid").build(),
            parse());
        Mockito.verify(this.response).resetBuffer();
        Mockito.verify(this.response).setStatus(400);
        Mockito.verify(this.response).setContentType("application/json");
    }

    @Test
    void leavesACommittedResponseAsItIs() throws Exception
    {
        Mockito.when(this.response.isCommitted()).thenReturn(true);

        JsonResponses.writeError(this.response, 500, "Too late");

        assertEquals("", this.output.toString());
        Mockito.verify(this.response, Mockito.never()).setStatus(Mockito.anyInt());
    }

    private JsonObject parse()
    {
        return Json.createReader(new StringReader(this.output.toString())).readObject();
    }
}

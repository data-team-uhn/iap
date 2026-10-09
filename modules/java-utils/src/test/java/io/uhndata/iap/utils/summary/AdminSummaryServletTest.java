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
package io.uhndata.iap.utils.summary;

import java.io.IOException;
import java.io.StringReader;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Unit tests for {@link AdminSummaryServlet}.
 *
 * @version $Id$
 */
@ExtendWith(SlingContextExtension.class)
class AdminSummaryServletTest
{
    private final SlingContext context = new SlingContext();

    @Test
    void writesTheFiguresInOrder() throws IOException
    {
        final MockSlingJakartaHttpServletResponse response = this.answer(request -> Json.createObjectBuilder()
            .add("total", AdminSummaryServlet.count("Archived in total", 12))
            .add("enabled", AdminSummaryServlet.state("Catching mail", true)));

        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        assertEquals("application/json", response.getContentType().split(";")[0]);
        assertEquals("{\"total\":{\"label\":\"Archived in total\",\"value\":12},"
            + "\"enabled\":{\"label\":\"Catching mail\",\"value\":true}}", response.getOutputAsString());
    }

    @Test
    void marksOnlyAnApproximateCount()
    {
        assertEquals("{\"label\":\"Workflows\",\"value\":3}",
            AdminSummaryServlet.count("Workflows", 3, false).build().toString());
        assertEquals("{\"label\":\"Workflows\",\"value\":10000,\"approximate\":true}",
            AdminSummaryServlet.count("Workflows", 10_000, true).build().toString());
    }

    @Test
    void writesAnUnknownCountWithANullValue()
    {
        assertEquals("{\"label\":\"Workflows\",\"value\":null}",
            AdminSummaryServlet.unknown("Workflows").build().toString());
    }

    @Test
    void answersAnUnavailableSummaryWithAnError() throws IOException
    {
        final MockSlingJakartaHttpServletResponse response = this.answer(request -> {
            throw new SummaryUnavailableException("The archive cannot be queried");
        });

        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.getStatus());
        assertEquals("The archive cannot be queried", read(response).getString("error"));
    }

    @Test
    void keepsTheCauseOutOfTheAnswer() throws IOException
    {
        final MockSlingJakartaHttpServletResponse response = this.answer(request -> {
            throw new SummaryUnavailableException("The archive cannot be queried",
                new IllegalStateException("the index is on fire"));
        });

        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.getStatus());
        assertFalse(response.getOutputAsString().contains("on fire"));
    }

    @Test
    void answersAnUnforeseenFailureInTheSameFormat() throws IOException
    {
        final MockSlingJakartaHttpServletResponse response = this.answer(request -> {
            throw new IllegalStateException("the index is on fire");
        });

        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.getStatus());
        assertEquals("application/json", response.getContentType().split(";")[0]);
        assertEquals("The summary cannot be read", read(response).getString("error"));
    }

    /** What a summary computing its figures with {@code figures} answers. */
    private MockSlingJakartaHttpServletResponse answer(final Figures figures) throws IOException
    {
        final AdminSummaryServlet servlet = new AdminSummaryServlet()
        {
            private static final long serialVersionUID = 1L;

            @Override
            protected JsonObjectBuilder summarize(final SlingJakartaHttpServletRequest request)
                throws SummaryUnavailableException
            {
                return figures.of(request);
            }
        };
        this.context.currentResource(this.context.create().resource("/Archive"));
        servlet.doGet(this.context.jakartaRequest(), this.context.jakartaResponse());
        return this.context.jakartaResponse();
    }

    private static JsonObject read(final MockSlingJakartaHttpServletResponse response)
    {
        return Json.createReader(new StringReader(response.getOutputAsString())).readObject();
    }

    /** A subclass's {@link AdminSummaryServlet#summarize}, as a lambda. */
    @FunctionalInterface
    private interface Figures
    {
        JsonObjectBuilder of(SlingJakartaHttpServletRequest request) throws SummaryUnavailableException;
    }
}

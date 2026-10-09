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
package io.uhndata.iap.errortracking.internal;

import java.io.IOException;
import java.io.StringReader;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityHomepage;
import io.uhndata.iap.errortracking.models.Acknowledgement;
import io.uhndata.iap.errortracking.models.LoggedError;
import io.uhndata.iap.errortracking.models.LoggedErrorsHomepage;
import io.uhndata.iap.errortracking.models.LoggedFailure;
import io.uhndata.iap.errortracking.models.LoggedProblem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Unit tests for {@link LoggedErrorsSummaryServlet}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class LoggedErrorsSummaryServletTest
{
    private static final String HOME = "/LoggedErrors";

    private final SlingContext context = new SlingContext();

    private LoggedErrorsSummaryServlet servlet;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityHomepage.class, LoggedError.class,
            LoggedFailure.class, LoggedProblem.class, LoggedErrorsHomepage.class, Acknowledgement.class);
        this.context.create().resource(HOME, "sling:resourceType", LoggedErrorsHomepage.RESOURCE_TYPE);
        this.servlet = new LoggedErrorsSummaryServlet();
    }

    @Test
    void countsNothingWhenNothingHasBeenRecorded() throws IOException
    {
        final JsonObject summary = this.summary();

        assertEquals(0, value(summary, "needingAttention"));
        assertEquals(0, value(summary, "total"));
    }

    @Test
    void separatesTheErrorsNobodyHasDealtWithFromTheRest() throws IOException
    {
        this.error("one", false);
        this.error("two", true);
        this.error("three", false);

        final JsonObject summary = this.summary();

        assertEquals(2, value(summary, "needingAttention"));
        assertEquals(3, value(summary, "total"));
    }

    @Test
    void namesBothFiguresAndMarksTheOneWorthActingOn() throws IOException
    {
        final JsonObject summary = this.summary();

        assertEquals("Needing attention", summary.getJsonObject("needingAttention").getString("label"));
        assertEquals("nonzero", summary.getJsonObject("needingAttention").getString("important"));
        assertEquals("Recorded in total", summary.getJsonObject("total").getString("label"));
        assertFalse(summary.getJsonObject("total").containsKey("important"));
    }

    @Test
    void countsExactlyRatherThanApproximately() throws IOException
    {
        this.error("one", false);

        // The children are read directly, so the count is exact
        assertFalse(this.summary().getJsonObject("total").containsKey("approximate"));
    }

    @Test
    void reportsAHomepageItCannotReadRatherThanAnsweringWithZeros() throws IOException
    {
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(), this.context.bundleContext());
        // A homepage that will not adapt, as happens when the session cannot read its properties
        request.setResource(new ResourceWrapper(this.context.resourceResolver().getResource(HOME))
        {
            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return null;
            }
        });
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.servlet.doGet(request, response);

        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.getStatus());
        assertEquals("The recorded errors cannot be read", read(response).getString("error"));
    }

    /** How many one figure counted. */
    private static int value(final JsonObject summary, final String figure)
    {
        return summary.getJsonObject(figure).getInt("value");
    }

    /** The answer the servlet gives for the recorded errors. */
    private JsonObject summary() throws IOException
    {
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(), this.context.bundleContext());
        request.setResource(this.context.resourceResolver().getResource(HOME));
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();
        this.servlet.doGet(request, response);
        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        return read(response);
    }

    private static JsonObject read(final MockSlingJakartaHttpServletResponse response)
    {
        try (var reader = Json.createReader(new StringReader(response.getOutputAsString()))) {
            return reader.readObject();
        }
    }

    /** Records one error, either dealt with or still asking for attention. */
    private Resource error(final String name, final boolean acknowledged)
    {
        final Map<String, Object> properties = new HashMap<>(Map.of(
            "sling:resourceType", LoggedFailure.RESOURCE_TYPE,
            "sling:resourceSuperType", LoggedError.RESOURCE_TYPE,
            "type", "java.lang.IllegalStateException",
            "stackTrace", "java.lang.IllegalStateException: boom",
            "occurrences", 1L,
            "lastOccurrence", Calendar.getInstance()));
        properties.put("computedTags",
            new String[] { acknowledged ? LoggedError.ACKNOWLEDGED : LoggedError.UNACKNOWLEDGED });
        return this.context.create().resource(HOME + "/" + name, properties);
    }
}

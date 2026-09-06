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
package io.uhndata.iap.statistics.internal;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.Calendar;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link StatisticsRefreshServlet}: who may ask for a refresh, and what they are told.
 *
 * @version $Id$
 * @since 0.1.0
 */
class StatisticsRefreshServletTest
{
    private static final String STATUS = "status";

    private final StatisticsRefreshServlet servlet = new StatisticsRefreshServlet();

    private MetricStore store;

    @BeforeEach
    void setUp() throws Exception
    {
        this.store = Mockito.mock(MetricStore.class);
        final Field field = StatisticsRefreshServlet.class.getDeclaredField("store");
        field.setAccessible(true);
        field.set(this.servlet, this.store);
    }

    // It answers when the work is done, not when it has been started: a caller asking for this wants to
    // know whether the numbers they are about to read are the new ones
    @Test
    void worksTheMetricsOutAndSaysWhen() throws Exception
    {
        Mockito.when(this.store.refresh()).thenReturn(Calendar.getInstance());

        final JsonObject answer = post(session(true), Mockito.mock(SlingJakartaHttpServletResponse.class));

        assertEquals("ok", answer.getString(STATUS));
        Mockito.verify(this.store).refresh();
    }

    @Test
    void refusesAnybodyWhoDoesNotLookAfterTheMetrics() throws Exception
    {
        final SlingJakartaHttpServletResponse response =
            Mockito.mock(SlingJakartaHttpServletResponse.class);

        final JsonObject answer = post(session(false), response);

        assertEquals("error", answer.getString(STATUS));
        Mockito.verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        Mockito.verify(this.store, Mockito.never()).refresh();
    }

    // Reported rather than left to look like success: the previous figures are still there, and a caller
    // who asked for new ones would otherwise read the old ones as new
    @Test
    void saysSoWhenTheMetricsCouldNotBeWorkedOut() throws Exception
    {
        Mockito.when(this.store.refresh()).thenReturn(null);
        final SlingJakartaHttpServletResponse response =
            Mockito.mock(SlingJakartaHttpServletResponse.class);

        final JsonObject answer = post(session(true), response);

        assertEquals("error", answer.getString(STATUS));
        Mockito.verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }

    private static Session session(final boolean mayWrite) throws RepositoryException
    {
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.hasPermission("/Statistics", Session.ACTION_SET_PROPERTY))
            .thenReturn(mayWrite);
        return session;
    }

    private JsonObject post(final Session session, final SlingJakartaHttpServletResponse response)
        throws IOException
    {
        final Resource resource = Mockito.mock(Resource.class);
        Mockito.when(resource.getPath()).thenReturn("/Statistics");
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        Mockito.when(resolver.adaptTo(Session.class)).thenReturn(session);
        final SlingJakartaHttpServletRequest request =
            Mockito.mock(SlingJakartaHttpServletRequest.class);
        Mockito.when(request.getResourceResolver()).thenReturn(resolver);
        Mockito.when(request.getResource()).thenReturn(resource);

        final StringWriter written = new StringWriter();
        Mockito.when(response.getWriter()).thenReturn(new PrintWriter(written));

        this.servlet.doPost(request, response);
        return Json.createReader(new StringReader(written.toString())).readObject();
    }
}

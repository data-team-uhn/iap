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
import java.util.List;
import java.util.TimeZone;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link StatisticsEndpoint}: what it serves, and who it decides may see the metrics
 * reserved for administrators.
 *
 * @version $Id$
 * @since 0.1.0
 */
class StatisticsEndpointTest
{
    private static final String SOLO = "{\"name\":\"solo\",\"label\":\"Solo\",\"value\":4.0}";

    private final StatisticsEndpoint endpoint = new StatisticsEndpoint();

    private MetricStore store;

    @BeforeEach
    void setUp() throws Exception
    {
        this.store = Mockito.mock(MetricStore.class);
        Mockito.when(this.store.read(Mockito.anyBoolean()))
            .thenReturn(new MetricStore.Stored(midnight(), List.of(SOLO)));
        final Field field = StatisticsEndpoint.class.getDeclaredField("store");
        field.setAccessible(true);
        field.set(this.endpoint, this.store);
    }

    @Test
    void servesWhatTheMetricsSay() throws Exception
    {
        final JsonObject answer = serve(session(true));

        assertEquals(1, answer.getJsonArray("metrics").size());
        assertEquals("solo", answer.getJsonArray("metrics").getJsonObject(0).getString("name"));
    }

    // The figures are worked out on a schedule, so every page showing them owes its reader this date:
    // a stale number nobody can date is the one that misleads
    @Test
    void saysWhenTheyWereWorkedOut() throws Exception
    {
        assertEquals("2026-09-06T04:00:00Z", serve(session(true)).getString("computedAt"));
    }

    @Test
    void saysSoWhenTheyNeverHaveBeen() throws Exception
    {
        Mockito.when(this.store.read(Mockito.anyBoolean()))
            .thenReturn(new MetricStore.Stored(null, List.of()));

        final JsonObject answer = serve(session(true));

        assertTrue(answer.isNull("computedAt"));
        assertTrue(answer.getJsonArray("metrics").isEmpty());
    }

    // A stored figure that somehow got mangled should cost that metric and not the whole dashboard
    @Test
    void leavesOutAStoredValueItCannotReadBack() throws Exception
    {
        Mockito.when(this.store.read(Mockito.anyBoolean()))
            .thenReturn(new MetricStore.Stored(midnight(), List.of("not json at all", SOLO)));

        assertEquals(1, serve(session(true)).getJsonArray("metrics").size());
    }

    // "Administrator" is asked of the repository - whoever may write the definitions - rather than kept
    // as a second list that could drift out of step with the permission
    @Test
    void asksTheRepositoryWhoMayCurate() throws Exception
    {
        serve(session(true));
        Mockito.verify(this.store).read(true);

        serve(session(false));
        Mockito.verify(this.store).read(false);
    }

    @Test
    void treatsARequestWithNoSessionAsAnOrdinaryReader() throws Exception
    {
        serve(null);
        Mockito.verify(this.store).read(false);
    }

    // Answering "no" shows a curator fewer metrics than they could have, which is the harmless way for
    // this to be wrong
    @Test
    void treatsAnUnanswerableQuestionAsANo() throws Exception
    {
        final Session broken = Mockito.mock(Session.class);
        Mockito.when(broken.getUserID()).thenReturn("someone");
        Mockito.when(broken.hasPermission(Mockito.anyString(), Mockito.anyString()))
            .thenThrow(new RepositoryException("cannot tell"));

        serve(broken);

        Mockito.verify(this.store).read(false);
    }

    private static Calendar midnight()
    {
        final Calendar when = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        when.clear();
        when.set(2026, Calendar.SEPTEMBER, 6, 4, 0, 0);
        return when;
    }

    private static Session session(final boolean mayWrite) throws RepositoryException
    {
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.hasPermission("/Statistics", Session.ACTION_SET_PROPERTY))
            .thenReturn(mayWrite);
        return session;
    }

    private JsonObject serve(final Session session) throws IOException
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
        final SlingJakartaHttpServletResponse response =
            Mockito.mock(SlingJakartaHttpServletResponse.class);
        Mockito.when(response.getWriter()).thenReturn(new PrintWriter(written));

        this.endpoint.doGet(request, response);
        Mockito.verify(response).setContentType("application/json");
        assertTrue(written.toString().startsWith("{"));
        return Json.createReader(new StringReader(written.toString())).readObject();
    }
}

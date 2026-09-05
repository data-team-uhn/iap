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
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.List;

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

import io.uhndata.iap.statistics.api.MetricCalculator;
import io.uhndata.iap.statistics.api.MetricValue;

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
    private final StatisticsEndpoint endpoint = new StatisticsEndpoint();

    private MetricCalculator calculator;

    @BeforeEach
    void setUp() throws Exception
    {
        this.calculator = Mockito.mock(MetricCalculator.class);
        Mockito.when(this.calculator.computeAll(Mockito.anyBoolean()))
            .thenReturn(List.of(MetricValue.of("solo", "Solo").valued(4.0, 2).build()));
        final Field field = StatisticsEndpoint.class.getDeclaredField("calculator");
        field.setAccessible(true);
        field.set(this.endpoint, this.calculator);
    }

    @Test
    void servesWhatTheMetricsSay() throws Exception
    {
        final JsonObject answer = serve(session(true));

        assertEquals(1, answer.getJsonArray("metrics").size());
        assertEquals("solo", answer.getJsonArray("metrics").getJsonObject(0).getString("name"));
    }

    // "Administrator" is asked of the repository - whoever may write the definitions - rather than kept
    // as a second list that could drift out of step with the permission
    @Test
    void asksTheRepositoryWhoMayCurate() throws Exception
    {
        serve(session(true));
        Mockito.verify(this.calculator).computeAll(true);

        serve(session(false));
        Mockito.verify(this.calculator).computeAll(false);
    }

    @Test
    void treatsARequestWithNoSessionAsAnOrdinaryReader() throws Exception
    {
        serve(null);
        Mockito.verify(this.calculator).computeAll(false);
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

        Mockito.verify(this.calculator).computeAll(false);
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
        return Json.createReader(new java.io.StringReader(written.toString())).readObject();
    }
}

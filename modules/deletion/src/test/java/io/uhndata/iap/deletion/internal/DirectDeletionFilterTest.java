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
package io.uhndata.iap.deletion.internal;

import java.io.StringReader;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.servlet.FilterChain;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.osgi.framework.BundleContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DirectDeletionFilter}.
 *
 * @version $Id$
 */
@ExtendWith(SlingContextExtension.class)
class DirectDeletionFilterTest
{
    private final SlingContext context = new SlingContext();

    private final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

    private final FilterChain chain = mock(FilterChain.class);

    @Test
    void refusesAnHttpDelete() throws Exception
    {
        final MockSlingJakartaHttpServletRequest request = this.request("DELETE", Map.of());

        filter(null).doFilter(request, this.response, this.chain);

        assertEquals(405, this.response.getStatus());
        final JsonObject body = this.body();
        assertEquals("refused", body.getString("status"));
        assertEquals("Content is deleted by its workflow: send it a delete event, as POST <path>.delete.json",
            body.getString("status.message"));
        verify(this.chain, never()).doFilter(request, this.response);
    }

    @Test
    void refusesTheSlingDeleteOperationWhereverItPoints() throws Exception
    {
        final MockSlingJakartaHttpServletRequest request =
            this.request("POST", Map.of(":operation", "delete", ":applyTo", "/Submissions/s1"));

        filter(null).doFilter(request, this.response, this.chain);

        assertEquals(405, this.response.getStatus());
        verify(this.chain, never()).doFilter(request, this.response);
    }

    @Test
    void letsOtherWritesThrough() throws Exception
    {
        final MockSlingJakartaHttpServletRequest event = this.request("POST", Map.of("patch", "{}"));
        final MockSlingJakartaHttpServletRequest move = this.request("POST", Map.of(":operation", "move"));

        filter(null).doFilter(event, this.response, this.chain);
        filter(null).doFilter(move, this.response, this.chain);

        verify(this.chain).doFilter(event, this.response);
        verify(this.chain).doFilter(move, this.response);
    }

    @Test
    void letsDirectDeletionsThroughWhereTheInstanceAllowsThem() throws Exception
    {
        final MockSlingJakartaHttpServletRequest delete = this.request("DELETE", Map.of());
        final MockSlingJakartaHttpServletRequest operation = this.request("POST", Map.of(":operation", "delete"));

        filter("allowed").doFilter(delete, this.response, this.chain);
        filter("allowed").doFilter(operation, this.response, this.chain);

        verify(this.chain).doFilter(delete, this.response);
        verify(this.chain).doFilter(operation, this.response);
    }

    @Test
    void refusesWhenTheSwitchSaysAnythingElse() throws Exception
    {
        final MockSlingJakartaHttpServletRequest request = this.request("DELETE", Map.of());

        filter("true").doFilter(request, this.response, this.chain);

        assertEquals(405, this.response.getStatus());
    }

    private static DirectDeletionFilter filter(final String switchValue)
    {
        final BundleContext bundleContext = mock(BundleContext.class);
        when(bundleContext.getProperty("iap.deletion.direct")).thenReturn(switchValue);
        return new DirectDeletionFilter(bundleContext);
    }

    private MockSlingJakartaHttpServletRequest request(final String method, final Map<String, Object> parameters)
    {
        final MockSlingJakartaHttpServletRequest request = new MockSlingJakartaHttpServletRequest(
            this.context.resourceResolver(), this.context.bundleContext());
        request.setMethod(method);
        request.setParameterMap(parameters);
        return request;
    }

    private JsonObject body()
    {
        try (var reader = Json.createReader(new StringReader(this.response.getOutputAsString()))) {
            return reader.readObject();
        }
    }
}

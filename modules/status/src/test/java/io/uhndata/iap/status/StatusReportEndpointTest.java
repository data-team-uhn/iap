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
package io.uhndata.iap.status;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.json.Json;
import jakarta.json.JsonArray;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockRequestPathInfo;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.status.api.StatusReportManager;
import io.uhndata.iap.status.spi.StatusReport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link StatusReportEndpoint}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class StatusReportEndpointTest
{
    private final SlingContext context = new SlingContext();

    private final StatusReportManager manager = Mockito.mock(StatusReportManager.class);

    private StatusReportEndpoint endpoint;

    @BeforeEach
    void setUp() throws Exception
    {
        this.endpoint = new StatusReportEndpoint();
        final Field reference = StatusReportEndpoint.class.getDeclaredField("manager");
        reference.setAccessible(true);
        reference.set(this.endpoint, this.manager);
    }

    @Test
    void serializesReportsAsJson() throws Exception
    {
        Mockito.when(this.manager.getReports(true, StatusReport.Status.INFO, Set.of()))
            .thenReturn(List.of(
                new StatusReport("Uptime", StatusReport.Status.INFO, "All day long"),
                new StatusReport("Problems", StatusReport.Status.WARNING, "Something is off")));

        final MockSlingJakartaHttpServletResponse response = get(null, Map.of());

        assertEquals("application/json;charset=UTF-8", response.getContentType());
        final JsonArray results =
            Json.createReader(new StringReader(response.getOutputAsString())).readArray();
        assertEquals(2, results.size());
        assertEquals("Uptime", results.getJsonObject(0).getString("name"));
        assertEquals("WARNING", results.getJsonObject(1).getString("status"));
    }

    @Test
    void serializesReportsAsText() throws Exception
    {
        Mockito.when(this.manager.getReports(true, StatusReport.Status.INFO, Set.of()))
            .thenReturn(List.of(
                new StatusReport("Uptime", StatusReport.Status.INFO, "All day long"),
                new StatusReport("Silence", StatusReport.Status.INFO, null),
                new StatusReport("Problems", StatusReport.Status.WARNING, "Something is off")));

        final MockSlingJakartaHttpServletResponse response = get("txt", Map.of());

        assertEquals("text/plain;charset=UTF-8", response.getContentType());
        // Texts are joined by blank lines, and missing texts don't print as "null"
        assertEquals("All day long\n\n\n\nSomething is off", response.getOutputAsString());
    }

    @Test
    void honorsTargetStatusAndTags() throws Exception
    {
        Mockito.when(this.manager.getReports(true, StatusReport.Status.WARNING, Set.of("problems")))
            .thenReturn(List.of());

        final MockSlingJakartaHttpServletResponse response =
            get(null, Map.of("targetStatus", "WARNING", "tags", "problems"));

        assertEquals(200, response.getStatus());
        assertEquals("[]", response.getOutputAsString());
        Mockito.verify(this.manager).getReports(true, StatusReport.Status.WARNING, Set.of("problems"));
    }

    @Test
    void rejectsInvalidTargetStatus() throws Exception
    {
        final MockSlingJakartaHttpServletResponse response = get(null, Map.of("targetStatus", "LOUDER"));

        assertEquals(400, response.getStatus());
        assertTrue(response.getOutputAsString().contains("Invalid targetStatus: LOUDER"));
        Mockito.verifyNoInteractions(this.manager);
    }

    @Test
    void blankTargetStatusMeansInfo() throws Exception
    {
        Mockito.when(this.manager.getReports(true, StatusReport.Status.INFO, Set.of())).thenReturn(List.of());

        final MockSlingJakartaHttpServletResponse response = get(null, Map.of("targetStatus", " "));

        assertEquals(200, response.getStatus());
        Mockito.verify(this.manager).getReports(true, StatusReport.Status.INFO, Set.of());
    }

    @Test
    void onlyTheAdministratorIsPrivileged() throws Exception
    {
        Mockito.when(this.manager.getReports(Mockito.anyBoolean(), Mockito.any(), Mockito.any()))
            .thenReturn(List.of());

        final MockSlingJakartaHttpServletRequest request = request(null, Map.of());
        request.setRemoteUser("admin");
        this.endpoint.service(request, new MockSlingJakartaHttpServletResponse());
        Mockito.verify(this.manager).getReports(false, StatusReport.Status.INFO, Set.of());

        final MockSlingJakartaHttpServletRequest other = request(null, Map.of());
        other.setRemoteUser("visitor");
        this.endpoint.service(other, new MockSlingJakartaHttpServletResponse());
        Mockito.verify(this.manager).getReports(true, StatusReport.Status.INFO, Set.of());
    }

    private MockSlingJakartaHttpServletRequest request(final String extension, final Map<String, Object> parameters)
    {
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(), this.context.bundleContext());
        ((MockRequestPathInfo) request.getRequestPathInfo()).setExtension(extension);
        request.setParameterMap(parameters);
        return request;
    }

    private MockSlingJakartaHttpServletResponse get(final String extension, final Map<String, Object> parameters)
        throws Exception
    {
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();
        this.endpoint.service(request(extension, parameters), response);
        return response;
    }
}

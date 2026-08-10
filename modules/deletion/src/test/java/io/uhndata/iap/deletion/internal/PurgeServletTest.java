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
import java.lang.reflect.Field;
import java.util.List;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.deletion.api.DeletionException;
import io.uhndata.iap.deletion.api.DeletionImpact;
import io.uhndata.iap.deletion.api.DeletionResult;
import io.uhndata.iap.deletion.api.DeletionService;
import io.uhndata.iap.deletion.api.Veto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link PurgeServlet}.
 *
 * @version $Id$
 */
@ExtendWith(SlingContextExtension.class)
class PurgeServletTest
{
    private final SlingContext context = new SlingContext();

    private PurgeServlet servlet;

    private DeletionService deletionService;

    private MockSlingJakartaHttpServletRequest request;

    private MockSlingJakartaHttpServletResponse response;

    @BeforeEach
    void setup() throws Exception
    {
        this.deletionService = mock(DeletionService.class);
        this.servlet = new PurgeServlet();
        final Field service = PurgeServlet.class.getDeclaredField("deletionService");
        service.setAccessible(true);
        service.set(this.servlet, this.deletionService);
        this.context.create().resource("/Archive/123");
        this.request = new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(),
            this.context.bundleContext());
        this.request.setResource(this.context.resourceResolver().getResource("/Archive/123"));
        this.response = new MockSlingJakartaHttpServletResponse();
    }

    private JsonObject body()
    {
        try (var reader = Json.createReader(new StringReader(this.response.getOutputAsString()))) {
            return reader.readObject();
        }
    }

    @Test
    void purgedEntriesAreReported() throws Exception
    {
        when(this.deletionService.purge(any())).thenReturn(new DeletionResult(DeletionResult.Status.DELETED,
            null, new DeletionImpact(List.of("/Archive/123"), List.of(), List.of(), List.of(), 0, "")));
        this.servlet.doDelete(this.request, this.response);
        assertEquals(200, this.response.getStatus());
        assertEquals("deleted", this.body().getString("status"));
    }

    @Test
    void vetoedPurgesGet409() throws Exception
    {
        when(this.deletionService.purge(any())).thenReturn(new DeletionResult(DeletionResult.Status.VETOED,
            null, new DeletionImpact(List.of("/Archive/123"), List.of(),
                List.of(new Veto("undeletable", "/Archive/123/0/x", "This resource is protected from deletion")),
                List.of(), 0, "")));
        this.servlet.doDelete(this.request, this.response);
        assertEquals(409, this.response.getStatus());
        final JsonObject body = this.body();
        assertEquals("vetoed", body.getString("status"));
        assertEquals(1, body.getJsonArray("vetoes").size());
    }

    @Test
    void invalidTargetsGet400() throws Exception
    {
        when(this.deletionService.purge(any()))
            .thenThrow(new IllegalArgumentException("Not an archive entry"));
        this.servlet.doDelete(this.request, this.response);
        assertEquals(400, this.response.getStatus());
    }

    @Test
    void failuresGet500() throws Exception
    {
        when(this.deletionService.purge(any()))
            .thenThrow(new DeletionException("Failed to purge", null));
        this.servlet.doDelete(this.request, this.response);
        assertEquals(500, this.response.getStatus());
    }
}

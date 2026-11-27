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
import java.lang.reflect.Constructor;
import java.util.List;

import javax.jcr.Node;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.deletion.api.DeletionException;
import io.uhndata.iap.deletion.api.DeletionImpact;
import io.uhndata.iap.deletion.api.DeletionService;
import io.uhndata.iap.deletion.api.ReferrerGroup;
import io.uhndata.iap.deletion.api.Veto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DeletionImpactServlet}.
 *
 * @version $Id$
 */
@ExtendWith(SlingContextExtension.class)
class DeletionImpactServletTest
{
    private static final DeletionImpact CLEAN_IMPACT =
        new DeletionImpact(List.of("/content/x"), List.of(), List.of(), List.of(), 0, "");

    private final SlingContext context = new SlingContext();

    private final DeletionService deletionService = mock(DeletionService.class);

    private final DeletionImpactServlet servlet = new DeletionImpactServlet(this.deletionService);

    private MockSlingJakartaHttpServletRequest request;

    private final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

    @BeforeEach
    void setup()
    {
        this.context.create().resource("/content/x");
        this.request = new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(),
            this.context.bundleContext());
        this.request.setResource(this.context.resourceResolver().getResource("/content/x"));
    }

    // The mock repository gives no resource a node, so a test that needs one adapts it here
    private void adaptable()
    {
        this.context.registerAdapter(Resource.class, Node.class, mock(Node.class));
        this.request.setResource(this.context.resourceResolver().getResource("/content/x"));
    }

    private JsonObject body()
    {
        try (var reader = Json.createReader(new StringReader(this.response.getOutputAsString()))) {
            return reader.readObject();
        }
    }

    @Test
    void nonRepositoryResourcesGet404() throws Exception
    {
        this.servlet.doGet(this.request, this.response);
        assertEquals(404, this.response.getStatus());
        assertEquals("missing", this.body().getString("status"));
    }

    @Test
    void describesWhatTheDeleteEventWouldDoAndChangesNothing() throws Exception
    {
        this.adaptable();
        when(this.deletionService.analyze(any(), any())).thenReturn(CLEAN_IMPACT);
        this.servlet.doGet(this.request, this.response);
        assertEquals(200, this.response.getStatus());
        final JsonObject body = this.body();
        assertEquals("dryRun", body.getString("status"));
        assertTrue(body.getBoolean("executable"));
        assertFalse(body.containsKey("vetoes"));
        assertFalse(body.containsKey("referrers"));
        assertFalse(body.containsKey("status.message"));
        // As the event deletes: to the archive, refusing while anything refers to it
        verify(this.deletionService).analyze(any(), argThat(options -> !options.isRecursive()
            && !options.isPermanent()));
        verifyNoMoreInteractions(this.deletionService);
    }

    @Test
    void reportsWhatBlocksTheDeletion() throws Exception
    {
        this.adaptable();
        final DeletionImpact impact = new DeletionImpact(List.of("/content/x"), List.of(),
            List.of(new Veto("undeletable", "/content/x", "This resource is protected from deletion")),
            List.of(), 3, "This item is referenced by 3 other items you cannot see.");
        when(this.deletionService.analyze(any(), any())).thenReturn(impact);
        this.servlet.doGet(this.request, this.response);
        assertEquals(200, this.response.getStatus());
        final JsonObject body = this.body();
        assertFalse(body.getBoolean("executable"));
        assertEquals(1, body.getJsonArray("vetoes").size());
        assertEquals(0, body.getJsonArray("referrers").size());
        assertEquals(3, body.getInt("inaccessibleReferrers"));
        assertTrue(body.getString("status.message").contains("you cannot see"));
    }

    @Test
    void reportsWhatRefersToTheResource() throws Exception
    {
        this.adaptable();
        final DeletionImpact impact = new DeletionImpact(List.of("/content/x"), List.of(), List.of(),
            List.of(new ReferrerGroup("sub:Submission", "submission", List.of("S-1"), 3)), 2,
            "This item is referenced by 3 submissions (S-1, …) and 2 other items you cannot see.");
        when(this.deletionService.analyze(any(), any())).thenReturn(impact);
        this.servlet.doGet(this.request, this.response);
        assertEquals(200, this.response.getStatus());
        final JsonObject body = this.body();
        assertFalse(body.getBoolean("executable"));
        assertTrue(body.getString("status.message").startsWith("This item is referenced by"));
        final JsonObject group = body.getJsonArray("referrers").getJsonObject(0);
        assertEquals("sub:Submission", group.getString("type"));
        assertEquals("submission", group.getString("label"));
        assertEquals(3, group.getInt("count"));
        assertEquals("S-1", group.getJsonArray("names").getString(0));
        assertEquals(2, body.getInt("inaccessibleReferrers"));
    }

    @Test
    void invalidRequestsGet400() throws Exception
    {
        this.adaptable();
        when(this.deletionService.analyze(any(), any())).thenThrow(new IllegalArgumentException("Not deletable"));
        this.servlet.doGet(this.request, this.response);
        assertEquals(400, this.response.getStatus());
        assertEquals("Not deletable", this.body().getString("status.message"));
    }

    @Test
    void failuresGet500() throws Exception
    {
        this.adaptable();
        when(this.deletionService.analyze(any(), any()))
            .thenThrow(new DeletionException("Failed to analyze /content/x", null));
        this.servlet.doGet(this.request, this.response);
        assertEquals(500, this.response.getStatus());
        assertEquals("failed", this.body().getString("status"));
    }

    @Test
    void responsesUtilityIsNotInstantiatable() throws Exception
    {
        final Constructor<DeletionResponses> constructor = DeletionResponses.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertNotNull(constructor.newInstance());
    }
}

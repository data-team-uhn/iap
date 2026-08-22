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
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

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
import io.uhndata.iap.deletion.api.DeletionService;
import io.uhndata.iap.deletion.api.RestoreConflict;
import io.uhndata.iap.deletion.api.Veto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ArchiveEntryServlet}.
 *
 * @version $Id$
 */
@ExtendWith(SlingContextExtension.class)
class ArchiveEntryServletTest
{
    private static final String ENTRY = "/Archive/ab/cd/ef/one";

    private final SlingContext context = new SlingContext();

    private ArchiveEntryServlet servlet;

    private DeletionService deletionService;

    private MockSlingJakartaHttpServletRequest request;

    private MockSlingJakartaHttpServletResponse response;

    @BeforeEach
    void setup() throws Exception
    {
        this.deletionService = mock(DeletionService.class);
        this.servlet = new ArchiveEntryServlet();
        final Field service = ArchiveEntryServlet.class.getDeclaredField("deletionService");
        service.setAccessible(true);
        service.set(this.servlet, this.deletionService);

        final Calendar created = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        created.setTimeInMillis(0);
        this.context.create().resource(ENTRY,
            Map.of("deletedBy", "alice", "requestedPath", "/content/one", "jcr:created", created));
        this.context.create().resource(ENTRY + "/0", Map.of("originalPath", "/content/one"));

        this.request = new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(),
            this.context.bundleContext());
        this.request.setResource(this.context.resourceResolver().getResource(ENTRY));
        this.response = new MockSlingJakartaHttpServletResponse();
    }

    private JsonObject body()
    {
        try (var reader = Json.createReader(new StringReader(this.response.getOutputAsString()))) {
            return reader.readObject();
        }
    }

    @Test
    void describesTheEntryAndSaysBothActionsWouldWork() throws Exception
    {
        when(this.deletionService.checkRestore(any())).thenReturn(List.of());
        when(this.deletionService.checkPurge(any())).thenReturn(List.of());

        this.servlet.doGet(this.request, this.response);

        assertEquals(200, this.response.getStatus());
        final JsonObject body = this.body();
        assertEquals(ENTRY, body.getString("path"));
        assertEquals("alice", body.getString("deletedBy"));
        assertEquals("/content/one", body.getString("requestedPath"));
        assertEquals(1, body.getInt("itemCount"));
        assertTrue(body.getBoolean("restorable"));
        assertTrue(body.getBoolean("purgeable"));
        assertTrue(body.getJsonArray("restoreConflicts").isEmpty());
        assertTrue(body.getJsonArray("purgeVetoes").isEmpty());
    }

    @Test
    void namesWhatWouldBlockARestore() throws Exception
    {
        when(this.deletionService.checkRestore(any()))
            .thenReturn(List.of(new RestoreConflict("/content/one", RestoreConflict.Reason.OCCUPIED)));
        when(this.deletionService.checkPurge(any())).thenReturn(List.of());

        this.servlet.doGet(this.request, this.response);

        final JsonObject body = this.body();
        assertFalse(body.getBoolean("restorable"));
        final JsonObject conflict = body.getJsonArray("restoreConflicts").getJsonObject(0);
        assertEquals("/content/one", conflict.getString("originalPath"));
        assertEquals("OCCUPIED", conflict.getString("reason"));
        // The two actions are judged independently: one being blocked says nothing about the other
        assertTrue(body.getBoolean("purgeable"));
    }

    @Test
    void namesTheGuardsThatWouldBlockAPurge() throws Exception
    {
        when(this.deletionService.checkRestore(any())).thenReturn(List.of());
        when(this.deletionService.checkPurge(any()))
            .thenReturn(List.of(new Veto("RetentionVeto", ENTRY, "Archived less than 30 days ago")));

        this.servlet.doGet(this.request, this.response);

        final JsonObject body = this.body();
        assertFalse(body.getBoolean("purgeable"));
        assertEquals("Archived less than 30 days ago",
            body.getJsonArray("purgeVetoes").getJsonObject(0).getString("reason"));
        assertTrue(body.getBoolean("restorable"));
    }

    @Test
    void somethingThatIsNotAnEntryIsRefused() throws Exception
    {
        when(this.deletionService.checkRestore(any()))
            .thenThrow(new IllegalArgumentException("Not an archive entry"));

        this.servlet.doGet(this.request, this.response);

        assertEquals(400, this.response.getStatus());
        assertEquals("invalid", this.body().getString("status"));
    }

    @Test
    void aRepositoryFailureIsReportedWithoutLeakingIt() throws Exception
    {
        when(this.deletionService.checkRestore(any()))
            .thenThrow(new DeletionException("the index is on fire", null));

        this.servlet.doGet(this.request, this.response);

        assertEquals(500, this.response.getStatus());
        final JsonObject body = this.body();
        assertEquals("failed", body.getString("status"));
        assertFalse(body.getString("status.message").contains("on fire"));
    }
}

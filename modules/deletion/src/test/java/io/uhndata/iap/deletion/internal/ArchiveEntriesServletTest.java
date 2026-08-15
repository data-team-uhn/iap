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
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Workspace;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.NodeTypeDefinitionScanner;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ArchiveEntriesServlet}.
 *
 * @version $Id$
 */
@ExtendWith(SlingContextExtension.class)
class ArchiveEntriesServletTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private ArchiveEntriesServlet servlet;

    private Session session;

    private MockSlingJakartaHttpServletResponse response;

    @BeforeEach
    void setup() throws Exception
    {
        this.servlet = new ArchiveEntriesServlet();
        this.session = this.context.resourceResolver().adaptTo(Session.class);
        NodeTypeDefinitionScanner.get().register(this.session, List.of("SLING-INF/nodetypes/deletion.cnd"),
            ResourceResolverType.JCR_OAK.getNodeTypeMode());
        this.session.getRootNode().addNode("Archive", "iap:Archive");
        this.session.save();
        this.response = new MockSlingJakartaHttpServletResponse();
    }

    private void entry(final String name, final String user, final String path) throws Exception
    {
        final Node bucket = this.session.nodeExists("/Archive/ab")
            ? this.session.getNode("/Archive/ab")
            : this.session.getNode("/Archive").addNode("ab", "iap:Archive");
        final Node entry = bucket.addNode(name, "iap:ArchiveEntry");
        entry.setProperty("deletedBy", user);
        entry.setProperty("requestedPath", path);
        entry.addNode("item0", "iap:ArchivedItem").setProperty("originalPath", path);
        this.session.save();
    }

    private MockSlingJakartaHttpServletRequest request(final ResourceResolver resolver)
    {
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(resolver, this.context.bundleContext());
        request.setResource(this.context.resourceResolver().getResource("/Archive"));
        return request;
    }

    private MockSlingJakartaHttpServletRequest request()
    {
        return this.request(this.context.resourceResolver());
    }

    private JsonObject body()
    {
        try (var reader = Json.createReader(new StringReader(this.response.getOutputAsString()))) {
            return reader.readObject();
        }
    }

    @Test
    void listsTheEntriesWithWhatTheTableShows() throws Exception
    {
        this.entry("one", "alice", "/content/one");
        this.servlet.doGet(this.request(), this.response);

        assertEquals(200, this.response.getStatus());
        final JsonObject body = this.body();
        assertEquals(1, body.getInt("totalrows"));
        assertEquals(1, body.getInt("returnedrows"));
        final JsonObject row = body.getJsonArray("rows").getJsonObject(0);
        assertEquals("/Archive/ab/one", row.getString("path"));
        assertEquals("alice", row.getString("deletedBy"));
        assertEquals("/content/one", row.getString("requestedPath"));
        assertEquals(1, row.getInt("itemCount"));
    }

    @Test
    void anEmptyArchiveIsAnEmptyListRatherThanAnError() throws Exception
    {
        this.servlet.doGet(this.request(), this.response);
        assertEquals(200, this.response.getStatus());
        assertTrue(this.body().getJsonArray("rows").isEmpty());
        assertEquals(0, this.body().getInt("totalrows"));
    }

    @Test
    void theDefaultSortIsNewestFirstAndIsEchoedBack() throws Exception
    {
        this.entry("one", "alice", "/content/one");
        this.servlet.doGet(this.request(), this.response);
        assertEquals("jcr:created", this.body().getString("sortBy"));
        assertTrue(this.body().getBoolean("descending"));
    }

    @Test
    void anUnknownSortColumnIsReportedAsTheOneActuallyApplied() throws Exception
    {
        // The request is answered rather than refused, so the client is told what it really got
        this.entry("one", "alice", "/content/one");
        final MockSlingJakartaHttpServletRequest request = this.request();
        request.setParameterMap(Map.of("sortBy", "somethingElse"));
        this.servlet.doGet(request, this.response);
        assertEquals(200, this.response.getStatus());
        assertEquals("jcr:created", this.body().getString("sortBy"));
    }

    @Test
    void sortingAndDirectionAreHonoured() throws Exception
    {
        this.entry("one", "carol", "/content/one");
        this.entry("two", "alice", "/content/two");
        final MockSlingJakartaHttpServletRequest request = this.request();
        request.setParameterMap(Map.of("sortBy", "deletedBy", "descending", "false"));
        this.servlet.doGet(request, this.response);

        final JsonObject body = this.body();
        assertEquals("deletedBy", body.getString("sortBy"));
        assertFalse(body.getBoolean("descending"));
        assertEquals("alice", body.getJsonArray("rows").getJsonObject(0).getString("deletedBy"));
    }

    @Test
    void aPageIsCutOutWhileTheTotalStillCountsEverything() throws Exception
    {
        this.entry("a", "u", "/content/a");
        this.entry("b", "u", "/content/b");
        this.entry("c", "u", "/content/c");
        final MockSlingJakartaHttpServletRequest request = this.request();
        request.setParameterMap(Map.of("offset", "1", "limit", "1", "sortBy", "requestedPath",
            "descending", "false"));
        this.servlet.doGet(request, this.response);

        final JsonObject body = this.body();
        assertEquals(1, body.getJsonArray("rows").size());
        assertEquals("/content/b", body.getJsonArray("rows").getJsonObject(0).getString("requestedPath"));
        assertEquals(3, body.getInt("totalrows"));
        assertEquals(1, body.getInt("offset"));
        assertEquals(1, body.getInt("limit"));
    }

    @Test
    void aLimitOfZeroCountsWithoutListing() throws Exception
    {
        this.entry("one", "alice", "/content/one");
        final MockSlingJakartaHttpServletRequest request = this.request();
        request.setParameterMap(Map.of("limit", "0"));
        this.servlet.doGet(request, this.response);

        assertTrue(this.body().getJsonArray("rows").isEmpty());
        assertEquals(1, this.body().getInt("totalrows"));
    }

    @Test
    void nonsensicalPagingFallsBackToTheDefaults() throws Exception
    {
        this.entry("one", "alice", "/content/one");
        final MockSlingJakartaHttpServletRequest request = this.request();
        request.setParameterMap(Map.of("offset", "not a number", "limit", "neither"));
        this.servlet.doGet(request, this.response);

        assertEquals(200, this.response.getStatus());
        assertEquals(0, this.body().getInt("offset"));
        assertEquals(25, this.body().getInt("limit"));
    }

    @Test
    void anOutsizedLimitIsCappedRatherThanObeyed() throws Exception
    {
        final MockSlingJakartaHttpServletRequest request = this.request();
        request.setParameterMap(Map.of("limit", "100000"));
        this.servlet.doGet(request, this.response);
        assertEquals(200, this.body().getInt("limit"));
    }

    @Test
    void aNegativeOffsetIsTreatedAsTheStart() throws Exception
    {
        final MockSlingJakartaHttpServletRequest request = this.request();
        request.setParameterMap(Map.of("offset", "-5"));
        this.servlet.doGet(request, this.response);
        assertEquals(0, this.body().getInt("offset"));
    }

    @Test
    void theFilterNarrowsTheListing() throws Exception
    {
        this.entry("one", "alice", "/content/one");
        this.entry("two", "bob", "/content/two");
        final MockSlingJakartaHttpServletRequest request = this.request();
        request.setParameterMap(Map.of("filter", "bob"));
        this.servlet.doGet(request, this.response);

        final JsonObject body = this.body();
        assertEquals(1, body.getInt("totalrows"));
        assertEquals("bob", body.getJsonArray("rows").getJsonObject(0).getString("deletedBy"));
    }

    @Test
    void anEntryRemovedBetweenQueryAndReadIsSkipped() throws Exception
    {
        this.entry("one", "alice", "/content/one");
        this.entry("two", "bob", "/content/two");
        // A concurrent restore or purge takes an entry away after the query named it
        final ResourceResolver hidingOne = new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public Resource getResource(final String path)
            {
                return "/Archive/ab/one".equals(path) ? null : super.getResource(path);
            }
        };
        this.servlet.doGet(this.request(hidingOne), this.response);

        final JsonObject body = this.body();
        // Counted by the query, but not listed, and the two numbers say so rather than pretending
        assertEquals(2, body.getInt("totalrows"));
        assertEquals(1, body.getInt("returnedrows"));
        assertEquals(1, body.getJsonArray("rows").size());
    }

    @Test
    void aResolverWithNoRepositoryBehindItIsReportedRatherThanThrowing() throws Exception
    {
        final ResourceResolver sessionless = new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return Session.class.equals(type) ? null : super.adaptTo(type);
            }
        };
        this.servlet.doGet(this.request(sessionless), this.response);

        assertEquals(500, this.response.getStatus());
        assertEquals("failed", this.body().getString("status"));
    }

    @Test
    void aFailingQueryIsReportedWithoutLeakingTheRepositoryError() throws Exception
    {
        // Session.getWorkspace() declares no checked exception; the query manager is the first thing
        // on this path that can fail, so that is where the repository is made to break.
        final Workspace workspace = mock(Workspace.class);
        when(workspace.getQueryManager()).thenThrow(new RepositoryException("the index is on fire"));
        final Session broken = mock(Session.class);
        when(broken.getWorkspace()).thenReturn(workspace);
        final ResourceResolver failing = new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T adaptTo(final Class<T> type)
            {
                return Session.class.equals(type) ? (T) broken : super.adaptTo(type);
            }
        };
        this.servlet.doGet(this.request(failing), this.response);

        assertEquals(500, this.response.getStatus());
        final JsonObject body = this.body();
        assertEquals("failed", body.getString("status"));
        assertFalse(body.getString("status.message").contains("on fire"));
    }
}

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
import java.time.Duration;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Workspace;

import jakarta.json.Json;
import jakarta.json.JsonObject;

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
 * Tests for {@link ArchiveSummaryServlet}.
 *
 * <p>
 * The windows are exercised by moving the clock rather than the data: {@code jcr:created} is autocreated and
 * protected, so an entry cannot be backdated through the JCR API, but asking what the counts look like a week later
 * tests the same boundary from the other side.
 * </p>
 *
 * @version $Id$
 */
@ExtendWith(SlingContextExtension.class)
class ArchiveSummaryServletTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private Session session;

    private MockSlingJakartaHttpServletResponse response;

    @BeforeEach
    void setup() throws Exception
    {
        this.session = this.context.resourceResolver().adaptTo(Session.class);
        NodeTypeDefinitionScanner.get().register(this.session, List.of("SLING-INF/nodetypes/deletion.cnd"),
            ResourceResolverType.JCR_OAK.getNodeTypeMode());
        this.session.getRootNode().addNode("Archive", "iap:Archive");
        this.session.save();
        this.response = new MockSlingJakartaHttpServletResponse();
    }

    private void entry(final String name) throws Exception
    {
        final Node bucket = this.session.nodeExists("/Archive/ab")
            ? this.session.getNode("/Archive/ab")
            : this.session.getNode("/Archive").addNode("ab", "iap:Archive");
        final Node entry = bucket.addNode(name, "iap:ArchiveEntry");
        entry.setProperty("deletedBy", "alice");
        entry.setProperty("requestedPath", "/content/" + name);
        this.session.save();
    }

    private MockSlingJakartaHttpServletRequest request(final ResourceResolver resolver)
    {
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(resolver, this.context.bundleContext());
        request.setResource(this.context.resourceResolver().getResource("/Archive"));
        return request;
    }

    private JsonObject answerAt(final long now) throws Exception
    {
        this.response = new MockSlingJakartaHttpServletResponse();
        new ArchiveSummaryServlet(() -> now).doGet(this.request(this.context.resourceResolver()), this.response);
        try (var reader = Json.createReader(new StringReader(this.response.getOutputAsString()))) {
            return reader.readObject();
        }
    }

    @Test
    void anEmptyArchiveCountsZeroEverywhere() throws Exception
    {
        final JsonObject body = this.answerAt(System.currentTimeMillis());
        assertEquals(200, this.response.getStatus());
        assertEquals(0, body.getInt("last24Hours"));
        assertEquals(0, body.getInt("lastWeek"));
        assertEquals(0, body.getInt("total"));
        assertFalse(body.getBoolean("approximate"));
    }

    @Test
    void afreshlyArchivedEntryCountsInEveryWindow() throws Exception
    {
        this.entry("one");
        this.entry("two");
        final JsonObject body = this.answerAt(System.currentTimeMillis());
        assertEquals(2, body.getInt("last24Hours"));
        assertEquals(2, body.getInt("lastWeek"));
        assertEquals(2, body.getInt("total"));
    }

    @Test
    void aDayLaterTheEntryHasLeftTheDayWindowButNotTheWeek() throws Exception
    {
        this.entry("one");
        final JsonObject body = this.answerAt(System.currentTimeMillis() + Duration.ofDays(2).toMillis());
        assertEquals(0, body.getInt("last24Hours"));
        assertEquals(1, body.getInt("lastWeek"));
        assertEquals(1, body.getInt("total"));
    }

    @Test
    void aMonthLaterOnlyTheTotalStillCountsIt() throws Exception
    {
        this.entry("one");
        final JsonObject body = this.answerAt(System.currentTimeMillis() + Duration.ofDays(30).toMillis());
        assertEquals(0, body.getInt("last24Hours"));
        assertEquals(0, body.getInt("lastWeek"));
        assertEquals(1, body.getInt("total"));
    }

    @Test
    void countsThatStoppedAtTheBoundAreReportedAsLowerBounds() throws Exception
    {
        this.entry("one");
        this.entry("two");
        this.entry("three");
        this.response = new MockSlingJakartaHttpServletResponse();
        new ArchiveSummaryServlet(System::currentTimeMillis, 2)
            .doGet(this.request(this.context.resourceResolver()), this.response);

        try (var reader = Json.createReader(new StringReader(this.response.getOutputAsString()))) {
            final JsonObject body = reader.readObject();
            // Two of the three were counted, and the answer says so rather than claiming there are two
            assertEquals(2, body.getInt("total"));
            assertTrue(body.getBoolean("approximate"));
        }
    }

    @Test
    void theDefaultConstructorReadsTheRealClock() throws Exception
    {
        // The OSGi constructor is the one production uses, so it has to work as well as the test seam
        this.entry("one");
        new ArchiveSummaryServlet().doGet(this.request(this.context.resourceResolver()), this.response);
        try (var reader = Json.createReader(new StringReader(this.response.getOutputAsString()))) {
            assertEquals(1, reader.readObject().getInt("last24Hours"));
        }
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
        new ArchiveSummaryServlet().doGet(this.request(sessionless), this.response);

        assertEquals(500, this.response.getStatus());
        try (var reader = Json.createReader(new StringReader(this.response.getOutputAsString()))) {
            assertEquals("failed", reader.readObject().getString("status"));
        }
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
        new ArchiveSummaryServlet().doGet(this.request(failing), this.response);

        assertEquals(500, this.response.getStatus());
        try (var reader = Json.createReader(new StringReader(this.response.getOutputAsString()))) {
            final JsonObject body = reader.readObject();
            assertEquals("failed", body.getString("status"));
            assertFalse(body.getString("status.message").contains("on fire"));
        }
    }
}

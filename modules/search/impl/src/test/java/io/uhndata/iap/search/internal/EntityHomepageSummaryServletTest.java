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
package io.uhndata.iap.search.internal;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Workspace;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.QueryResult;
import javax.jcr.query.Row;
import javax.jcr.query.RowIterator;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.utils.PaginatedJsonResponse;

/**
 * Unit tests for {@link EntityHomepageSummaryServlet}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class EntityHomepageSummaryServletTest
{
    private static final String WORKFLOWS = "/Workflows";

    private static final String SYSTEM_WORKFLOWS = "/SystemWorkflows";

    private static final String WORKFLOW_TYPE = "wf:WorkflowDefinition";

    private EntityHomepageSummaryServlet servlet;

    private SlingJakartaHttpServletRequest request;

    private SlingJakartaHttpServletResponse response;

    private ResourceResolver resolver;

    private StringWriter output;

    private Session session;

    private QueryManager queryManager;

    /** Every homepage the repository holds, keyed by path, in the order the homepage query answers with. */
    private final Map<String, Resource> homepages = new LinkedHashMap<>();

    /** How many entities each homepage's count query finds, keyed by the path it is scoped to. */
    private final Map<String, Integer> entities = new HashMap<>();

    @BeforeEach
    public void setup() throws Exception
    {
        this.servlet = new EntityHomepageSummaryServlet();
        this.request = Mockito.mock(SlingJakartaHttpServletRequest.class);
        this.response = Mockito.mock(SlingJakartaHttpServletResponse.class);
        this.resolver = Mockito.mock(ResourceResolver.class);
        this.output = new StringWriter();
        this.session = Mockito.mock(Session.class);
        this.queryManager = Mockito.mock(QueryManager.class);
        final Workspace workspace = Mockito.mock(Workspace.class);
        this.homepages.clear();
        this.entities.clear();

        Mockito.when(this.request.getResourceResolver()).thenReturn(this.resolver);
        Mockito.when(this.response.getWriter()).thenReturn(new PrintWriter(this.output));
        Mockito.when(this.resolver.adaptTo(Session.class)).thenReturn(this.session);
        Mockito.when(this.session.getWorkspace()).thenReturn(workspace);
        Mockito.when(workspace.getQueryManager()).thenReturn(this.queryManager);
        // The homepage query answers with every mocked homepage; a count query with as many rows as the test gave
        // the homepage it is scoped to.
        Mockito.when(this.resolver.findResources(Mockito.anyString(), Mockito.anyString()))
            .thenAnswer(invocation -> new ArrayList<>(this.homepages.values()).iterator());
        Mockito.when(this.queryManager.createQuery(Mockito.anyString(), Mockito.anyString()))
            .thenAnswer(invocation -> countQuery(invocation.getArgument(0, String.class)));
    }

    @Test
    public void countsTheQueriedHomepageAndItsPeers() throws Exception
    {
        homepage(WORKFLOWS, WORKFLOW_TYPE, "Workflows", 12);
        homepage(SYSTEM_WORKFLOWS, WORKFLOW_TYPE, "System workflows", 8);
        addressing(WORKFLOWS);

        this.servlet.doGet(this.request, this.response);

        final JsonObject summary = responseJson();
        Assertions.assertEquals(List.of(WORKFLOWS, SYSTEM_WORKFLOWS), new ArrayList<>(summary.keySet()));
        final JsonObject workflows = summary.getJsonObject(WORKFLOWS);
        Assertions.assertEquals("Workflows", workflows.getString("label"));
        Assertions.assertEquals(12, workflows.getJsonNumber("value").longValue());
        Assertions.assertEquals(WORKFLOWS, workflows.getString("path"));
        Assertions.assertFalse(workflows.containsKey("approximate"));
        Assertions.assertEquals(8, summary.getJsonObject(SYSTEM_WORKFLOWS).getJsonNumber("value").longValue());
    }

    @Test
    public void countsOnlyTheHomepagesHoldingTheSameKindOfEntity() throws Exception
    {
        homepage(WORKFLOWS, WORKFLOW_TYPE, "Workflows", 12);
        homepage("/Submissions", "sub:Submission", "Submissions", 300);
        addressing(WORKFLOWS);

        this.servlet.doGet(this.request, this.response);

        Assertions.assertEquals(List.of(WORKFLOWS), new ArrayList<>(responseJson().keySet()));
    }

    @Test
    public void leadsWithTheQueriedHomepageAndOrdersTheRestByPath() throws Exception
    {
        homepage("/Content/Workflows", WORKFLOW_TYPE, "Borrowed workflows", 1);
        homepage(SYSTEM_WORKFLOWS, WORKFLOW_TYPE, "System workflows", 8);
        homepage(WORKFLOWS, WORKFLOW_TYPE, "Workflows", 12);
        addressing(SYSTEM_WORKFLOWS);

        this.servlet.doGet(this.request, this.response);

        Assertions.assertEquals(List.of(SYSTEM_WORKFLOWS, "/Content/Workflows", WORKFLOWS),
            new ArrayList<>(responseJson().keySet()));
    }

    @Test
    public void namesAHomepageWithNoTitleAfterItsNode() throws Exception
    {
        homepage(WORKFLOWS, WORKFLOW_TYPE, null, 3);
        addressing(WORKFLOWS);

        this.servlet.doGet(this.request, this.response);

        Assertions.assertEquals("Workflows", responseJson().getJsonObject(WORKFLOWS).getString("label"));
    }

    @Test
    public void readsTheEntityTypeFromTheResourceTypeWhenTheHomepageNamesNone() throws Exception
    {
        // sub/SubmissionsHomepage lists sub:Submission, so a homepage of that resource type counts submissions and
        // finds the peer that names the type outright
        final Resource derived = homepage("/Submissions", null, "Submissions", 300);
        Mockito.when(derived.getResourceType()).thenReturn("sub/SubmissionsHomepage");
        homepage("/Archived/Submissions", "sub:Submission", "Archived submissions", 4);
        addressing("/Submissions");

        this.servlet.doGet(this.request, this.response);

        final JsonObject summary = responseJson();
        Assertions.assertEquals(List.of("/Submissions", "/Archived/Submissions"), new ArrayList<>(summary.keySet()));
        Assertions.assertEquals(300, summary.getJsonObject("/Submissions").getJsonNumber("value").longValue());
    }

    @Test
    public void reportsACountPastTheCeilingAsALowerBound() throws Exception
    {
        homepage(WORKFLOWS, WORKFLOW_TYPE, "Workflows", (int) PaginatedJsonResponse.MAX_COUNT + 1);
        addressing(WORKFLOWS);

        this.servlet.doGet(this.request, this.response);

        final JsonObject workflows = responseJson().getJsonObject(WORKFLOWS);
        Assertions.assertEquals(PaginatedJsonResponse.MAX_COUNT, workflows.getJsonNumber("value").longValue());
        Assertions.assertTrue(workflows.getBoolean("approximate"));
    }

    @Test
    public void keepsAHomepageThatCannotBeCountedWithAnUnknownValue() throws Exception
    {
        homepage(WORKFLOWS, WORKFLOW_TYPE, "Workflows", 12);
        homepage(SYSTEM_WORKFLOWS, WORKFLOW_TYPE, "System workflows", 8);
        addressing(WORKFLOWS);
        Mockito.doThrow(new RepositoryException("read limit")).when(this.queryManager)
            .createQuery(Mockito.contains(SYSTEM_WORKFLOWS), Mockito.anyString());

        this.servlet.doGet(this.request, this.response);

        final JsonObject summary = responseJson();
        Assertions.assertEquals(12, summary.getJsonObject(WORKFLOWS).getJsonNumber("value").longValue());
        final JsonObject system = summary.getJsonObject(SYSTEM_WORKFLOWS);
        Assertions.assertEquals("System workflows", system.getString("label"));
        Assertions.assertTrue(system.isNull("value"));
        Assertions.assertEquals(SYSTEM_WORKFLOWS, system.getString("path"));
    }

    @Test
    public void answersWithAnErrorWhenNoHomepageCanBeCounted() throws Exception
    {
        homepage(WORKFLOWS, WORKFLOW_TYPE, "Workflows", 12);
        homepage(SYSTEM_WORKFLOWS, WORKFLOW_TYPE, "System workflows", 8);
        addressing(WORKFLOWS);
        Mockito.doThrow(new IllegalStateException("memory limit")).when(this.queryManager)
            .createQuery(Mockito.anyString(), Mockito.anyString());

        this.servlet.doGet(this.request, this.response);

        Mockito.verify(this.response).setStatus(SlingJakartaHttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        Assertions.assertEquals("Failed to count the entities", responseJson().getString("error"));
    }

    @Test
    public void answersWithAnErrorWithoutASession() throws Exception
    {
        homepage(WORKFLOWS, WORKFLOW_TYPE, "Workflows", 12);
        addressing(WORKFLOWS);
        Mockito.when(this.resolver.adaptTo(Session.class)).thenReturn(null);

        this.servlet.doGet(this.request, this.response);

        Mockito.verify(this.response).setStatus(SlingJakartaHttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        Assertions.assertEquals("Failed to count the entities", responseJson().getString("error"));
    }

    @Test
    public void answersWithAnErrorWhenTheHomepagesCannotBeFound() throws Exception
    {
        homepage(WORKFLOWS, WORKFLOW_TYPE, "Workflows", 12);
        addressing(WORKFLOWS);
        Mockito.when(this.resolver.findResources(Mockito.anyString(), Mockito.anyString()))
            .thenThrow(new IllegalStateException("no session"));

        this.servlet.doGet(this.request, this.response);

        Mockito.verify(this.response).setStatus(SlingJakartaHttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        Assertions.assertEquals("Failed to count the entities", responseJson().getString("error"));
    }

    /** Mocks one homepage, and records how many entities it holds. */
    private Resource homepage(final String path, final String childNodeType, final String title, final int held)
    {
        final Resource homepage = Mockito.mock(Resource.class);
        final Map<String, Object> properties = new HashMap<>();
        if (childNodeType != null) {
            properties.put("childNodeType", childNodeType);
        }
        if (title != null) {
            properties.put("title", title);
        }
        Mockito.when(homepage.getPath()).thenReturn(path);
        Mockito.when(homepage.getName()).thenReturn(path.substring(path.lastIndexOf('/') + 1));
        Mockito.when(homepage.getValueMap()).thenReturn(new ValueMapDecorator(properties));
        this.homepages.put(path, homepage);
        this.entities.put(path, held);
        return homepage;
    }

    /** Points the request at one of the mocked homepages. */
    private void addressing(final String path)
    {
        Mockito.when(this.request.getResource()).thenReturn(this.homepages.get(path));
    }

    /** A count query finding as many rows as the test gave the homepage the statement is scoped to. */
    private Query countQuery(final String statement) throws RepositoryException
    {
        final int held = this.entities.entrySet().stream()
            .filter(entry -> statement.contains("isdescendantnode(n, '" + entry.getKey() + "')"))
            .findFirst()
            .map(Map.Entry::getValue)
            .orElse(0);
        final Iterator<Row> found = Collections.nCopies(held, Mockito.mock(Row.class)).iterator();
        final RowIterator rows = Mockito.mock(RowIterator.class);
        Mockito.when(rows.hasNext()).thenAnswer(invocation -> found.hasNext());
        Mockito.when(rows.nextRow()).thenAnswer(invocation -> found.next());
        final QueryResult result = Mockito.mock(QueryResult.class);
        Mockito.when(result.getRows()).thenReturn(rows);
        final Query query = Mockito.mock(Query.class);
        Mockito.when(query.execute()).thenReturn(result);
        return query;
    }

    private JsonObject responseJson()
    {
        return Json.createReader(new StringReader(this.output.toString())).readObject();
    }
}

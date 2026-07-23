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
package io.uhndata.iap.entities.internal;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

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
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Unit tests for {@link PaginationServlet}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class PaginationServletTest
{
    private static final String SCOPE = "/Submissions";

    private PaginationServlet servlet;

    private SlingJakartaHttpServletRequest request;

    private SlingJakartaHttpServletResponse response;

    private ResourceResolver resolver;

    private Session session;

    private QueryManager queryManager;

    private StringWriter output;

    @BeforeEach
    public void setup() throws Exception
    {
        this.servlet = new PaginationServlet();
        this.request = Mockito.mock(SlingJakartaHttpServletRequest.class);
        this.response = Mockito.mock(SlingJakartaHttpServletResponse.class);
        this.resolver = Mockito.mock(ResourceResolver.class);
        this.session = Mockito.mock(Session.class);
        this.queryManager = Mockito.mock(QueryManager.class);
        this.output = new StringWriter();

        Mockito.when(this.request.getResourceResolver()).thenReturn(this.resolver);
        Mockito.when(this.resolver.adaptTo(Session.class)).thenReturn(this.session);
        Mockito.when(this.session.getUserID()).thenReturn("testUser");
        final Workspace workspace = Mockito.mock(Workspace.class);
        Mockito.when(this.session.getWorkspace()).thenReturn(workspace);
        Mockito.when(workspace.getQueryManager()).thenReturn(this.queryManager);
        Mockito.when(this.response.getWriter()).thenReturn(new PrintWriter(this.output));

        // Every resolved resource serializes to a small JSON object identifying it by path
        Mockito.when(this.resolver.resolve(Mockito.anyString())).thenAnswer(invocation -> {
            final Resource resource = Mockito.mock(Resource.class);
            Mockito.when(resource.adaptTo(JsonObject.class)).thenReturn(
                Json.createObjectBuilder().add("path", invocation.getArgument(0, String.class)).build());
            return resource;
        });

        mockHomepage("sub/SubmissionsHomepage", null);
    }

    @Test
    public void defaultRequestListsFirstPage() throws Exception
    {
        final ArgumentCaptor<String> statement = mockResults("/Submissions/s1", "/Submissions/s2");
        this.servlet.doGet(this.request, this.response);
        Assertions.assertEquals(
            "select n.* from [sub:Submission] as n where isdescendantnode(n, '/Submissions')"
                + " order by n.[jcr:created] ASC", statement.getValue());
        final JsonObject result = getResponseJson();
        Assertions.assertEquals(2, result.getJsonArray("rows").size());
        Assertions.assertEquals("/Submissions/s1", result.getJsonArray("rows").getJsonObject(0).getString("path"));
        Assertions.assertEquals(0, result.getJsonNumber("offset").longValue());
        Assertions.assertEquals(10, result.getJsonNumber("limit").longValue());
        Assertions.assertEquals(2, result.getJsonNumber("returnedrows").longValue());
        Assertions.assertEquals(2, result.getJsonNumber("totalrows").longValue());
        Assertions.assertFalse(result.getBoolean("totalIsApproximate"));
        Assertions.assertFalse(result.containsKey("req"));
    }

    @Test
    public void offsetAndLimitSelectTheRequestedPage() throws Exception
    {
        withParameter("offset", "1");
        withParameter("limit", "2");
        withParameter("req", "7");
        mockResults("/Submissions/s1", "/Submissions/s2", "/Submissions/s3", "/Submissions/s4");
        this.servlet.doGet(this.request, this.response);
        final JsonObject result = getResponseJson();
        Assertions.assertEquals(2, result.getJsonArray("rows").size());
        Assertions.assertEquals("/Submissions/s2", result.getJsonArray("rows").getJsonObject(0).getString("path"));
        Assertions.assertEquals("/Submissions/s3", result.getJsonArray("rows").getJsonObject(1).getString("path"));
        Assertions.assertEquals(4, result.getJsonNumber("totalrows").longValue());
        Assertions.assertEquals("7", result.getString("req"));
    }

    @Test
    public void duplicateRowsFromJoinsAreListedOnce() throws Exception
    {
        mockResults("/Submissions/s1", "/Submissions/s1", "/Submissions/s2", "/Submissions/s1");
        this.servlet.doGet(this.request, this.response);
        final JsonObject result = getResponseJson();
        Assertions.assertEquals(2, result.getJsonArray("rows").size());
        Assertions.assertEquals(2, result.getJsonNumber("totalrows").longValue());
    }

    @Test
    public void countingStopsAfterTheLookaheadAndReportsAnApproximateTotal() throws Exception
    {
        withParameter("limit", "1");
        mockResults(IntStream.range(0, 15).mapToObj(i -> "/Submissions/s" + i).toArray(String[]::new));
        this.servlet.doGet(this.request, this.response);
        final JsonObject result = getResponseJson();
        Assertions.assertEquals(1, result.getJsonArray("rows").size());
        Assertions.assertEquals(10, result.getJsonNumber("totalrows").longValue());
        Assertions.assertTrue(result.getBoolean("totalIsApproximate"));
    }

    @Test
    public void zeroLimitOnlyCountsTheMatches() throws Exception
    {
        withParameter("limit", "0");
        mockResults("/Submissions/s1", "/Submissions/s2", "/Submissions/s3");
        this.servlet.doGet(this.request, this.response);
        final JsonObject result = getResponseJson();
        Assertions.assertEquals(0, result.getJsonArray("rows").size());
        Assertions.assertEquals(3, result.getJsonNumber("totalrows").longValue());
        Assertions.assertFalse(result.getBoolean("totalIsApproximate"));
    }

    @Test
    public void invalidNumbersFallBackToDefaultsAndExcessiveLimitsAreCapped() throws Exception
    {
        withParameter("offset", "-5");
        withParameter("limit", "9999");
        mockResults("/Submissions/s1");
        this.servlet.doGet(this.request, this.response);
        JsonObject result = getResponseJson();
        Assertions.assertEquals(0, result.getJsonNumber("offset").longValue());
        Assertions.assertEquals(1000, result.getJsonNumber("limit").longValue());

        resetOutput();
        withParameter("offset", "NaN");
        withParameter("limit", "NaN");
        mockResults("/Submissions/s1");
        this.servlet.doGet(this.request, this.response);
        result = getResponseJson();
        Assertions.assertEquals(0, result.getJsonNumber("offset").longValue());
        Assertions.assertEquals(10, result.getJsonNumber("limit").longValue());
    }

    @Test
    public void resourceSelectorsAreCleanedUpAndAppendedToTheSerializedPath() throws Exception
    {
        withParameter("resourceSelectors", "deep..simple/ etc");
        mockResults("/Submissions/s1");
        this.servlet.doGet(this.request, this.response);
        final JsonObject result = getResponseJson();
        Assertions.assertEquals("/Submissions/s1.deep.simpleetc",
            result.getJsonArray("rows").getJsonObject(0).getString("path"));
    }

    @Test
    public void blankResourceSelectorsAreIgnored() throws Exception
    {
        withParameter("resourceSelectors", " ");
        mockResults("/Submissions/s1");
        this.servlet.doGet(this.request, this.response);
        final JsonObject result = getResponseJson();
        Assertions.assertEquals("/Submissions/s1", result.getJsonArray("rows").getJsonObject(0).getString("path"));
    }

    @Test
    public void unserializableResourcesAreListedByPath() throws Exception
    {
        final Resource broken = Mockito.mock(Resource.class);
        Mockito.when(broken.adaptTo(JsonObject.class)).thenReturn(null);
        Mockito.when(this.resolver.resolve("/Submissions/s1")).thenReturn(broken);
        mockResults("/Submissions/s1");
        this.servlet.doGet(this.request, this.response);
        final JsonObject result = getResponseJson();
        Assertions.assertEquals("/Submissions/s1", result.getJsonArray("rows").getJsonObject(0).getString("@path"));
    }

    @Test
    public void propertyFiltersAndSortingAreForwardedToTheQuery() throws Exception
    {
        withParameter("fieldName", "jcr:createdBy", "status");
        withParameter("fieldComparator", "=", "<>");
        withParameter("fieldValue", "@me", "draft");
        withParameter("filter", "cancer");
        withParameter("sortBy", "jcr:lastModified");
        withParameter("descending", "true");
        final ArgumentCaptor<String> statement = mockResults();
        this.servlet.doGet(this.request, this.response);
        Assertions.assertEquals(
            "select n.* from [sub:Submission] as n where isdescendantnode(n, '/Submissions')"
                + " and n.[jcr:createdBy] = 'testUser'"
                + " and not n.[status] = 'draft'"
                + " and contains(n.*, 'cancer')"
                + " order by n.[jcr:lastModified] DESC", statement.getValue());
    }

    @Test
    public void missingComparatorsDefaultToEquals() throws Exception
    {
        withParameter("fieldName", "status");
        withParameter("fieldValue", "draft");
        final ArgumentCaptor<String> statement = mockResults();
        this.servlet.doGet(this.request, this.response);
        Assertions.assertTrue(statement.getValue().contains("and n.[status] = 'draft'"));
    }

    @Test
    public void childFiltersJoinOnTheChildType() throws Exception
    {
        withParameter("childType", "sub:Review");
        withParameter("childFieldName", "reviewer", "status");
        withParameter("childFieldComparator", "=", "<>");
        withParameter("childFieldValue", "@me", "approved");
        final ArgumentCaptor<String> statement = mockResults();
        this.servlet.doGet(this.request, this.response);
        Assertions.assertEquals(
            "select n.* from [sub:Submission] as n"
                + " inner join [sub:Review] as c on isdescendantnode(c, n)"
                + " where isdescendantnode(n, '/Submissions')"
                + " and c.[reviewer] = 'testUser'"
                + " and not c.[status] = 'approved'"
                + " order by n.[jcr:created] ASC", statement.getValue());
    }

    @Test
    public void explicitChildNodeTypeOverridesTheNamingConvention() throws Exception
    {
        mockHomepage("iap/EntityHomepage", "iap:TestEntity");
        final ArgumentCaptor<String> statement = mockResults();
        this.servlet.doGet(this.request, this.response);
        Assertions.assertTrue(statement.getValue().startsWith("select n.* from [iap:TestEntity] as n"));
    }

    @Test
    public void mismatchedFilterParametersAreRejected() throws Exception
    {
        withParameter("fieldName", "status");
        this.servlet.doGet(this.request, this.response);
        assertError(SlingJakartaHttpServletResponse.SC_BAD_REQUEST);

        resetOutput();
        withParameter("fieldValue", "draft", "submitted");
        this.servlet.doGet(this.request, this.response);
        assertError(SlingJakartaHttpServletResponse.SC_BAD_REQUEST);

        resetOutput();
        withParameter("fieldValue", "draft");
        withParameter("fieldComparator", "=", "=");
        this.servlet.doGet(this.request, this.response);
        assertError(SlingJakartaHttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    public void childFiltersWithoutChildTypeAreRejected() throws Exception
    {
        withParameter("childFieldName", "reviewer");
        withParameter("childFieldValue", "@me");
        this.servlet.doGet(this.request, this.response);
        assertError(SlingJakartaHttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    public void invalidPropertyNamesAreRejected() throws Exception
    {
        withParameter("fieldName", "status] IS NULL or n.[x");
        withParameter("fieldValue", "draft");
        this.servlet.doGet(this.request, this.response);
        assertError(SlingJakartaHttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    public void missingJcrSessionIsReportedAsAnError() throws Exception
    {
        Mockito.when(this.resolver.adaptTo(Session.class)).thenReturn(null);
        this.servlet.doGet(this.request, this.response);
        assertError(SlingJakartaHttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }

    @Test
    public void repositoryErrorsAreReportedAsAnError() throws Exception
    {
        Mockito.when(this.queryManager.createQuery(Mockito.anyString(), Mockito.eq(Query.JCR_SQL2)))
            .thenThrow(new RepositoryException("Query engine down"));
        this.servlet.doGet(this.request, this.response);
        assertError(SlingJakartaHttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }

    private void mockHomepage(final String resourceType, final String childNodeType)
    {
        final Resource homepage = Mockito.mock(Resource.class);
        Mockito.when(homepage.getPath()).thenReturn(SCOPE);
        Mockito.when(homepage.getResourceType()).thenReturn(resourceType);
        final Map<String, Object> properties =
            childNodeType == null ? Map.of() : Map.of("childNodeType", childNodeType);
        Mockito.when(homepage.getValueMap()).thenReturn(new ValueMapDecorator(properties));
        Mockito.when(this.request.getResource()).thenReturn(homepage);
    }

    /**
     * Mocks the query infrastructure to return rows with the given paths, and captures the query statement.
     *
     * @param paths the paths of the rows the query returns
     * @return a captor holding the statement passed to the query manager, filled in once the servlet runs
     */
    private ArgumentCaptor<String> mockResults(final String... paths) throws RepositoryException
    {
        final ArgumentCaptor<String> statement = ArgumentCaptor.forClass(String.class);
        final Query query = Mockito.mock(Query.class);
        Mockito.when(this.queryManager.createQuery(statement.capture(), Mockito.eq(Query.JCR_SQL2)))
            .thenReturn(query);
        final QueryResult result = Mockito.mock(QueryResult.class);
        Mockito.when(query.execute()).thenReturn(result);
        final Iterator<String> iterator = List.of(paths).iterator();
        final RowIterator rows = Mockito.mock(RowIterator.class);
        Mockito.when(rows.hasNext()).thenAnswer(invocation -> iterator.hasNext());
        Mockito.when(rows.nextRow()).thenAnswer(invocation -> {
            final Row row = Mockito.mock(Row.class);
            Mockito.when(row.getPath("n")).thenReturn(iterator.next());
            return row;
        });
        Mockito.when(result.getRows()).thenReturn(rows);
        return statement;
    }

    private void withParameter(final String name, final String... values)
    {
        Mockito.when(this.request.getParameter(name)).thenReturn(values[0]);
        Mockito.when(this.request.getParameterValues(name)).thenReturn(values);
    }

    /**
     * Prepares for a second request in the same test: empties the captured output and hands the response a fresh
     * writer, since writing the first response closed the previous one.
     *
     * @throws Exception in case of unexpected mocking errors
     */
    private void resetOutput() throws Exception
    {
        this.output.getBuffer().setLength(0);
        Mockito.when(this.response.getWriter()).thenReturn(new PrintWriter(this.output));
    }

    private JsonObject getResponseJson()
    {
        return Json.createReader(new StringReader(this.output.toString())).readObject();
    }

    private void assertError(final int expectedStatus)
    {
        Mockito.verify(this.response, Mockito.atLeastOnce()).setStatus(expectedStatus);
        Assertions.assertTrue(getResponseJson().containsKey("error"));
    }

}

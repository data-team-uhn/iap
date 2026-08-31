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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
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

    /** The values the servlet bound into the statement, in the order it bound them. */
    private final Map<String, String> boundValues = new LinkedHashMap<>();

    private StringWriter output;

    private Map<String, String[]> parameters;

    @BeforeEach
    public void setup() throws Exception
    {
        this.parameters = new HashMap<>();
        this.servlet = new PaginationServlet();
        this.request = Mockito.mock(SlingJakartaHttpServletRequest.class);
        this.response = Mockito.mock(SlingJakartaHttpServletResponse.class);
        this.resolver = Mockito.mock(ResourceResolver.class);
        this.session = Mockito.mock(Session.class);
        this.queryManager = Mockito.mock(QueryManager.class);
        this.output = new StringWriter();

        Mockito.when(this.request.getParameterMap()).thenReturn(this.parameters);
        Mockito.when(this.request.getResourceResolver()).thenReturn(this.resolver);
        Mockito.when(this.resolver.adaptTo(Session.class)).thenReturn(this.session);
        Mockito.when(this.session.getUserID()).thenReturn("testUser");
        final Workspace workspace = Mockito.mock(Workspace.class);
        Mockito.when(this.session.getWorkspace()).thenReturn(workspace);
        Mockito.when(workspace.getQueryManager()).thenReturn(this.queryManager);
        // A value factory that hands back what it was given, so a test can read the bound values back
        final ValueFactory valueFactory = Mockito.mock(ValueFactory.class);
        Mockito.when(this.session.getValueFactory()).thenReturn(valueFactory);
        Mockito.when(valueFactory.createValue(Mockito.anyString())).thenAnswer(invocation -> {
            final Value value = Mockito.mock(Value.class);
            Mockito.when(value.getString()).thenReturn(invocation.getArgument(0, String.class));
            return value;
        });
        this.boundValues.clear();
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
        mockResults(IntStream.range(0, 150).mapToObj(i -> "/Submissions/s" + i).toArray(String[]::new));
        this.servlet.doGet(this.request, this.response);
        final JsonObject result = getResponseJson();
        Assertions.assertEquals(1, result.getJsonArray("rows").size());
        Assertions.assertEquals(100, result.getJsonNumber("totalrows").longValue());
        Assertions.assertTrue(result.getBoolean("totalIsApproximate"));
    }

    @Test
    public void lookaheadIsBoundedForLargePageSizes() throws Exception
    {
        // 100 pages of lookahead at this page size would mean counting 20 000 rows; the absolute
        // row bound kicks in instead
        withParameter("limit", "200");
        mockResults(IntStream.range(0, 10_150).mapToObj(i -> "/Submissions/s" + i).toArray(String[]::new));
        this.servlet.doGet(this.request, this.response);
        final JsonObject result = getResponseJson();
        Assertions.assertEquals(10_000, result.getJsonNumber("totalrows").longValue());
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
    public void unserializableResourcesAreSkipped() throws Exception
    {
        final Resource broken = Mockito.mock(Resource.class);
        Mockito.when(broken.adaptTo(JsonObject.class)).thenReturn(null);
        Mockito.when(this.resolver.resolve("/Submissions/s1")).thenReturn(broken);
        mockResults("/Submissions/s1", "/Submissions/s2");
        this.servlet.doGet(this.request, this.response);
        final JsonObject result = getResponseJson();
        Assertions.assertEquals(1, result.getJsonArray("rows").size());
        Assertions.assertEquals("/Submissions/s2", result.getJsonArray("rows").getJsonObject(0).getString("path"));
        Assertions.assertEquals(1, result.getJsonNumber("returnedrows").longValue());
        // The skipped entity is still a match, so it still counts towards the total
        Assertions.assertEquals(2, result.getJsonNumber("totalrows").longValue());
    }

    @Test
    public void serializationErrorsDontBreakTheResponse() throws Exception
    {
        final Resource broken = Mockito.mock(Resource.class);
        Mockito.when(broken.adaptTo(JsonObject.class)).thenThrow(new IllegalStateException("Broken resource"));
        Mockito.when(this.resolver.resolve("/Submissions/s1")).thenReturn(broken);
        mockResults("/Submissions/s1", "/Submissions/s2");
        this.servlet.doGet(this.request, this.response);
        final JsonObject result = getResponseJson();
        Assertions.assertEquals(1, result.getJsonArray("rows").size());
        Assertions.assertEquals("/Submissions/s2", result.getJsonArray("rows").getJsonObject(0).getString("path"));
        Assertions.assertFalse(result.containsKey("error"));
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
                + " and (n.[jcr:createdBy] = $p0)"
                + " and (not n.[status] = $p1)"
                + " and contains(n.*, $p2)"
                + " order by n.[jcr:lastModified] DESC", statement.getValue());
        // @me is resolved before the value is bound, so it is the session's user that reaches the repository
        Assertions.assertEquals(Map.of("p0", "testUser", "p1", "draft", "p2", "cancer"), this.boundValues);
    }

    @Test
    public void groupedFiltersAreOredTogether() throws Exception
    {
        withParameter("fieldName", "jcr:createdBy", "status", "status");
        withParameter("fieldComparator", "=", "=", "=");
        withParameter("fieldValue", "@me", "submitted", "in-review");
        withParameter("fieldGroup", "", "st", "st");
        final ArgumentCaptor<String> statement = mockResults();
        this.servlet.doGet(this.request, this.response);
        Assertions.assertEquals(
            "select n.* from [sub:Submission] as n where isdescendantnode(n, '/Submissions')"
                + " and (n.[jcr:createdBy] = $p0)"
                + " and (n.[status] = $p1 or n.[status] = $p2)"
                + " order by n.[jcr:created] ASC", statement.getValue());
        Assertions.assertEquals(Map.of("p0", "testUser", "p1", "submitted", "p2", "in-review"), this.boundValues);
    }

    @Test
    public void mismatchedGroupParametersAreRejected() throws Exception
    {
        withParameter("fieldName", "status");
        withParameter("fieldValue", "draft");
        withParameter("fieldGroup", "g1", "g2");
        this.servlet.doGet(this.request, this.response);
        assertError(SlingJakartaHttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    public void missingComparatorsDefaultToEquals() throws Exception
    {
        withParameter("fieldName", "status");
        withParameter("fieldValue", "draft");
        final ArgumentCaptor<String> statement = mockResults();
        this.servlet.doGet(this.request, this.response);
        Assertions.assertTrue(statement.getValue().contains("and (n.[status] = $p0)"), statement.getValue());
        Assertions.assertEquals(Map.of("p0", "draft"), this.boundValues);
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
                + " inner join [sub:Review] as c0 on isdescendantnode(c0, n)"
                + " where isdescendantnode(n, '/Submissions')"
                + " and (c0.[reviewer] = $p0)"
                + " and (not c0.[status] = $p1)"
                + " order by n.[jcr:created] ASC", statement.getValue());
        Assertions.assertEquals(Map.of("p0", "testUser", "p1", "approved"), this.boundValues);
    }

    @Test
    public void numberedChildFamiliesEachJoinOnTheirOwnDescendant() throws Exception
    {
        withParameter("childType", "sub:Review");
        withParameter("childFieldName", "reviewer");
        withParameter("childFieldValue", "@me");
        // No conditions for this family: only the descendant's existence is required
        withParameter("childType10", "sub:Attachment");
        withParameter("childType2", "sub:Signature");
        withParameter("childField2Name", "signer");
        withParameter("childField2Value", "bob");
        final ArgumentCaptor<String> statement = mockResults();
        this.servlet.doGet(this.request, this.response);
        Assertions.assertEquals(
            // The families are joined in numeric order: plain, then 2, then 10
            "select n.* from [sub:Submission] as n"
                + " inner join [sub:Review] as c0 on isdescendantnode(c0, n)"
                + " inner join [sub:Signature] as c1 on isdescendantnode(c1, n)"
                + " inner join [sub:Attachment] as c2 on isdescendantnode(c2, n)"
                + " where isdescendantnode(n, '/Submissions')"
                + " and (c0.[reviewer] = $p0)"
                + " and (c1.[signer] = $p1)"
                + " order by n.[jcr:created] ASC", statement.getValue());
    }

    @Test
    public void numberedChildFiltersWithoutMatchingChildTypeAreRejected() throws Exception
    {
        withParameter("childType", "sub:Review");
        withParameter("childField3Name", "reviewer");
        withParameter("childField3Value", "@me");
        this.servlet.doGet(this.request, this.response);
        assertError(SlingJakartaHttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    public void explicitChildNodeTypeOverridesTheNamingConvention() throws Exception
    {
        mockHomepage("data/EntityHomepage", "test:Entity");
        final ArgumentCaptor<String> statement = mockResults();
        this.servlet.doGet(this.request, this.response);
        Assertions.assertTrue(statement.getValue().startsWith("select n.* from [test:Entity] as n"));
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

    @Test
    public void filterValuesAreBoundRatherThanWrittenIntoTheStatement() throws Exception
    {
        mockHomepage("sub/SubmissionsHomepage", null);
        withParameter("fieldName", "owner", "title");
        withParameter("fieldComparator", "=", "ILIKE");
        // A value that would end the string literal if it were written into the statement
        withParameter("fieldValue", "O'Brien", "%CARdiac%");
        withParameter("filter", "tumor");
        final ArgumentCaptor<String> statement = mockResults(SCOPE + "/s1");

        this.servlet.doGet(this.request, this.response);

        // Nothing the caller sent appears in the statement
        Assertions.assertEquals("select n.* from [sub:Submission] as n"
            + " where isdescendantnode(n, '/Submissions')"
            + " and (n.[owner] = $p0)"
            + " and (LOWER(n.[title]) LIKE $p1)"
            + " and contains(n.*, $p2)"
            + " order by n.[jcr:created] ASC", statement.getValue());
        // ... and all of it is bound, the apostrophe unescaped and the case-insensitive value lowercased
        Assertions.assertEquals(Map.of("p0", "O'Brien", "p1", "%cardiac%", "p2", "tumor"), this.boundValues);
    }

    @Test
    public void aValuelessComparatorBindsNothing() throws Exception
    {
        mockHomepage("sub/SubmissionsHomepage", null);
        withParameter("fieldName", "schemaVersion");
        withParameter("fieldComparator", "IS NOT NULL");
        withParameter("fieldValue", "ignored");
        final ArgumentCaptor<String> statement = mockResults(SCOPE + "/s1");

        this.servlet.doGet(this.request, this.response);

        Assertions.assertTrue(statement.getValue().contains("n.[schemaVersion] IS NOT NULL"),
            statement.getValue());
        Assertions.assertEquals(Map.of(), this.boundValues);
    }

    @Test
    public void aFailureWhileReadingTheResultsStillLeavesParsableJson() throws Exception
    {
        // Executing the query is not the same as reading it: the result set is lazy, so a read can fail once part of
        // the response has already gone out, and by then an error response could only be appended to it
        final Query query = Mockito.mock(Query.class);
        Mockito.when(this.queryManager.createQuery(Mockito.anyString(), Mockito.eq(Query.JCR_SQL2)))
            .thenReturn(query);
        final QueryResult result = Mockito.mock(QueryResult.class);
        Mockito.when(query.execute()).thenReturn(result);
        final Deque<String> remaining = new ArrayDeque<>(List.of(SCOPE + "/s1"));
        final RowIterator rows = Mockito.mock(RowIterator.class);
        Mockito.when(rows.hasNext()).thenReturn(true);
        Mockito.when(rows.nextRow()).thenAnswer(invocation -> {
            if (remaining.isEmpty()) {
                throw new RepositoryException("The session went away");
            }
            final Row row = Mockito.mock(Row.class);
            Mockito.when(row.getPath("n")).thenReturn(remaining.removeFirst());
            return row;
        });
        Mockito.when(result.getRows()).thenReturn(rows);

        this.servlet.doGet(this.request, this.response);
        final JsonObject written = getResponseJson();
        Assertions.assertEquals(1, written.getJsonArray("rows").size());
        Assertions.assertEquals("Failed to read all the results", written.getString("error"));
        Assertions.assertTrue(written.getBoolean("partial"));
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
        recordBindings(query);
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

    /**
     * Records what the servlet binds into a query, so that a test can assert on the values as well as on the
     * statement naming them.
     *
     * @param query the query mock to watch
     */
    private void recordBindings(final Query query) throws RepositoryException
    {
        Mockito.doAnswer(invocation -> {
            this.boundValues.put(invocation.getArgument(0, String.class),
                invocation.getArgument(1, Value.class).getString());
            return null;
        }).when(query).bindValue(Mockito.anyString(), Mockito.any(Value.class));
    }

    private void withParameter(final String name, final String... values)
    {
        this.parameters.put(name, values);
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

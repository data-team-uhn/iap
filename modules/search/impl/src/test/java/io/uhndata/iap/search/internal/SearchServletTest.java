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
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.IntStream;

import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.Workspace;
import javax.jcr.query.InvalidQueryException;
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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.search.api.SearchParameters;
import io.uhndata.iap.search.spi.QuickSearchEngine;
import io.uhndata.iap.utils.PaginatedJsonResponse;

/**
 * Unit tests for {@link SearchServlet}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class SearchServletTest
{
    private static final String ROWS = "rows";

    private static final String TOTAL = "totalrows";

    private static final String QUERY = "query";

    private static final String SUBMISSION = "sub:Submission";

    private static final String INDEXED_PLAN = "[sub:Submission] as [n] /* property submissionIndex */";

    /**
     * The exclusion every generated full-text statement carries. Spelled out in full, rather than reused from the
     * servlet, by {@link #aFullTextSearchStaysOutOfTheRepositorysBookkeeping()}.
     */
    private static final String OUTSIDE_SYSTEM =
        " and not issamenode(n, '/jcr:system') and not isdescendantnode(n, '/jcr:system')";

    private SearchServlet servlet;

    private SlingJakartaHttpServletRequest request;

    private SlingJakartaHttpServletResponse response;

    private ResourceResolver resolver;

    private QueryManager queryManager;

    private StringWriter output;

    /** Every statement the servlet asked the repository to run, in order, including the explain ones. */
    private List<String> statements;

    /** The columns of the row an {@code explain} returns: {@code null} for no row at all. */
    private String[] planColumns;

    @BeforeEach
    public void setup() throws Exception
    {
        this.servlet = new SearchServlet();
        this.request = Mockito.mock(SlingJakartaHttpServletRequest.class);
        this.response = Mockito.mock(SlingJakartaHttpServletResponse.class);
        this.resolver = Mockito.mock(ResourceResolver.class);
        this.queryManager = Mockito.mock(QueryManager.class);
        this.output = new StringWriter();
        this.statements = new ArrayList<>();
        this.planColumns = new String[] { INDEXED_PLAN };

        final Session session = Mockito.mock(Session.class);
        final Workspace workspace = Mockito.mock(Workspace.class);
        Mockito.when(this.request.getResourceResolver()).thenReturn(this.resolver);
        Mockito.when(this.resolver.adaptTo(Session.class)).thenReturn(session);
        Mockito.when(session.getWorkspace()).thenReturn(workspace);
        Mockito.when(workspace.getQueryManager()).thenReturn(this.queryManager);
        Mockito.when(this.response.getWriter()).thenReturn(new PrintWriter(this.output));

        // Every resolved resource serializes to a small JSON object identifying it by path
        Mockito.when(this.resolver.resolve(Mockito.anyString())).thenAnswer(invocation -> {
            final Resource resource = Mockito.mock(Resource.class);
            Mockito.when(resource.adaptTo(JsonObject.class)).thenReturn(
                Json.createObjectBuilder().add("path", invocation.getArgument(0, String.class)).build());
            return resource;
        });
        withEngines();
    }

    @Test
    public void aRequestWithoutAQueryReturnsNothing() throws Exception
    {
        this.servlet.doGet(this.request, this.response);
        final JsonObject result = getResponseJson();
        Assertions.assertEquals(0, result.getJsonArray(ROWS).size());
        Assertions.assertEquals(0, result.getInt(TOTAL));
        Assertions.assertEquals(List.of(), this.statements);
        Mockito.verify(this.response).setContentType("application/json");
    }

    @Test
    public void aBlankQueryReturnsNothing() throws Exception
    {
        withParameter(QUERY, "   ");
        this.servlet.doGet(this.request, this.response);
        Assertions.assertEquals(0, getResponseJson().getJsonArray(ROWS).size());
        Assertions.assertEquals(List.of(), this.statements);
    }

    @Test
    public void aJcrQueryIsRunAsItIs() throws Exception
    {
        final String statement = "select * from [sub:Submission]";
        withParameter(QUERY, statement);
        mockNodeResults("/Submissions/s1", "/Submissions/s2");
        this.servlet.doGet(this.request, this.response);
        Assertions.assertEquals(List.of(statement, "explain " + statement), this.statements);
        final JsonObject result = getResponseJson();
        Assertions.assertEquals(2, result.getJsonArray(ROWS).size());
        Assertions.assertEquals("/Submissions/s1", result.getJsonArray(ROWS).getJsonObject(0).getString("path"));
        Assertions.assertEquals(2, result.getInt(TOTAL));
    }

    @Test
    public void nodesReachedTwiceAreReturnedOnce() throws Exception
    {
        withParameter(QUERY, "select * from [sub:Submission]");
        mockNodeResults("/Submissions/s1", "/Submissions/s1", "/Submissions/s2");
        this.servlet.doGet(this.request, this.response);
        Assertions.assertEquals(2, getResponseJson().getInt(TOTAL));
    }

    @Test
    public void serializationSelectorsAreAppliedToEachResult() throws Exception
    {
        withParameter(QUERY, "select * from [sub:Submission]");
        withParameter("resourceSelectors", "deep");
        mockNodeResults("/Submissions/s1");
        this.servlet.doGet(this.request, this.response);
        Assertions.assertEquals("/Submissions/s1.deep",
            getResponseJson().getJsonArray(ROWS).getJsonObject(0).getString("path"));
    }

    @Test
    public void aResultThatCannotBeSerializedIsLeftOut() throws Exception
    {
        withParameter(QUERY, "select * from [sub:Submission]");
        mockNodeResults("/Submissions/s1");
        Mockito.when(this.resolver.resolve("/Submissions/s1")).thenThrow(new IllegalStateException("Broken"));
        this.servlet.doGet(this.request, this.response);
        final JsonObject result = getResponseJson();
        Assertions.assertEquals(0, result.getJsonArray(ROWS).size());
        // It still counts: the query did match it, it just could not be shown
        Assertions.assertEquals(1, result.getInt(TOTAL));
    }

    @Test
    public void rawResultsReturnTheSelectedColumns() throws Exception
    {
        withParameter(QUERY, "select f.subject from [sub:Submission] as f");
        withParameter("rawResults", "true");
        mockRawResults();
        this.servlet.doGet(this.request, this.response);
        final JsonObject row = getResponseJson().getJsonArray(ROWS).getJsonObject(0);
        Assertions.assertEquals("/Submissions/s1", row.getString("f"));
        Assertions.assertEquals("value of subject", row.getString("f.subject"));
    }

    @Test
    public void rawResultsThatCannotBeReadAreLeftOut() throws Exception
    {
        withParameter(QUERY, "select f.subject from [sub:Submission] as f");
        withParameter("rawResults", "true");
        final Row row = Mockito.mock(Row.class);
        Mockito.when(row.getPath("f")).thenThrow(new RepositoryException("Gone"));
        mockResults(singleRow(row), new String[] { "f" }, new String[] { "f.subject" });
        this.servlet.doGet(this.request, this.response);
        final JsonObject result = getResponseJson();
        Assertions.assertEquals(0, result.getJsonArray(ROWS).size());
        Assertions.assertEquals(1, result.getInt(TOTAL));
    }

    @Test
    public void rawResultsKeepTheColumnsThatHaveNoValue() throws Exception
    {
        withParameter(QUERY, "select f.subject from [sub:Submission] as f");
        withParameter("rawResults", "true");
        // A selector may have no node in an outer join, and a column may have no value on the row that matched
        mockResults(singleRow(Mockito.mock(Row.class)), new String[] { "f" }, new String[] { "f.subject" });
        this.servlet.doGet(this.request, this.response);
        final JsonObject row = getResponseJson().getJsonArray(ROWS).getJsonObject(0);
        Assertions.assertTrue(row.isNull("f"));
        Assertions.assertTrue(row.isNull("f.subject"));
    }

    @Test
    public void aPlanWithNoColumnsStillRuns() throws Exception
    {
        this.planColumns = new String[0];
        withParameter(QUERY, "select * from [sub:Submission]");
        mockNodeResults("/Submissions/s1");
        this.servlet.doGet(this.request, this.response);
        Assertions.assertEquals(1, getResponseJson().getJsonArray(ROWS).size());
    }

    @Test
    public void aFullTextSearchLooksEverywhere() throws Exception
    {
        withParameter("fulltext", "diabetes");
        mockNodeResults("/Submissions/s1");
        this.servlet.doGet(this.request, this.response);
        Assertions.assertEquals("select n.* from [nt:base] as n where contains(n.*, 'diabetes')" + OUTSIDE_SYSTEM,
            executedStatement());
    }

    @Test
    public void aFullTextSearchStaysOutOfTheRepositorysBookkeeping() throws Exception
    {
        // Checking a versionable node in leaves a frozen copy of all its properties under
        // /jcr:system/jcr:versionStorage, and the node type registry answers an ordinary word with the property
        // definitions that declare it. Measured against Oak 2.4.0 with a Lucene full-text index: a search matching
        // one submission that had been checked in twice came back with three rows, two of them frozen copies, and
        // one for "versionable" came back with thirty, twenty-nine of them node type definitions. Adding this left
        // the query plan byte for byte the same, so the exclusion costs nothing in index selection.
        withParameter("fulltext", "diabetes");
        mockNodeResults();
        this.servlet.doGet(this.request, this.response);
        Assertions.assertEquals("select n.* from [nt:base] as n where contains(n.*, 'diabetes')"
            + " and not issamenode(n, '/jcr:system') and not isdescendantnode(n, '/jcr:system')",
            executedStatement());
    }

    @Test
    public void aQueryIsRunAsItWasSentEvenIntoTheSystemTree() throws Exception
    {
        // The exclusion is added to the statement this endpoint builds, not to one the client wrote: a client
        // asking for version storage in its own JCR-SQL2 asked for it on purpose
        withParameter(QUERY, "select * from [nt:frozenNode]");
        mockNodeResults();
        this.servlet.doGet(this.request, this.response);
        Assertions.assertEquals("select * from [nt:frozenNode]", executedStatement());
    }

    @Test
    public void aFullTextSearchIgnoresSurroundingWhitespace() throws Exception
    {
        // A full text expression has to start with a term, so a leading space -- what a paste, or an
        // autocompletion, routinely leaves in front of what the user typed -- used to come back as a bad request
        withParameter("fulltext", "  diabetes \t");
        mockNodeResults();
        this.servlet.doGet(this.request, this.response);
        Assertions.assertEquals("select n.* from [nt:base] as n where contains(n.*, 'diabetes')" + OUTSIDE_SYSTEM,
            executedStatement());
    }

    @Test
    public void fullTextOperatorsAreEscapedByDefault() throws Exception
    {
        withParameter("fulltext", "a-b OR c*");
        mockNodeResults();
        this.servlet.doGet(this.request, this.response);
        Assertions.assertEquals("select n.* from [nt:base] as n where contains(n.*, 'a\\-b OR c\\*')" + OUTSIDE_SYSTEM,
            executedStatement());
    }

    @Test
    public void fullTextOperatorsCanBeLeftAlone() throws Exception
    {
        withParameter("fulltext", "a-b OR c*");
        withParameter("doNotEscapeQuery", "true");
        mockNodeResults();
        this.servlet.doGet(this.request, this.response);
        Assertions.assertEquals("select n.* from [nt:base] as n where contains(n.*, 'a-b OR c*')" + OUTSIDE_SYSTEM,
            executedStatement());
    }

    @Test
    public void quotesAreAlwaysEscapedIntoTheStatement() throws Exception
    {
        // Even when the client asks for its full-text operators to be kept: a quote would end the string literal
        // and let the rest of the input be read as query syntax
        withParameter("fulltext", "it's");
        withParameter("doNotEscapeQuery", "true");
        mockNodeResults();
        this.servlet.doGet(this.request, this.response);
        Assertions.assertEquals("select n.* from [nt:base] as n where contains(n.*, 'it''s')" + OUTSIDE_SYSTEM,
            executedStatement());
    }

    @Test
    public void aQueryPreemptsAFullTextSearch() throws Exception
    {
        withParameter(QUERY, "select * from [sub:Submission]");
        withParameter("fulltext", "diabetes");
        withParameter("quick", "diabetes");
        mockNodeResults();
        this.servlet.doGet(this.request, this.response);
        Assertions.assertEquals("select * from [sub:Submission]", executedStatement());
    }

    @Test
    public void anUnindexedQueryIsReported() throws Exception
    {
        this.planColumns = new String[] { "[nt:base] as [n] /* traverse \"*\" */" };
        withParameter(QUERY, "select * from [nt:base]");
        mockNodeResults("/Submissions/s1");
        this.servlet.doGet(this.request, this.response);
        // The query still runs, the warning is only a warning
        Assertions.assertEquals(1, getResponseJson().getJsonArray(ROWS).size());
    }

    @Test
    public void anUnexplainableQueryStillRuns() throws Exception
    {
        withParameter(QUERY, "select * from [sub:Submission]");
        mockNodeResults("/Submissions/s1");
        final Query explain = Mockito.mock(Query.class);
        Mockito.when(explain.execute()).thenThrow(new RepositoryException("No plan for you"));
        Mockito.when(this.queryManager.createQuery(Mockito.startsWith("explain"), Mockito.eq(Query.JCR_SQL2)))
            .thenAnswer(invocation -> {
                this.statements.add(invocation.getArgument(0, String.class));
                return explain;
            });
        this.servlet.doGet(this.request, this.response);
        Assertions.assertEquals(1, getResponseJson().getJsonArray(ROWS).size());
    }

    @Test
    public void aQueryWithoutAPlanStillRuns() throws Exception
    {
        this.planColumns = null;
        withParameter(QUERY, "select * from [sub:Submission]");
        mockNodeResults("/Submissions/s1");
        this.servlet.doGet(this.request, this.response);
        Assertions.assertEquals(1, getResponseJson().getJsonArray(ROWS).size());
    }

    @Test
    public void anInvalidQueryIsABadRequest() throws Exception
    {
        withParameter(QUERY, "this is not a query");
        Mockito.when(this.queryManager.createQuery(Mockito.anyString(), Mockito.eq(Query.JCR_SQL2)))
            .thenThrow(new InvalidQueryException("Syntax error"));
        this.servlet.doGet(this.request, this.response);
        assertError(SlingJakartaHttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    public void aRepositoryFailureIsAServerError() throws Exception
    {
        withParameter(QUERY, "select * from [sub:Submission]");
        Mockito.when(this.queryManager.createQuery(Mockito.anyString(), Mockito.eq(Query.JCR_SQL2)))
            .thenThrow(new RepositoryException("Query engine down"));
        this.servlet.doGet(this.request, this.response);
        assertError(SlingJakartaHttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }

    @Test
    public void aSessionlessResolverIsAServerError() throws Exception
    {
        withParameter(QUERY, "select * from [sub:Submission]");
        Mockito.when(this.resolver.adaptTo(Session.class)).thenReturn(null);
        this.servlet.doGet(this.request, this.response);
        assertError(SlingJakartaHttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }

    @Test
    public void quickSearchCollectsFromEveryEngine() throws Exception
    {
        withParameter("quick", "diabetes");
        withEngines(new StubEngine(List.of(SUBMISSION), "s1"), new StubEngine(List.of("sch:Schema"), "sc1"));
        this.servlet.doGet(this.request, this.response);
        final JsonObject result = getResponseJson();
        Assertions.assertEquals(2, result.getJsonArray(ROWS).size());
        Assertions.assertEquals("s1", result.getJsonArray(ROWS).getJsonObject(0).getString("name"));
        Assertions.assertEquals("sc1", result.getJsonArray(ROWS).getJsonObject(1).getString("name"));
        Assertions.assertEquals(List.of(), this.statements);
    }

    @Test
    public void quickSearchOnlyAsksTheEnginesThatCanServeTheRequestedTypes() throws Exception
    {
        final StubEngine submissions = new StubEngine(List.of(SUBMISSION), "s1");
        final StubEngine schemas = new StubEngine(List.of("sch:Schema"), "sc1");
        withParameter("quick", "diabetes");
        withParameter("allowedResourceTypes", SUBMISSION);
        withEngines(submissions, schemas);
        this.servlet.doGet(this.request, this.response);
        Assertions.assertEquals(1, getResponseJson().getJsonArray(ROWS).size());
        Assertions.assertEquals(List.of(SUBMISSION), submissions.searchedTypes);
        Assertions.assertNull(schemas.searchedTypes);
    }

    @Test
    public void anEngineIsOnlyAskedForTheTypesItWasAllowed() throws Exception
    {
        final StubEngine engine = new StubEngine(List.of(SUBMISSION, "sch:Schema"), "s1");
        withParameter("quick", "diabetes");
        withParameter("allowedResourceTypes", SUBMISSION);
        withEngines(engine);
        this.servlet.doGet(this.request, this.response);
        Assertions.assertEquals(List.of(SUBMISSION), engine.searchedTypes);
    }

    @Test
    public void withoutARestrictionAnEngineSearchesEverythingItCan() throws Exception
    {
        final StubEngine engine = new StubEngine(List.of(SUBMISSION, "sch:Schema"), "s1");
        withParameter("quick", "diabetes");
        withEngines(engine);
        this.servlet.doGet(this.request, this.response);
        Assertions.assertEquals(List.of(SUBMISSION, "sch:Schema"), engine.searchedTypes);
    }

    @Test
    public void anEmptyTypeRestrictionIsNoRestriction() throws Exception
    {
        final StubEngine engine = new StubEngine(List.of(SUBMISSION), "s1");
        withParameter("quick", "diabetes");
        Mockito.when(this.request.getParameterValues("allowedResourceTypes")).thenReturn(new String[0]);
        withEngines(engine);
        this.servlet.doGet(this.request, this.response);
        Assertions.assertEquals(List.of(SUBMISSION), engine.searchedTypes);
    }

    @Test
    public void quickSearchResultsOutsideThePageAreSkippedNotSerialized() throws Exception
    {
        final StubEngine engine = new StubEngine(List.of(SUBMISSION), "s1", "s2", "s3");
        withParameter("quick", "diabetes");
        withParameter("offset", "1");
        withParameter("limit", "1");
        withEngines(engine);
        this.servlet.doGet(this.request, this.response);
        final JsonObject result = getResponseJson();
        Assertions.assertEquals(1, result.getJsonArray(ROWS).size());
        Assertions.assertEquals("s2", result.getJsonArray(ROWS).getJsonObject(0).getString("name"));
        Assertions.assertEquals(3, result.getInt(TOTAL));
        // The two results that are only counted are never turned into JSON
        Assertions.assertEquals(List.of("s2"), engine.served);
    }

    @Test
    public void anEngineIsNotAskedForMoreResultsThanCanBeUsed() throws Exception
    {
        final StubEngine engine = new StubEngine(List.of(SUBMISSION), "s1");
        withParameter("quick", "diabetes");
        withParameter("limit", "5");
        withEngines(engine);
        this.servlet.doGet(this.request, this.response);
        // A whole lookahead of pages of five, plus the one result that shows the total is not exact
        Assertions.assertEquals(PaginatedJsonResponse.LOOKAHEAD_PAGES * 5 + 1, engine.maxResults);
    }

    @Test
    public void enginesAreNotAskedOnceThePageIsFull() throws Exception
    {
        final StubEngine first = new StubEngine(List.of(SUBMISSION),
            IntStream.rangeClosed(1, (int) (PaginatedJsonResponse.LOOKAHEAD_PAGES * 5 + 10))
                .mapToObj(i -> "s" + i).toArray(String[]::new));
        final StubEngine second = new StubEngine(List.of("sch:Schema"), "sc1");
        withParameter("quick", "diabetes");
        withParameter("limit", "5");
        withEngines(first, second);
        this.servlet.doGet(this.request, this.response);
        Assertions.assertNull(second.searchedTypes);
        Assertions.assertTrue(getResponseJson().getBoolean("totalIsApproximate"));
    }

    @Test
    public void quickSearchWithoutAnyEngineReturnsNothing() throws Exception
    {
        withParameter("quick", "diabetes");
        setEngines(null);
        this.servlet.doGet(this.request, this.response);
        Assertions.assertEquals(0, getResponseJson().getJsonArray(ROWS).size());
    }

    @Test
    public void aJoinReadsThePathOfTheFirstSelector() throws Exception
    {
        // Asking a row for its path without naming a selector throws as soon as the query has more than one, which
        // every join has, so it used to be the first row of a join that failed the whole request
        withParameter(QUERY, "select * from [sub:Submission] as a inner join [sub:Review] as b on"
            + " isdescendantnode(b, a)");
        mockJoinResults("a", "/Submissions/s1", "/Submissions/s1", "/Submissions/s2");
        this.servlet.doGet(this.request, this.response);
        final JsonObject result = getResponseJson();
        // The same node reached twice by the join is returned once
        Assertions.assertEquals(2, result.getJsonArray(ROWS).size());
        Assertions.assertEquals(2, result.getInt(TOTAL));
        Assertions.assertFalse(result.containsKey("error"));
    }

    @Test
    public void aRowWhosePathCannotBeReadIsSkipped() throws Exception
    {
        withParameter(QUERY, "select * from [sub:Submission] as n");
        mockNodeResults("/Submissions/s1", null, "/Submissions/s2");
        this.servlet.doGet(this.request, this.response);
        final JsonObject result = getResponseJson();
        // The unreadable row is left out, and the rest of the results are still served
        Assertions.assertEquals(2, result.getJsonArray(ROWS).size());
        Assertions.assertEquals(2, result.getInt(TOTAL));
    }

    @Test
    public void aSelectorThatMatchedNoNodeIsSkipped() throws Exception
    {
        // What an outer join gives for the side that didn't match: a row, but no node on that selector
        withParameter(QUERY, "select * from [sub:Submission] as a left outer join [sub:Review] as b on"
            + " isdescendantnode(b, a)");
        mockJoinResults("a", "/Submissions/s1", null);
        this.servlet.doGet(this.request, this.response);
        final JsonObject result = getResponseJson();
        Assertions.assertEquals(1, result.getJsonArray(ROWS).size());
        Assertions.assertEquals("/Submissions/s1", result.getJsonArray(ROWS).getJsonObject(0).getString("path"));
    }

    @Test
    public void aBinaryColumnIsNotReadIntoTheResponse() throws Exception
    {
        // Reading a binary as a string reads all of it, and a statement may select the data of every file there is
        withParameter(QUERY, "select f.[jcr:data] from [nt:resource] as f");
        withParameter("rawResults", "true");
        final Row row = Mockito.mock(Row.class);
        Mockito.when(row.getPath("f")).thenReturn("/uploads/scan.pdf");
        final Value binary = Mockito.mock(Value.class);
        Mockito.when(binary.getType()).thenReturn(PropertyType.BINARY);
        Mockito.when(row.getValue("f.jcr:data")).thenReturn(binary);
        mockResults(singleRow(row), new String[] { "f" }, new String[] { "f.jcr:data" });

        this.servlet.doGet(this.request, this.response);
        final JsonObject result = getResponseJson();
        final JsonObject serialized = result.getJsonArray(ROWS).getJsonObject(0);
        Assertions.assertEquals("/uploads/scan.pdf", serialized.getString("f"));
        Assertions.assertTrue(serialized.isNull("f.jcr:data"));
        Mockito.verify(binary, Mockito.never()).getString();
    }

    @Test
    public void aFailureWhileReadingTheResultsStillLeavesParsableJson() throws Exception
    {
        withParameter(QUERY, "select * from [sub:Submission] as n");
        final RowIterator rows = Mockito.mock(RowIterator.class);
        final Deque<String> remaining = new ArrayDeque<>(List.of("/Submissions/s1"));
        Mockito.when(rows.hasNext()).thenAnswer(invocation -> true);
        Mockito.when(rows.nextRow()).thenAnswer(invocation -> {
            if (remaining.isEmpty()) {
                throw new RepositoryException("The session went away");
            }
            final Row row = Mockito.mock(Row.class);
            Mockito.when(row.getPath()).thenReturn(remaining.removeFirst());
            return row;
        });
        mockResults(rows, new String[] { "n" }, new String[] { "n.jcr:path" });

        this.servlet.doGet(this.request, this.response);
        // Whatever was read before the failure is still a response the client can parse, and it says it is partial
        final JsonObject result = getResponseJson();
        Assertions.assertEquals(1, result.getJsonArray(ROWS).size());
        Assertions.assertTrue(result.getBoolean("partial"));
        Assertions.assertEquals("Failed to read all the results", result.getString("error"));
    }

    @Test
    public void aQueryTheRepositoryCannotMakeSenseOfIsABadRequest() throws Exception
    {
        // What the repository raises for a full-text expression it parsed but cannot interpret
        withParameter(QUERY, "select * from [nt:base] as n where contains(n.*, '\"')");
        mockNodeResults();
        Mockito.when(this.queryManager.createQuery(Mockito.anyString(), Mockito.eq(Query.JCR_SQL2)))
            .thenThrow(new IllegalArgumentException("Query: */*(*)query/*(*)/"));
        this.servlet.doGet(this.request, this.response);
        assertError(SlingJakartaHttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    public void anApostropheIsEscapedOutOfTheFullTextExpression() throws Exception
    {
        // An apostrophe opens a quoted phrase for the full-text parser. The statement's own escaping doubles it, but
        // parsing the statement undoes that again, so it has to be escaped for the full-text parser as well
        withParameter("fulltext", "'tis");
        mockNodeResults();
        this.servlet.doGet(this.request, this.response);
        Assertions.assertEquals("select n.* from [nt:base] as n where contains(n.*, '\\''tis')" + OUTSIDE_SYSTEM,
            executedStatement());
    }

    @Test
    public void aStatementIsLoggedOnASingleLine()
    {
        // In fulltext mode the statement carries the text the user typed, so a line break in it would otherwise let
        // a client write log entries of its own
        Assertions.assertEquals("select * from [nt:base] where a = 'b'",
            SearchServlet.forLog("select * from [nt:base]\nwhere a = 'b'"));
    }

    @Test
    public void aLongStatementIsCutDownBeforeItIsLogged()
    {
        final String statement = "select * from [nt:base] where title = '" + "a".repeat(600) + "'";
        final String logged = SearchServlet.forLog(statement);
        Assertions.assertEquals(503, logged.length());
        Assertions.assertTrue(logged.endsWith("..."));
    }

    @Test
    public void anEngineReturningNoResultsAtAllIsPassedOver() throws Exception
    {
        // The SPI says the results are never null, so an engine returning one is broken; it is handled the same way
        // as any other engine that misbehaves, rather than taking the response down
        withParameter("quick", "diabetes");
        final QuickSearchEngine broken = Mockito.mock(QuickSearchEngine.class);
        Mockito.when(broken.getSupportedTypes()).thenReturn(List.of(SUBMISSION));
        Mockito.when(broken.quickSearch(Mockito.any(), Mockito.any())).thenReturn(null);
        final StubEngine working = new StubEngine(List.of("sub:Review"), "review");
        withEngines(broken, working);

        this.servlet.doGet(this.request, this.response);
        final JsonObject result = getResponseJson();
        Assertions.assertEquals(1, result.getJsonArray(ROWS).size());
        Assertions.assertFalse(result.containsKey("error"));
        Assertions.assertEquals(List.of("review"), working.served);
    }

    @Test
    public void anEngineThatFailsDoesNotFailTheSearch() throws Exception
    {
        withParameter("quick", "diabetes");
        final QuickSearchEngine broken = Mockito.mock(QuickSearchEngine.class);
        Mockito.when(broken.getSupportedTypes()).thenReturn(List.of(SUBMISSION));
        Mockito.when(broken.quickSearch(Mockito.any(), Mockito.any()))
            .thenThrow(new IllegalStateException("Not today"));
        final StubEngine working = new StubEngine(List.of("sub:Review"), "review");
        withEngines(broken, working);

        this.servlet.doGet(this.request, this.response);
        final JsonObject result = getResponseJson();
        Assertions.assertEquals(1, result.getJsonArray(ROWS).size());
        Assertions.assertFalse(result.containsKey("error"));
        Assertions.assertEquals(List.of("review"), working.served);
    }

    @Test
    public void resultsAreClosedEvenWhenNotReadToTheEnd() throws Exception
    {
        // Stopping early is what happens for any search with more matches than fit on a page, so an engine holding a
        // session for the search has to be told about it then, not only when its results run out
        withParameter("quick", "diabetes");
        withParameter("limit", "1");
        // More matches than the paginator will ever count for a page this size, so the reading stops part-way
        final StubEngine engine = new StubEngine(List.of(SUBMISSION),
            IntStream.rangeClosed(1, (int) (PaginatedJsonResponse.LOOKAHEAD_PAGES + 10))
                .mapToObj(i -> "r" + i).toArray(String[]::new));
        withEngines(engine);

        this.servlet.doGet(this.request, this.response);
        final JsonObject result = getResponseJson();
        Assertions.assertEquals(1, result.getJsonArray(ROWS).size());
        Assertions.assertTrue(result.getBoolean("totalIsApproximate"));
        Assertions.assertTrue(engine.closed);
    }

    @Test
    public void anEngineThatCannotEvenLetGoDoesNotFailTheSearch() throws Exception
    {
        withParameter("quick", "diabetes");
        final QuickSearchEngine clingy = Mockito.mock(QuickSearchEngine.class);
        Mockito.when(clingy.getSupportedTypes()).thenReturn(List.of(SUBMISSION));
        final QuickSearchEngine.Results results = Mockito.mock(QuickSearchEngine.Results.class);
        Mockito.when(results.hasNext()).thenReturn(false);
        Mockito.doThrow(new IllegalStateException("Mine")).when(results).close();
        Mockito.when(clingy.quickSearch(Mockito.any(), Mockito.any())).thenReturn(results);
        final StubEngine working = new StubEngine(List.of("sub:Review"), "review");
        withEngines(clingy, working);

        this.servlet.doGet(this.request, this.response);
        final JsonObject result = getResponseJson();
        Assertions.assertEquals(1, result.getJsonArray(ROWS).size());
        Assertions.assertFalse(result.containsKey("error"));
    }

    @Test
    public void aTypeIsOnlySearchedByTheFirstEngineThatClaimsIt() throws Exception
    {
        // Nothing stops two engines from claiming the same node type, and if both were asked the same node would be
        // returned twice and counted twice, since results from different engines are not deduplicated
        withParameter("quick", "diabetes");
        final StubEngine first = new StubEngine(List.of(SUBMISSION), "found");
        final StubEngine second = new StubEngine(List.of(SUBMISSION), "found again");
        withEngines(first, second);

        this.servlet.doGet(this.request, this.response);
        Assertions.assertEquals(1, getResponseJson().getJsonArray(ROWS).size());
        Assertions.assertEquals(List.of("found"), first.served);
        Assertions.assertNull(second.searchedTypes);
    }

    @Test
    public void anEngineThatFailsDoesNotTakeItsTypesWithIt() throws Exception
    {
        // The first engine returned nothing, so there is nothing for the second one to duplicate; leaving the type
        // claimed by the engine that broke would answer a request that could have been served with nothing at all
        withParameter("quick", "diabetes");
        final QuickSearchEngine broken = Mockito.mock(QuickSearchEngine.class);
        Mockito.when(broken.getSupportedTypes()).thenReturn(List.of(SUBMISSION));
        Mockito.when(broken.quickSearch(Mockito.any(), Mockito.any()))
            .thenThrow(new IllegalStateException("Not today"));
        final StubEngine fallback = new StubEngine(List.of(SUBMISSION), "found anyway");
        withEngines(broken, fallback);

        this.servlet.doGet(this.request, this.response);
        final JsonObject result = getResponseJson();
        Assertions.assertEquals(1, result.getJsonArray(ROWS).size());
        Assertions.assertEquals(List.of(SUBMISSION), fallback.searchedTypes);
        Assertions.assertEquals(List.of("found anyway"), fallback.served);
    }

    @Test
    public void anEngineIsStillAskedForTheTypesNoOneElseClaimed() throws Exception
    {
        withParameter("quick", "diabetes");
        final StubEngine first = new StubEngine(List.of(SUBMISSION), "one");
        final StubEngine second = new StubEngine(List.of(SUBMISSION, "sub:Review"), "two");
        withEngines(first, second);

        this.servlet.doGet(this.request, this.response);
        Assertions.assertEquals(2, getResponseJson().getJsonArray(ROWS).size());
        Assertions.assertEquals(List.of("sub:Review"), second.searchedTypes);
    }

    /**
     * The statement the servlet actually ran, as opposed to the decorated one it asked the plan for.
     *
     * @return a JCR-SQL2 statement
     */
    private String executedStatement()
    {
        return this.statements.stream().filter(statement -> !statement.startsWith("explain ")).findFirst()
            .orElseThrow();
    }

    private void withParameter(final String name, final String... values)
    {
        Mockito.when(this.request.getParameter(name)).thenReturn(values[0]);
        Mockito.when(this.request.getParameterValues(name)).thenReturn(values);
    }

    private void withEngines(final QuickSearchEngine... engines)
    {
        setEngines(List.of(engines));
    }

    private void setEngines(final List<QuickSearchEngine> engines)
    {
        try {
            final Field field = SearchServlet.class.getDeclaredField("searchEngines");
            field.setAccessible(true);
            field.set(this.servlet, engines);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Mocks the query infrastructure to return rows matching the given node paths, for a query with a single
     * selector.
     *
     * @param paths the paths of the nodes the query matches; a {@code null} stands for a row whose path cannot be
     *            read
     */
    private void mockNodeResults(final String... paths) throws RepositoryException
    {
        final Iterator<String> iterator = Arrays.asList(paths).iterator();
        final RowIterator rows = Mockito.mock(RowIterator.class);
        Mockito.when(rows.hasNext()).thenAnswer(invocation -> iterator.hasNext());
        Mockito.when(rows.nextRow()).thenAnswer(invocation -> {
            final Row row = Mockito.mock(Row.class);
            final String path = iterator.next();
            if (path == null) {
                Mockito.when(row.getPath()).thenThrow(new RepositoryException("This row cannot be read"));
            } else {
                Mockito.when(row.getPath()).thenReturn(path);
            }
            return row;
        });
        mockResults(rows, new String[] { "n" }, new String[] { "n.jcr:path" });
    }

    /**
     * Mocks the query infrastructure to return rows of a query with two selectors, as a join has. Asking such a row
     * for its path without naming a selector is an error, which is what the repository does too.
     *
     * @param selector the name of the first selector, the one holding the matched node
     * @param paths the path that selector holds in each row; a {@code null} stands for a row where it matched
     *            nothing, as an outer join allows
     */
    private void mockJoinResults(final String selector, final String... paths) throws RepositoryException
    {
        final Iterator<String> iterator = Arrays.asList(paths).iterator();
        final RowIterator rows = Mockito.mock(RowIterator.class);
        Mockito.when(rows.hasNext()).thenAnswer(invocation -> iterator.hasNext());
        Mockito.when(rows.nextRow()).thenAnswer(invocation -> {
            final Row row = Mockito.mock(Row.class);
            Mockito.when(row.getPath()).thenThrow(new RepositoryException("More than one selector"));
            Mockito.when(row.getPath(selector)).thenReturn(iterator.next());
            return row;
        });
        mockResults(rows, new String[] { selector, "b" }, new String[] { selector + ".jcr:path" });
    }

    /** Mocks the query infrastructure to return one row with one selector and one column. */
    private void mockRawResults() throws RepositoryException
    {
        final Row row = Mockito.mock(Row.class);
        Mockito.when(row.getPath("f")).thenReturn("/Submissions/s1");
        final Value value = Mockito.mock(Value.class);
        Mockito.when(value.getString()).thenReturn("value of subject");
        Mockito.when(row.getValue("f.subject")).thenReturn(value);
        mockResults(singleRow(row), new String[] { "f" }, new String[] { "f.subject" });
    }

    private RowIterator singleRow(final Row row)
    {
        final Deque<Row> remaining = new ArrayDeque<>(List.of(row));
        final RowIterator rows = Mockito.mock(RowIterator.class);
        Mockito.when(rows.hasNext()).thenAnswer(invocation -> !remaining.isEmpty());
        Mockito.when(rows.nextRow()).thenAnswer(invocation -> remaining.removeFirst());
        return rows;
    }

    /**
     * Mocks the query manager: an {@code explain} statement returns the configured plan, anything else returns the
     * given rows. Every statement is recorded in {@link #statements}.
     */
    private void mockResults(final RowIterator rows, final String[] selectors, final String[] columns)
        throws RepositoryException
    {
        final QueryResult result = Mockito.mock(QueryResult.class);
        Mockito.when(result.getRows()).thenReturn(rows);
        Mockito.when(result.getSelectorNames()).thenReturn(selectors);
        Mockito.when(result.getColumnNames()).thenReturn(columns);
        final Query query = Mockito.mock(Query.class);
        Mockito.when(query.execute()).thenReturn(result);

        final Query explain = Mockito.mock(Query.class);
        Mockito.when(explain.execute()).thenAnswer(invocation -> {
            final RowIterator planRows = Mockito.mock(RowIterator.class);
            if (this.planColumns == null) {
                Mockito.when(planRows.hasNext()).thenReturn(false);
            } else {
                final Value[] values = new Value[this.planColumns.length];
                for (int i = 0; i < values.length; ++i) {
                    values[i] = Mockito.mock(Value.class);
                    Mockito.when(values[i].getString()).thenReturn(this.planColumns[i]);
                }
                final Row planRow = Mockito.mock(Row.class);
                Mockito.when(planRow.getValues()).thenReturn(values);
                Mockito.when(planRows.hasNext()).thenReturn(true);
                Mockito.when(planRows.nextRow()).thenReturn(planRow);
            }
            final QueryResult planResult = Mockito.mock(QueryResult.class);
            Mockito.when(planResult.getRows()).thenReturn(planRows);
            return planResult;
        });

        Mockito.when(this.queryManager.createQuery(Mockito.anyString(), Mockito.eq(Query.JCR_SQL2)))
            .thenAnswer(invocation -> {
                final String statement = invocation.getArgument(0, String.class);
                this.statements.add(statement);
                return statement.startsWith("explain ") ? explain : query;
            });
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

    /**
     * An engine returning a fixed list of results, recording what it was asked for and which results were actually
     * read, so that a test can tell a served result from a skipped one.
     *
     * @since 0.1.0
     */
    private static final class StubEngine implements QuickSearchEngine
    {
        private final List<String> supportedTypes;

        private final String[] names;

        private List<String> searchedTypes;

        private long maxResults;

        private boolean closed;

        private final List<String> served = new ArrayList<>();

        StubEngine(final List<String> supportedTypes, final String... names)
        {
            this.supportedTypes = supportedTypes;
            this.names = names;
        }

        @Override
        public List<String> getSupportedTypes()
        {
            return this.supportedTypes;
        }

        @Override
        public Results quickSearch(final SearchParameters query, final ResourceResolver resourceResolver)
        {
            this.searchedTypes = query.getResourceTypes();
            this.maxResults = query.getMaxResults();
            final Deque<String> remaining = new ArrayDeque<>(List.of(this.names));
            return new Results()
            {
                @Override
                public boolean hasNext()
                {
                    return !remaining.isEmpty();
                }

                @Override
                public JsonObject next()
                {
                    if (remaining.isEmpty()) {
                        throw new NoSuchElementException();
                    }
                    final String name = remaining.removeFirst();
                    StubEngine.this.served.add(name);
                    return Json.createObjectBuilder().add("name", name).build();
                }

                @Override
                public void skip()
                {
                    remaining.removeFirst();
                }

                @Override
                public void close()
                {
                    StubEngine.this.closed = true;
                }
            };
        }
    }
}

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

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.query.InvalidQueryException;
import javax.jcr.query.Query;
import javax.jcr.query.QueryResult;
import javax.jcr.query.Row;
import javax.jcr.query.RowIterator;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.stream.JsonGenerator;
import jakarta.servlet.Servlet;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingJakartaAllMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.search.api.SearchContext;
import io.uhndata.iap.search.api.SearchContextFactory;
import io.uhndata.iap.search.spi.QuickSearchEngine;
import io.uhndata.iap.utils.JsonResponses;
import io.uhndata.iap.utils.PaginatedJsonResponse;

/**
 * Searches the repository and returns the results as JSON. It is registered on the {@code data/Search} resource type
 * with the {@code json} extension, so it serves {@code /search.json}.
 *
 * <p>
 * One of these parameters says what to look for:
 * </p>
 * <ul>
 * <li>{@code query}: a JCR-SQL2 statement.</li>
 * <li>{@code fulltext}: a text to look for anywhere in the content, except in Oak's own {@code /jcr:system} and in
 * {@code /SystemWorkflows}.</li>
 * <li>{@code quick}: a text for the registered {@link QuickSearchEngine quick search engines} to match.</li>
 * </ul>
 *
 * <p>
 * If more than one is sent, the first that is not blank, in the order above, is used. If none is sent, the result is
 * empty. The other parameters are:
 * </p>
 * <ul>
 * <li>{@code offset}, {@code limit}, {@code resourceSelectors} and {@code req}, as for every paginated response; see
 * {@link PaginatedJsonResponse}.</li>
 * <li>{@code rawResults=true}: return the columns the query selected instead of the matched nodes, for a
 * {@code query} or a {@code fulltext} search. A query that reports on itself matches no nodes, and always gets raw
 * results.</li>
 * <li>{@code doNotEscapeQuery=true}: read {@code fulltext} as a full-text expression, operators and all, instead of
 * as a text to find as it is.</li>
 * <li>{@code allowedResourceTypes}: repeatable, the node types a {@code quick} search may return; by default, every
 * type the registered engines can search.</li>
 * </ul>
 *
 * <p>
 * The query runs in the session of the user making the request, so a search returns only what that user may read.
 * </p>
 *
 * <p>
 * {@code GET} and {@code POST} run the same search, with the parameters in the query string or in a form-encoded
 * body. A search changes nothing. {@code POST} keeps the search terms out of access logs and browser history.
 * </p>
 *
 * <p>
 * A {@code query} may name a referenced node by its path where the property holds a UUID, as in
 * {@code a.question = '/Schemas/consent/v1/hasCapacity'}; see {@link QueryPathResolver}. It may also start with
 * {@code explain} or {@code measure}, which report on the query instead of running it.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
// The extension is required. Without it the servlet registers as GET.servlet, and Sling's default JSON renderer
// answers /search.json with the search node itself.
@SlingServletResourceTypes(resourceTypes = { "data/Search" }, methods = { "GET", "POST" },
    extensions = { "json" })
public class SearchServlet extends SlingJakartaAllMethodsServlet
{
    private static final long serialVersionUID = -6002540580101127991L;

    private static final Logger LOGGER = LoggerFactory.getLogger(SearchServlet.class);

    /** The selector the generated queries use for the node being matched. */
    private static final String NODE_SELECTOR = "n";

    /**
     * The tree Oak keeps its own records in, such as version storage and the node type registry. A full-text search
     * leaves it out.
     */
    private static final String SYSTEM_TREE = "/jcr:system";

    /**
     * The system workflows, which define how the platform handles its own content. A full-text search leaves them
     * out.
     */
    private static final String SYSTEM_WORKFLOWS = "/SystemWorkflows";

    /**
     * Keeps a full-text search out of the {@link #SYSTEM_TREE} and the {@link #SYSTEM_WORKFLOWS}. Each tree's own node
     * is excluded as well as its descendants: {@code isdescendantnode} does not match the node itself, and
     * {@code /jcr:system} has a primary type that answers a search for "system".
     */
    private static final String OUTSIDE_MACHINERY = outside(SYSTEM_TREE) + outside(SYSTEM_WORKFLOWS);

    /**
     * The characters with a meaning of their own in a full-text expression. The apostrophe is one: it opens a quoted
     * phrase, as the double quote does. The grammar applies to whatever the bind variable holds, so binding does not
     * make escaping unnecessary.
     */
    private static final Pattern FULL_TEXT_SPECIAL = Pattern.compile("([\\\\+\\-&|!(){}\\[\\]^\"'~*?:/])");

    /**
     * Matches a statement that reports on itself instead of matching nodes: {@code explain} gives the plan Oak would
     * run, and {@code measure} how much it had to scan. Both together are written {@code explain measure}.
     */
    private static final Pattern REPORTING_QUERY =
        Pattern.compile("^\\s*+(explain|measure)\\s", Pattern.CASE_INSENSITIVE);

    /** The bind variable a generated full-text statement holds its expression in. */
    private static final String FULL_TEXT_VARIABLE = "text";

    /** The registered quick search engines. Transient: a servlet is serializable, and a service is not. */
    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC,
        policyOption = ReferencePolicyOption.GREEDY)
    private transient volatile List<QuickSearchEngine> searchEngines;

    @Override
    public void doGet(final SlingJakartaHttpServletRequest request, final SlingJakartaHttpServletResponse response)
        throws IOException
    {
        search(request, response);
    }

    @Override
    public void doPost(final SlingJakartaHttpServletRequest request, final SlingJakartaHttpServletResponse response)
        throws IOException
    {
        search(request, response);
    }

    /**
     * Runs the search a request asks for, whichever method it uses, and answers with an error if the query cannot
     * run.
     *
     * @param request the current request
     * @param response the HTTP response to write into
     * @throws IOException if writing the response fails
     */
    private void search(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        try {
            writeResponse(request, response);
        } catch (final InvalidQueryException e) {
            JsonResponses.writeError(response, SlingJakartaHttpServletResponse.SC_BAD_REQUEST,
                "Invalid query: " + e.getMessage());
        } catch (final IllegalArgumentException e) {
            // What Oak throws for a malformed full-text expression, among others
            JsonResponses.writeError(response, SlingJakartaHttpServletResponse.SC_BAD_REQUEST,
                "Invalid query: " + e.getMessage());
        } catch (final RepositoryException e) {
            LOGGER.warn("Failed to execute search query: {}", e.getMessage(), e);
            JsonResponses.writeError(response, SlingJakartaHttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Failed to execute query");
        }
    }

    /**
     * Runs the requested search and writes the results. The query is executed before anything is written, so a query
     * that cannot run is answered with an error status.
     *
     * <p>
     * Oak returns a lazy result set, so reading the rows can still fail after part of the response has gone out. Such
     * a failure ends the results and is reported in the summary, and the response stays a well-formed document.
     * </p>
     *
     * @param request the current request
     * @param response the HTTP response to write into
     * @throws IOException if writing the response fails
     * @throws RepositoryException if the query is invalid or cannot be executed
     */
    // A page of results is content, streamed as it is read
    @SuppressWarnings("checkstyle:directResponseWriter")
    private void writeResponse(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException, RepositoryException
    {
        final Found found = this.runRequestedQuery(request);
        final String quick = request.getParameter("quick");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        // Closing the generator closes the writer too
        try (JsonGenerator json = Json.createGenerator(response.getWriter())) {
            json.writeStartObject();
            json.writeStartArray("rows");
            final PaginatedJsonResponse page = PaginatedJsonResponse.forRequest(json, request);
            String error = null;
            try {
                if (found.results() != null) {
                    // The rows of a reporting query describe the query, and hold no node to serialize
                    if (found.reporting() || "true".equals(request.getParameter("rawResults"))) {
                        writeRawResults(page, found.results());
                    } else {
                        writeNodeResults(page, request, found.results());
                    }
                } else if (StringUtils.isNotBlank(quick)) {
                    writeQuickResults(page, request, quick);
                }
            } catch (final RepositoryException | RuntimeException e) {
                // Unchecked too: Oak reports a read or memory limit reached with an unchecked exception
                LOGGER.warn("Failed to read the results of a search: {}", e.getMessage(), e);
                error = "Failed to read all the results";
            }
            json.writeEnd();
            page.writeSummary(request.getParameter("req"), error);
            json.writeEnd().flush();
        }
    }

    /**
     * Runs the query a request names: a JCR-SQL2 statement in {@code query}, or a full-text search in
     * {@code fulltext}.
     *
     * @param request the current request
     * @return what the query found; its results are {@code null} if the request names neither
     * @throws RepositoryException if the query is invalid or cannot be executed
     */
    private Found runRequestedQuery(final SlingJakartaHttpServletRequest request) throws RepositoryException
    {
        final String jcrQuery = request.getParameter("query");
        if (StringUtils.isNotBlank(jcrQuery)) {
            final Session session = session(request);
            final String statement = QueryPathResolver.resolveReferencePaths(session, jcrQuery);
            final boolean reporting = REPORTING_QUERY.matcher(statement).find();
            // A reporting query gets no plan check. Oak does not parse the explain of an explain.
            return new Found(this.runQuery(session, new BoundStatement(statement, Map.of()), !reporting), reporting);
        }
        final String fullText = request.getParameter("fulltext");
        if (StringUtils.isNotBlank(fullText)) {
            return new Found(this.runQuery(session(request), this.fullTextStatement(request, fullText), true), false);
        }
        return new Found(null, false);
    }

    /**
     * The session of the user making the request, which every query runs in.
     *
     * @param request the current request
     * @return the session behind the request's resource resolver
     * @throws RepositoryException if the resource resolver has no JCR session
     */
    private static Session session(final SlingJakartaHttpServletRequest request) throws RepositoryException
    {
        final Session session = request.getResourceResolver().adaptTo(Session.class);
        if (session == null) {
            throw new RepositoryException("The resource resolver is not backed by a JCR session");
        }
        return session;
    }

    /**
     * Executes a JCR-SQL2 statement.
     *
     * @param session the session to run the statement in
     * @param bound the statement to execute, with the values of any variables it names
     * @param checkPlan whether to report the statement if it has no index to use
     * @return the query results
     * @throws RepositoryException if the statement is invalid
     */
    private QueryResult runQuery(final Session session, final BoundStatement bound, final boolean checkPlan)
        throws RepositoryException
    {
        // Parsed before the plan check, so a syntax error quotes the client's statement, not the explain built on it
        final Query query = bound.createQuery(session);
        if (checkPlan) {
            QueryPlanChecker.warnIfUnindexed(session, bound);
        }
        return query.execute();
    }

    /**
     * Builds the statement that looks for a text in every node type, outside the {@link #SYSTEM_TREE} and the
     * {@link #SYSTEM_WORKFLOWS}.
     *
     * <p>
     * The text is stripped first, since a full-text expression must start with a term.
     * </p>
     *
     * @param request the current request
     * @param query the text to look for, not blank
     * @return the JCR-SQL2 statement, with the expression bound
     */
    private BoundStatement fullTextStatement(final SlingJakartaHttpServletRequest request, final String query)
    {
        final String text = query.strip();
        // Whether to find the text as it is, rather than read it as a full-text expression
        final boolean verbatim = !"true".equals(request.getParameter("doNotEscapeQuery"));
        // The expression is bound, never written into a string literal, so even an unescaped one cannot end the
        // literal and continue the statement
        final String expression = verbatim ? FULL_TEXT_SPECIAL.matcher(text).replaceAll("\\\\$1") : text;
        return new BoundStatement(
            String.format("select %1$s.* from [nt:base] as %1$s where contains(%1$s.*, $%2$s)%3$s", NODE_SELECTOR,
                FULL_TEXT_VARIABLE, OUTSIDE_MACHINERY),
            Map.of(FULL_TEXT_VARIABLE, expression));
    }

    /**
     * The condition that keeps the matched node out of a tree, the tree's own node included.
     *
     * @param tree the path of the tree's root
     * @return a condition to append to a statement's {@code where} clause
     */
    private static String outside(final String tree)
    {
        return " and not issamenode(" + NODE_SELECTOR + ", '" + tree + "')"
            + " and not isdescendantnode(" + NODE_SELECTOR + ", '" + tree + "')";
    }

    /**
     * Writes the nodes matched by the query, serialized as the client asked for them.
     *
     * @param page the paginator for the requested page
     * @param request the current request
     * @param results the query results
     * @throws RepositoryException if reading the query results fails
     */
    private void writeNodeResults(final PaginatedJsonResponse page, final SlingJakartaHttpServletRequest request,
        final QueryResult results) throws RepositoryException
    {
        final String selectors = PaginatedJsonResponse.getResourceSelectors(request);
        final ResourceResolver resolver = request.getResourceResolver();
        // Asking a row for a path without naming a selector throws when the query has more than one, as every join
        // has. The first selector is the node the query is about.
        final String[] selectorNames = results.getSelectorNames();
        final String selector = selectorNames.length <= 1 ? null : selectorNames[0];
        final RowIterator rows = results.getRows();
        boolean more = true;
        while (rows.hasNext() && more) {
            // The path alone is cheaper than loading the node. A join returns the same node once per matching
            // combination, and the page drops the duplicates.
            final String path = readPath(rows.nextRow(), selector);
            if (path == null) {
                continue;
            }
            more = page.offer(path, () -> serializeNode(resolver, path, selectors));
        }
    }

    /**
     * Reads the path of the node a result row is about. A row whose path cannot be read is left out, and the search
     * goes on.
     *
     * @param row the row to read
     * @param selector the name of the selector holding the node; {@code null} if the query has only one
     * @return the path of the matched node; {@code null} if the row cannot be read, or if the selector matched
     *         nothing, as an outer join allows
     */
    private String readPath(final Row row, final String selector)
    {
        try {
            return selector == null ? row.getPath() : row.getPath(selector);
        } catch (final RepositoryException e) {
            LOGGER.warn("Skipping a search result whose path cannot be read: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Writes the query results as they are: one object per row, holding the path of each selector and the value of
     * each column the query selected.
     *
     * <p>
     * The columns are read <em>after</em> the rows. For a query that reports on itself, Oak's
     * {@code getColumnNames()} names the columns of the statement being reported on until the rows have been asked
     * for. Only then does it name the columns its rows hold. No row has a value for the first set, and every row
     * would be dropped.
     * </p>
     *
     * @param page the paginator for the requested page
     * @param results the query results
     * @throws RepositoryException if reading the query results fails
     */
    private void writeRawResults(final PaginatedJsonResponse page, final QueryResult results)
        throws RepositoryException
    {
        final String[] selectors = results.getSelectorNames();
        final RowIterator rows = results.getRows();
        final String[] columns = results.getColumnNames();
        boolean more = true;
        while (rows.hasNext() && more) {
            final Row row = rows.nextRow();
            // No duplicate check: two identical rows may both be meant
            more = page.offer(null, () -> RawResultSerializer.serialize(row, selectors, columns));
        }
    }

    /**
     * Writes the matches found by the quick search engines. It asks each engine that supports a requested node type,
     * until the page wants no more results. Several engines can search the same type.
     *
     * <p>
     * An engine returns serialized matches, not paths, so the matches of different engines are not compared. Two
     * engines that find the same node both report it.
     * </p>
     *
     * @param page the paginator for the requested page
     * @param request the current request
     * @param query the text to look for
     */
    private void writeQuickResults(final PaginatedJsonResponse page, final SlingJakartaHttpServletRequest request,
        final String query)
    {
        final String[] parameter = request.getParameterValues("allowedResourceTypes");
        final String[] requested = parameter == null || parameter.length == 0 ? null : parameter;
        final List<QuickSearchEngine> engines = this.searchEngines;
        if (engines == null) {
            return;
        }
        for (final QuickSearchEngine engine : engines) {
            if (page.isFull()) {
                break;
            }
            // Every call into an engine is inside this guard, the questions about its types included, so a failing
            // engine loses only its own results
            try {
                askEngine(page, request, query, engine, requested);
            } catch (final RuntimeException e) {
                LOGGER.warn("The quick search engine {} failed: {}", engine.getClass().getName(), e.getMessage(), e);
            }
        }
    }

    /**
     * Asks one engine for the matches of every type it supports that the client asked about.
     *
     * @param page the paginator for the requested page
     * @param request the current request
     * @param query the text to look for
     * @param engine the engine to ask
     * @param requested the node types the client restricted the search to; {@code null} for no restriction
     */
    private void askEngine(final PaginatedJsonResponse page, final SlingJakartaHttpServletRequest request,
        final String query, final QuickSearchEngine engine, final String[] requested)
    {
        final List<String> types = requested == null ? List.copyOf(engine.getSupportedTypes())
            : Arrays.stream(requested).filter(engine::isTypeSupported).toList();
        if (types.isEmpty()) {
            return;
        }

        final SearchContext context = SearchContextFactory.newSearchContext()
            .withQuery(query)
            .withResourceTypes(types)
            .withMaxResults(page.getRemainingCapacity())
            .withResourceResolver(request.getResourceResolver())
            .build();
        QuickSearchEngine.Results results = null;
        try {
            // An engine that returns null fails here with a NullPointerException, caught with its other failures
            results = engine.quickSearch(context);
            boolean more = true;
            while (results.hasNext() && more) {
                more = page.offer(null, results::next, results::skip);
            }
        } finally {
            close(engine, results);
        }
    }

    /**
     * Releases an engine's results, whatever happened while they were being read.
     *
     * @param engine the engine the results came from, for the log message
     * @param results the results to release; {@code null} if the engine returned none
     */
    private static void close(final QuickSearchEngine engine, final QuickSearchEngine.Results results)
    {
        if (results == null) {
            return;
        }
        try {
            results.close();
        } catch (final RuntimeException e) {
            LOGGER.warn("The quick search engine {} failed to release its results: {}", engine.getClass().getName(),
                e.getMessage(), e);
        }
    }

    /**
     * Serializes one matched node.
     *
     * @param resolver the current resource resolver
     * @param path the path of the node to serialize
     * @param selectors the extra serialization selectors the client asked for, such as {@code .deep}; empty for none
     * @return the serialized node; {@code null} if it cannot be serialized, and then it is left out of the page but
     *         still counted
     */
    private JsonObject serializeNode(final ResourceResolver resolver, final String path, final String selectors)
    {
        try {
            return resolver.resolve(path + selectors).adaptTo(JsonObject.class);
        } catch (final RuntimeException e) {
            LOGGER.warn("Failed to serialize {} for a search: {}", path, e.getMessage(), e);
            return null;
        }
    }

    /**
     * What the query a request names found.
     *
     * @param results the rows; {@code null} if the request names no query
     * @param reporting whether the query reports on itself, so that its rows describe the query and not nodes
     * @version $Id$
     * @since 0.1.0
     */
    private record Found(QueryResult results, boolean reporting)
    {
    }
}

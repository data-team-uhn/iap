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
import io.uhndata.iap.utils.PaginatedJsonResponse;

/**
 * A servlet running a query and returning the results as JSON. It is registered on the {@code data/Search} resource
 * type with the {@code json} extension, so it serves {@code /search.json}.
 *
 * <p>
 * What to look for is taken from the request parameters, one of:
 * </p>
 * <ul>
 * <li>{@code query}, a full query in the JCR-SQL2 syntax</li>
 * <li>{@code fulltext}, a text to look for anywhere in the content, Oak's own {@code /jcr:system} bookkeeping
 * excepted</li>
 * <li>{@code quick}, a text to be matched by the registered
 * {@link QuickSearchEngine quick search engines}</li>
 * </ul>
 *
 * <p>
 * If more than one of these is sent, the first one, in the order above, that is not empty is used and the others are
 * ignored. If none is sent, an empty result is returned. The other parameters are:
 * </p>
 * <ul>
 * <li>{@code offset}, {@code limit}, {@code resourceSelectors} and {@code req}: as for every paginated response, see
 * {@link PaginatedJsonResponse}</li>
 * <li>{@code rawResults=true}: return the columns the query selected, as they are, instead of serializing the nodes
 * the query matched; only meaningful together with {@code query}, and used whether or not it was asked for when the
 * query reports on itself, which leaves no nodes to serialize</li>
 * <li>{@code doNotEscapeQuery=true}: treat the {@code fulltext} input as a full-text expression written by the user,
 * operators and all, instead of as a text to be found verbatim</li>
 * <li>{@code allowedResourceTypes}: repeatable, the node types a {@code quick} search may return; by default every
 * type the registered engines can search</li>
 * </ul>
 *
 * <p>
 * The query runs in the session of the user making the request. A search never returns content that user could not
 * read anyway.
 * </p>
 *
 * <p>
 * {@code GET} and {@code POST} serve the same search, with the parameters read the same way from the query string or
 * from a form-encoded body. A search changes nothing. {@code POST} is offered because search terms can be as
 * sensitive as what they find, and a query string is written to access logs and kept in browser history.
 * </p>
 *
 * <p>
 * A {@code query} may name a referenced node by its path where the UUID it holds is expected, for example
 * {@code a.question = '/Schemas/Consent/1.0/hasCapacity'}; see {@link QueryPathResolver}. It may also start with
 * {@code explain} or {@code measure}, which report on the query instead of running it.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
// The extension is required. Without it this registers as GET.servlet, and Sling's default renderer takes
// .json requests: the search node serializes itself instead of answering the query.
@SlingServletResourceTypes(resourceTypes = { "data/Search" }, methods = { "GET", "POST" },
    extensions = { "json" })
public class SearchServlet extends SlingJakartaAllMethodsServlet
{
    private static final long serialVersionUID = -6002540580101127991L;

    private static final Logger LOGGER = LoggerFactory.getLogger(SearchServlet.class);

    /** The selector the generated queries use for the node being matched. */
    private static final String SELECTOR = "n";

    /**
     * The tree Oak keeps its own bookkeeping in, left out of a generated search.
     *
     * <p>
     * Much of it is a copy of content that is searchable anyway. Checking a node in leaves a frozen copy of all its
     * properties under {@code /jcr:system/jcr:versionStorage}, so a submission edited twenty times would answer a
     * search for its own text twenty-one times over. The node type registry puts every property definition it
     * declares in front of a search for an ordinary word. None of these paths is one the client can use.
     * </p>
     */
    private static final String SYSTEM_TREE = "/jcr:system";

    /**
     * Keeps a generated statement out of the {@link #SYSTEM_TREE}. The tree's own node is named separately from its
     * descendants: {@code isdescendantnode} does not match the node itself, and {@code /jcr:system} carries a primary
     * type that answers a search for "system".
     */
    private static final String OUTSIDE_SYSTEM_TREE =
        " and not issamenode(" + SELECTOR + ", '" + SYSTEM_TREE + "')"
            + " and not isdescendantnode(" + SELECTOR + ", '" + SYSTEM_TREE + "')";

    /**
     * The characters that mean something other than themselves in a full-text expression. The apostrophe is one of
     * them: it opens a quoted phrase. An unescaped one leaves the full-text parser looking for a phrase that
     * never ends. Binding does not help. The grammar is applied to whatever the variable holds.
     */
    private static final Pattern FULL_TEXT_SPECIAL = Pattern.compile("([\\\\+\\-&|!(){}\\[\\]^\"'~*?:/])");

    /**
     * Matches a statement that reports on itself instead of matching nodes: {@code explain} gives the plan Oak would
     * run, {@code measure} how much it had to scan. Either may precede any statement, and both together are allowed.
     */
    private static final Pattern REPORTING_QUERY =
        Pattern.compile("^\\s*+(explain|measure)\\s", Pattern.CASE_INSENSITIVE);

    /** The bind variable a generated full-text statement holds its expression in. */
    private static final String FULL_TEXT_VARIABLE = "text";

    /** Transient because a servlet is serializable and a bound service is not. SCR sets it again on activation. */
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
     * Runs the search a request asks for, whichever method it arrived by, and reports whatever it could not do.
     *
     * @param request the current request
     * @param response the HTTP response
     * @throws IOException if writing the response fails
     */
    private void search(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        try {
            writeResponse(request, response);
        } catch (final InvalidQueryException e) {
            PaginatedJsonResponse.writeError(response, SlingJakartaHttpServletResponse.SC_BAD_REQUEST,
                "Invalid query: " + e.getMessage());
        } catch (final IllegalArgumentException e) {
            // What Oak raises for a query it parsed but cannot make sense of, notably a malformed full-text
            // expression. The client sent it, so the client hears about it.
            PaginatedJsonResponse.writeError(response, SlingJakartaHttpServletResponse.SC_BAD_REQUEST,
                "Invalid query: " + e.getMessage());
        } catch (final RepositoryException e) {
            LOGGER.warn("Failed to execute search query: {}", e.getMessage(), e);
            PaginatedJsonResponse.writeError(response, SlingJakartaHttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Failed to execute query");
        }
    }

    /**
     * Runs the requested search and writes the results. The query is executed before anything is written, so a query
     * that cannot run at all is reported as an error rather than as a half-written response.
     *
     * <p>
     * Executing a query is not reading it. Oak hands back a lazy result set, and a read can still fail once rows are
     * being pulled from it, by which time the beginning of the response may already have gone out. Such a failure
     * ends the results and is reported in the summary, leaving a document the client can still parse.
     * </p>
     *
     * @param request the current request
     * @param response the HTTP response
     * @throws IOException if writing the response fails
     * @throws RepositoryException if the query is invalid or cannot be executed
     */
    private void writeResponse(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException, RepositoryException
    {
        final String jcrQuery = request.getParameter("query");
        final String fullText = request.getParameter("fulltext");
        final String quick = request.getParameter("quick");
        final QueryResult results;
        final boolean reporting;
        if (StringUtils.isNotBlank(jcrQuery)) {
            final Session session = session(request);
            final String statement = QueryPathResolver.resolveReferencePaths(session, jcrQuery);
            reporting = REPORTING_QUERY.matcher(statement).find();
            // Asking for the plan of a statement that is itself about a plan is either the same question again or,
            // for an explain, not something Oak will parse
            results = runQuery(session, new BoundStatement(statement, Map.of()), !reporting);
        } else if (StringUtils.isNotBlank(fullText)) {
            reporting = false;
            results = runQuery(session(request), fullTextStatement(request, fullText), true);
        } else {
            reporting = false;
            results = null;
        }

        // Closing the generator closes the writer too
        try (JsonGenerator json = Json.createGenerator(response.getWriter())) {
            json.writeStartObject();
            json.writeStartArray("rows");
            final PaginatedJsonResponse page = PaginatedJsonResponse.forRequest(json, request);
            String error = null;
            try {
                if (results != null) {
                    // The rows of a reporting query describe the query. There is no node to serialize, and raw
                    // output is the only rendering left
                    if (reporting || "true".equals(request.getParameter("rawResults"))) {
                        writeRawResults(page, results);
                    } else {
                        writeNodeResults(page, request, results);
                    }
                } else if (StringUtils.isNotBlank(quick)) {
                    writeQuickResults(page, request, quick);
                }
            } catch (final RepositoryException | RuntimeException e) {
                // Unchecked as much as checked. A result set is lazy, and Oak signals much of what can go wrong
                // while it is being read with an unchecked exception: a read or memory limit reached, an index
                // failing under it. Letting one out here would abandon the response half-written, with the generator
                // closed on an incomplete document and too much of the body already on the wire to replace it with
                // an error status.
                LOGGER.warn("Failed to read the results of a search: {}", e.getMessage(), e);
                error = "Failed to read all the results";
            }
            json.writeEnd();
            page.writeSummary(request.getParameter("req"), error);
            json.writeEnd().flush();
        }
    }

    /**
     * The session of the user making the request, which every query runs in.
     *
     * @param request the current request
     * @return the session behind the request's resource resolver
     * @throws RepositoryException if the resource resolver is not backed by a JCR session
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
     * @param checkPlan whether to report the statement if it has no index to work with
     * @return the query results
     * @throws RepositoryException if the statement is invalid
     */
    private QueryResult runQuery(final Session session, final BoundStatement bound, final boolean checkPlan)
        throws RepositoryException
    {
        // Parsed first, so the error a client gets back is about the statement it sent, not about the decorated one
        // the plan is asked for below
        final Query query = bound.createQuery(session);
        if (checkPlan) {
            QueryPlanChecker.warnIfUnindexed(session, bound);
        }
        return query.execute();
    }

    /**
     * Builds the statement looking for a text anywhere in the repository.
     *
     * <p>
     * The text is stripped first. A full-text expression must start with a term. A leading space, which a paste or
     * an autocompletion routinely leaves in front of what the user typed, makes the expression fail to parse and
     * turns good input into a bad request. A trailing space, and any amount of space between the words, are already
     * fine.
     * </p>
     *
     * <p>
     * This is the only statement in the endpoint that spans every node type, and the only one that reaches Oak's own
     * {@link #SYSTEM_TREE bookkeeping}, which it is kept out of. A typed query cannot get there on its own: a frozen
     * node stores the type it was a copy of in a property and takes {@code nt:frozenNode} as its own, so it never
     * matches the type its original would.
     * </p>
     *
     * @param request the current request
     * @param query the text to look for, not blank
     * @return a JCR-SQL2 statement
     */
    private BoundStatement fullTextStatement(final SlingJakartaHttpServletRequest request, final String query)
    {
        final String text = query.strip();
        // Whether the input is a full-text expression the user wrote, or a text to be found as it is
        final boolean verbatim = !"true".equals(request.getParameter("doNotEscapeQuery"));
        // The full-text grammar is applied to whatever the variable holds, so escaping it is still this method's
        // job. What binding takes away is the layer around that one: the expression is no longer written into a
        // string literal, so there is no longer a literal for a quote to close and a client to continue past --
        // which is what makes doNotEscapeQuery safe to offer rather than safe only while one escape stays correct.
        final String expression = verbatim ? FULL_TEXT_SPECIAL.matcher(text).replaceAll("\\\\$1") : text;
        return new BoundStatement(
            String.format("select %1$s.* from [nt:base] as %1$s where contains(%1$s.*, $%2$s)%3$s", SELECTOR,
                FULL_TEXT_VARIABLE, OUTSIDE_SYSTEM_TREE),
            Map.of(FULL_TEXT_VARIABLE, expression));
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
        // Asking a row for a path without naming a selector throws as soon as the query has more than one, which a
        // join always has. The first selector is the node the query is about.
        final String[] selectorNames = results.getSelectorNames();
        final String selector = selectorNames.length <= 1 ? null : selectorNames[0];
        final RowIterator rows = results.getRows();
        boolean more = true;
        while (rows.hasNext() && more) {
            // The path alone is cheaper than loading the node. A join returns the same node once per matching
            // combination, and many of the paths read here are duplicates the page drops.
            final String path = readPath(rows.nextRow(), selector);
            if (path == null) {
                continue;
            }
            more = page.offer(path, () -> serializeNode(resolver, path, selectors));
        }
    }

    /**
     * Reads the path of the node a result row is about. One unreadable row is not a reason to fail the whole search,
     * so it is left out and the rest of the results are returned.
     *
     * @param row the row to read
     * @param selector the name of the selector holding the node, or {@code null} when the query has only one
     * @return the path of the matched node, or {@code null} if the row has no node to return, either because it
     *         cannot be read or because the selector matched nothing, as an outer join allows
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
     * each column the query asked for. A client that only needs a few properties, or the result of an aggregation,
     * uses this instead of paying for the serialization of whole nodes.
     *
     * <p>
     * The columns are read <em>after</em> the rows, and the order is load-bearing. For a query that reports on
     * itself, Oak answers {@code getColumnNames()} with the columns of the statement being reported on until the rows
     * have been asked for, and only then with the ones its rows actually hold. Asking first yields names no row has a
     * value for, which drops every row.
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
            // The client asked for rows. Unlike two whole nodes, two identical rows are not a duplicate to drop:
            // the query may have meant to return both.
            more = page.offer(null, () -> RawResultSerializer.serialize(row, selectors, columns));
        }
    }

    /**
     * Writes the matches found by the quick search engines. Every engine that can search at least one of the
     * requested node types is asked, until enough results have been collected.
     *
     * <p>
     * A node type may be searched by more than one engine, and every one of them is asked. Engines are an extension
     * point, not a partition of the content: two of them can know different things about the same type, one matching
     * a submission's own answers and another the text extracted from the files attached to it.
     * </p>
     *
     * <p>
     * The results are not deduplicated against each other, and cannot be: an engine returns a serialized match rather
     * than the path of one, so there is nothing here to compare. Two engines that find the same node both report it.
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
            // An engine is code this module knows nothing about. Every call into one is inside this guard, the type
            // questions as much as the search itself, so a misbehaving engine costs its own results and not the
            // whole response.
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
     * @param requested the node types the client restricted the search to, or {@code null} for no restriction
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
            // The method is declared never to return null. An engine that returns one anyway is broken, and the
            // NullPointerException lands in the same guard as every other way of misbehaving.
            results = engine.quickSearch(context);
            boolean more = true;
            while (results.hasNext() && more) {
                more = page.offer(null, results::next, results::skip);
            }
        } finally {
            // Closed whether the results were read to the end or not. Stopping early is the ordinary outcome for a
            // search with more matches than fit on a page, and an engine holding a session needs to hear about it.
            close(engine, results);
        }
    }

    /**
     * Releases an engine's results, whatever happened while they were being read.
     *
     * @param engine the engine the results came from, for the log message
     * @param results the results to release, may be {@code null} if the engine never returned any
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
     * @param selectors the extra serialization selectors requested by the client, may be an empty string
     * @return the serialized node, or {@code null} if it cannot be serialized; such nodes are left out of the
     *         response, though they still count towards the reported total
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

}

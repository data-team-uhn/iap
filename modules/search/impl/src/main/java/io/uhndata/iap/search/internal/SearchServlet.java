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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
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
import org.apache.commons.lang3.Strings;
import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingJakartaSafeMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.search.api.SearchParameters;
import io.uhndata.iap.search.api.SearchParametersFactory;
import io.uhndata.iap.search.api.SearchUtils;
import io.uhndata.iap.search.spi.QuickSearchEngine;
import io.uhndata.iap.utils.PaginatedJsonResponse;

/**
 * A servlet running a query against the repository and returning the results as JSON. It is registered on the
 * {@code data/Search} resource type with the {@code json} extension, so it serves {@code /search.json}. The
 * extension is not optional: without one, the default renderer of the {@code /search} node itself wins the
 * resolution.
 *
 * <p>
 * What to look for is taken from the request parameters, one of:
 * </p>
 * <ul>
 * <li>{@code query}, a full query in the JCR-SQL2 syntax</li>
 * <li>{@code fulltext}, a text to look for anywhere in the content, the repository's own {@code /jcr:system}
 * bookkeeping excepted</li>
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
 * The query runs in the session of the user making the request, so a search never reveals content that user could
 * not read anyway.
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
@SlingServletResourceTypes(resourceTypes = { "data/Search" }, methods = { "GET" }, extensions = { "json" })
public class SearchServlet extends SlingJakartaSafeMethodsServlet
{
    private static final long serialVersionUID = -6002540580101127991L;

    private static final Logger LOGGER = LoggerFactory.getLogger(SearchServlet.class);

    /** The selector the generated queries use for the node being matched. */
    private static final String SELECTOR = "n";

    /**
     * The tree the repository keeps its own bookkeeping in, left out of a generated search.
     *
     * <p>
     * None of it is content anyone searched for, and a good part of it is a copy of content that is: checking a node
     * in leaves a frozen copy of all its properties under {@code /jcr:system/jcr:versionStorage}, so a submission
     * edited twenty times would answer a search for its own text twenty-one times over. The rest is worse than
     * useless — the node type registry alone puts every property definition it declares in front of a search for an
     * ordinary word, and none of those paths is one the client can do anything with.
     * </p>
     */
    private static final String SYSTEM_TREE = "/jcr:system";

    /**
     * Keeps a generated statement out of the {@link #SYSTEM_TREE}. The tree's own node is named separately from its
     * descendants because {@code isdescendantnode} is strictly about the latter, and {@code /jcr:system} itself
     * carries a primary type that answers a search for "system".
     */
    private static final String OUTSIDE_SYSTEM_TREE =
        " and not issamenode(" + SELECTOR + ", '" + SYSTEM_TREE + "')"
            + " and not isdescendantnode(" + SELECTOR + ", '" + SYSTEM_TREE + "')";

    /**
     * The characters that mean something other than themselves in a full-text expression. The apostrophe is one of
     * them: it opens a quoted phrase, and although the statement's own string literal escaping doubles it, the parser
     * of the statement undoes that again, so an unescaped one reaches the full-text parser and leaves it looking for
     * a phrase that never ends.
     */
    private static final Pattern FULL_TEXT_SPECIAL = Pattern.compile("([\\\\+\\-&|!(){}\\[\\]^\"'~*?:/])");

    /**
     * Matches a statement that reports on itself instead of matching nodes: {@code explain} gives the plan the
     * repository would run, {@code measure} how much it had to scan. Either may be written in front of any
     * statement, and both together are allowed.
     */
    private static final Pattern REPORTING_QUERY =
        Pattern.compile("^\\s*+(explain|measure)\\s", Pattern.CASE_INSENSITIVE);

    /** How much of a statement to write into a log message. */
    private static final int MAX_LOGGED_STATEMENT = 500;

    /** Transient because a servlet is serializable and a bound service is not; it is re-injected on activation. */
    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC,
        policyOption = ReferencePolicyOption.GREEDY)
    private transient volatile List<QuickSearchEngine> searchEngines;

    @Override
    public void doGet(final SlingJakartaHttpServletRequest request, final SlingJakartaHttpServletResponse response)
        throws IOException
    {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        try {
            writeResponse(request, response);
        } catch (final InvalidQueryException e) {
            PaginatedJsonResponse.writeError(response, SlingJakartaHttpServletResponse.SC_BAD_REQUEST,
                "Invalid query: " + e.getMessage());
        } catch (final IllegalArgumentException e) {
            // What the repository raises for a query it parsed but cannot make sense of, notably a malformed
            // full-text expression; the client sent it, so the client is the one to hear about it
            PaginatedJsonResponse.writeError(response, SlingJakartaHttpServletResponse.SC_BAD_REQUEST,
                "Invalid query: " + e.getMessage());
        } catch (final RepositoryException e) {
            LOGGER.warn("Failed to execute search query: {}", e.getMessage(), e);
            PaginatedJsonResponse.writeError(response, SlingJakartaHttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Failed to execute query");
        }
    }

    /**
     * Runs the requested search and writes the results. The query is executed before anything is written, so that a
     * query that cannot run at all is reported as an error rather than as a half-written response.
     *
     * <p>
     * Executing the query is not the same as reading it, though: the repository hands back a lazy result set, and a
     * read can still fail once rows are being pulled from it, by which time the beginning of the response may already
     * have gone out. Such a failure ends the results and is reported in the summary, leaving the response a document
     * the client can still parse.
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
            // for an explain, not something the repository will parse
            results = runQuery(session, statement, !reporting);
        } else if (StringUtils.isNotBlank(fullText)) {
            reporting = false;
            results = runQuery(session(request), fullTextStatement(request, fullText), true);
        } else {
            reporting = false;
            results = null;
        }

        // The writer doesn't need to be explicitly closed, closing the generator closes it too
        try (JsonGenerator json = Json.createGenerator(response.getWriter())) {
            json.writeStartObject();
            json.writeStartArray("rows");
            final PaginatedJsonResponse page = PaginatedJsonResponse.forRequest(json, request);
            String error = null;
            try {
                if (results != null) {
                    // The rows of a reporting query describe the query, and have no node to serialize, so the raw
                    // output is the only one that can render them
                    if (reporting || "true".equals(request.getParameter("rawResults"))) {
                        writeRawResults(page, results);
                    } else {
                        writeNodeResults(page, request, results);
                    }
                } else if (StringUtils.isNotBlank(quick)) {
                    writeQuickResults(page, request, quick);
                }
            } catch (final RepositoryException | RuntimeException e) {
                // Unchecked as much as checked: a result set is lazy, and the repository signals a good part of what
                // can go wrong while it is being read — a read or memory limit reached, an index failing under it —
                // with an unchecked exception. Letting one out here would abandon the response half-written, with no
                // way back: the generator would be closed on an incomplete document, and by then too much of the
                // body may already be on the wire for an error status to replace it.
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
     * @param statement the statement to execute
     * @param checkPlan whether to report the statement if it has no index to work with
     * @return the query results
     * @throws RepositoryException if the statement is invalid
     */
    private QueryResult runQuery(final Session session, final String statement, final boolean checkPlan)
        throws RepositoryException
    {
        // Parsed first, so that the error a client gets back is about the statement it sent, not about the
        // decorated one the plan is asked for below
        final Query query = session.getWorkspace().getQueryManager().createQuery(statement, Query.JCR_SQL2);
        if (checkPlan) {
            warnIfUnindexed(session, statement);
        }
        return query.execute();
    }

    /**
     * Logs a warning if the query has no index to work with and will have to walk the repository instead. Since the
     * statement comes from the client, an expensive one is a mistake, or an attack, that is worth being able to
     * attribute to this endpoint; the repository logs its own traversal warnings, but only once the damage is being
     * done, and without saying who asked.
     *
     * <p>
     * Asking for the plan means planning the query twice. That is deliberate: planning is what the repository does
     * before it reads anything, and it is cheap next to a traversal, which is precisely the case this exists to
     * report.
     * </p>
     *
     * @param session the session to plan the query in
     * @param statement the statement about to be executed, already known to parse
     */
    private void warnIfUnindexed(final Session session, final String statement)
    {
        try {
            final RowIterator plan = session.getWorkspace().getQueryManager()
                .createQuery("explain " + statement, Query.JCR_SQL2).execute().getRows();
            if (!plan.hasNext()) {
                return;
            }
            final Value[] columns = plan.nextRow().getValues();
            if (columns.length > 0 && Strings.CI.contains(columns[0].getString(), "traverse")) {
                LOGGER.warn("The search query [{}] has no index to use and will walk the repository: {}",
                    forLog(statement), columns[0].getString());
            }
        } catch (final RepositoryException | RuntimeException e) {
            // Everything here is diagnostics; never fail a request the repository would have served. The unchecked
            // exceptions matter as much as the repository's own: a malformed full-text expression reaches the
            // repository's parser as an IllegalArgumentException, and a request that only asks for a plan it cannot
            // have is still a request that can be answered.
            LOGGER.debug("Could not obtain the plan of the search query [{}]: {}", forLog(statement), e.getMessage(),
                e);
        }
    }

    /**
     * Prepares a statement for a log message. In {@code fulltext} mode the statement is built around the text the
     * user typed, so it carries two problems into the log: search terms end up outside the access control that is
     * this endpoint's whole story about who may see what, and a line break in them would let a client write log
     * entries of its own choosing.
     *
     * @param statement the statement to log
     * @return the statement on a single line, no longer than {@value #MAX_LOGGED_STATEMENT} characters
     */
    static String forLog(final String statement)
    {
        final String oneLine = statement.replaceAll("\\s+", " ");
        return oneLine.length() > MAX_LOGGED_STATEMENT
            ? oneLine.substring(0, MAX_LOGGED_STATEMENT) + "..." : oneLine;
    }

    /**
     * Builds the statement looking for a text anywhere in the repository.
     *
     * <p>
     * The text is stripped first. A full-text expression must start with a term, so a leading space — which is what
     * a paste, or an autocompletion, routinely leaves in front of what the user typed — makes the expression fail to
     * parse and the request come back as a bad one, for input that is perfectly good. A trailing space, and any
     * amount of space between the words, are already fine.
     * </p>
     *
     * <p>
     * The statement is the only one in the endpoint that spans every node type, so it is also the only one that
     * reaches the repository's own {@link #SYSTEM_TREE bookkeeping}, which it is kept out of. A typed query cannot
     * get there on its own: a frozen node stores the type it was a copy of in a property and takes
     * {@code nt:frozenNode} as its own, so it never matches the type its original would.
     * </p>
     *
     * @param request the current request
     * @param query the text to look for, not blank
     * @return a JCR-SQL2 statement
     */
    private String fullTextStatement(final SlingJakartaHttpServletRequest request, final String query)
    {
        final String text = query.strip();
        // Whether the input is a full-text expression the user wrote, or a text to be found as it is
        final boolean verbatim = !"true".equals(request.getParameter("doNotEscapeQuery"));
        final String expression = verbatim ? FULL_TEXT_SPECIAL.matcher(text).replaceAll("\\\\$1") : text;
        // The quotes are escaped either way: they delimit the string in the statement, so leaving them to the client
        // would let it write the rest of the query
        return String.format("select %1$s.* from [nt:base] as %1$s where contains(%1$s.*, '%2$s')%3$s", SELECTOR,
            SearchUtils.escapeQueryArgument(expression), OUTSIDE_SYSTEM_TREE);
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
        // A row only knows which node is "the" result if it is told: asking for the path without naming a selector
        // throws as soon as the query has more than one, which a join always has. The first selector is the node the
        // query is about, the same convention the entity pagination follows with its own fixed selector.
        final String[] selectorNames = results.getSelectorNames();
        final String selector = selectorNames.length <= 1 ? null : selectorNames[0];
        final RowIterator rows = results.getRows();
        boolean more = true;
        while (rows.hasNext() && more) {
            // Working with the path alone is cheaper than loading the node, and a query with a join returns the same
            // node once per matching combination, so most of the paths read here are duplicates to be dropped
            final String path = readPath(rows.nextRow(), selector);
            if (path == null) {
                continue;
            }
            more = page.offer(path, () -> serializeNode(resolver, path, selectors));
        }
    }

    /**
     * Reads the path of the node a result row is about. One row that cannot be read is not a reason to fail the whole
     * search: the rest of the results are still worth returning, so a bad row is left out instead.
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
     * each column the query asked for. This is what a client that only needs a few properties, or the result of an
     * aggregation, uses instead of paying for the serialization of whole nodes.
     *
     * <p>
     * The columns are read <em>after</em> the rows, and the order is load-bearing: for a query that reports on
     * itself, the repository answers {@code getColumnNames()} with the columns of the statement being reported on
     * until the rows have been asked for, and only then with the ones its rows actually hold. Asking first yields
     * names no row has a value for, which drops every row.
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
            // Rows are what the client asked for here, so, unlike whole nodes, two identical ones are not a
            // duplicate to be dropped: the query may well have meant to return both
            more = page.offer(null, () -> RawResultSerializer.serialize(row, selectors, columns));
        }
    }

    /**
     * Writes the matches found by the quick search engines. Every engine that can search at least one of the
     * requested node types is asked, until enough results have been collected.
     *
     * <p>
     * Each node type is searched by a single engine, the first registered one that takes it on. That is what makes
     * the results of the engines disjoint, and so what makes it safe not to look for duplicates among them: two
     * engines claiming the same type would otherwise return the same node twice, and count it twice. The engines are
     * an extension point, so nothing stops that from being configured — it is caught here rather than assumed away.
     * An engine that fails before returning anything does not take its types with it, so a second engine that can
     * search them still gets asked.
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
        final Set<String> alreadySearched = new HashSet<>();
        for (final QuickSearchEngine engine : engines) {
            if (page.isFull()) {
                break;
            }
            // An engine is code this module knows nothing about, registered by whoever wanted its content
            // searchable. Every call into one is inside this guard, the type questions as much as the search
            // itself, so that one misbehaving engine costs its own results and not the whole response.
            try {
                askEngine(page, request, query, engine, requested, alreadySearched);
            } catch (final RuntimeException e) {
                LOGGER.warn("The quick search engine {} failed: {}", engine.getClass().getName(), e.getMessage(), e);
            }
        }
    }

    /**
     * Asks one engine for the matches of the types no earlier engine has already claimed.
     *
     * @param page the paginator for the requested page
     * @param request the current request
     * @param query the text to look for
     * @param engine the engine to ask
     * @param requested the node types the client restricted the search to, or {@code null} for no restriction
     * @param alreadySearched the node types earlier engines were asked for, added to with the ones this engine takes
     */
    private void askEngine(final PaginatedJsonResponse page, final SlingJakartaHttpServletRequest request,
        final String query, final QuickSearchEngine engine, final String[] requested,
        final Set<String> alreadySearched)
    {
        final List<String> supported = requested == null ? List.copyOf(engine.getSupportedTypes())
            : Arrays.stream(requested).filter(engine::isTypeSupported).toList();
        final List<String> types = supported.stream().filter(type -> !alreadySearched.contains(type)).toList();
        if (types.size() < supported.size()) {
            LOGGER.warn("More than one quick search engine searches {}; only the first one is asked",
                supported.stream().filter(alreadySearched::contains).toList());
        }
        if (types.isEmpty()) {
            return;
        }

        final SearchParameters parameters = SearchParametersFactory.newSearchParameters()
            .withQuery(query)
            .withResourceTypes(types)
            .withMaxResults(page.getRemainingCapacity())
            .build();
        QuickSearchEngine.Results results = null;
        try {
            // Declared never to be null, so an engine that returns one anyway is an engine that is broken, and is
            // handled as one: it lands in the same guard as every other way of misbehaving
            results = engine.quickSearch(parameters, request.getResourceResolver());
            // A type is claimed once an engine has actually taken the search on, not merely because it said it could
            // serve the type: an engine that fails outright returned nothing, so there is nothing for a later engine
            // to duplicate, and leaving the type claimed would only mean answering with nothing at all
            alreadySearched.addAll(types);
            boolean more = true;
            while (results.hasNext() && more) {
                more = page.offer(null, results::next, results::skip);
            }
        } finally {
            // Closed once the results have been read, or once enough of them have: stopping early is the ordinary
            // outcome for any search with more matches than fit on a page, and an engine holding a session for the
            // search needs to hear about it either way
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

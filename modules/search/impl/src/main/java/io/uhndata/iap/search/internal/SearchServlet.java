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
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;
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
 * {@code iap/Search} resource type with the {@code json} extension, so it serves {@code /search.json}. The
 * extension is not optional: without one, the default renderer of the {@code /search} node itself wins the
 * resolution.
 *
 * <p>
 * What to look for is taken from the request parameters, one of:
 * </p>
 * <ul>
 * <li>{@code query}, a full query in the JCR-SQL2 syntax</li>
 * <li>{@code fulltext}, a text to look for anywhere in the repository</li>
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
 * the query matched; only meaningful together with {@code query}</li>
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
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(resourceTypes = { "iap/Search" }, methods = { "GET" }, extensions = { "json" })
public class SearchServlet extends SlingJakartaSafeMethodsServlet
{
    private static final long serialVersionUID = -6002540580101127991L;

    private static final Logger LOGGER = LoggerFactory.getLogger(SearchServlet.class);

    /** The selector the generated queries use for the node being matched. */
    private static final String SELECTOR = "n";

    /** The characters that mean something other than themselves in a full-text expression. */
    private static final Pattern FULL_TEXT_SPECIAL = Pattern.compile("([\\\\+\\-&|!(){}\\[\\]^\"~*?:/])");

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
        if (StringUtils.isNotBlank(jcrQuery)) {
            results = runQuery(request, jcrQuery);
        } else if (StringUtils.isNotBlank(fullText)) {
            results = runQuery(request, fullTextStatement(request, fullText));
        } else {
            results = null;
        }

        // The writer doesn't need to be explicitly closed, closing the generator closes it too
        try (JsonGenerator json = Json.createGenerator(response.getWriter())) {
            json.writeStartObject();
            json.writeStartArray("rows");
            final PaginatedJsonResponse page = PaginatedJsonResponse.forRequest(json, request);
            if (results != null) {
                if ("true".equals(request.getParameter("rawResults"))) {
                    writeRawResults(page, results);
                } else {
                    writeNodeResults(page, request, results);
                }
            } else if (StringUtils.isNotBlank(quick)) {
                writeQuickResults(page, request, quick);
            }
            json.writeEnd();
            page.writeSummary(request.getParameter("req"));
            json.writeEnd().flush();
        }
    }

    /**
     * Executes a JCR-SQL2 statement in the session of the user making the request.
     *
     * @param request the current request
     * @param statement the statement to execute
     * @return the query results
     * @throws RepositoryException if the statement is invalid, or the resource resolver is not backed by a JCR
     *             session
     */
    private QueryResult runQuery(final SlingJakartaHttpServletRequest request, final String statement)
        throws RepositoryException
    {
        final Session session = request.getResourceResolver().adaptTo(Session.class);
        if (session == null) {
            throw new RepositoryException("The resource resolver is not backed by a JCR session");
        }
        // Parsed first, so that the error a client gets back is about the statement it sent, not about the
        // decorated one the plan is asked for below
        final Query query = session.getWorkspace().getQueryManager().createQuery(statement, Query.JCR_SQL2);
        warnIfUnindexed(session, statement);
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
                LOGGER.warn("The search query [{}] has no index to use and will walk the repository: {}", statement,
                    columns[0].getString());
            }
        } catch (final RepositoryException e) {
            // Everything here is diagnostics; never fail a request the repository would have served
            LOGGER.debug("Could not obtain the plan of the search query [{}]: {}", statement, e.getMessage(), e);
        }
    }

    /**
     * Builds the statement looking for a text anywhere in the repository.
     *
     * @param request the current request
     * @param query the text to look for
     * @return a JCR-SQL2 statement
     */
    private String fullTextStatement(final SlingJakartaHttpServletRequest request, final String query)
    {
        // Whether the input is a full-text expression the user wrote, or a text to be found as it is
        final boolean verbatim = !"true".equals(request.getParameter("doNotEscapeQuery"));
        final String expression = verbatim ? FULL_TEXT_SPECIAL.matcher(query).replaceAll("\\\\$1") : query;
        // The quotes are escaped either way: they delimit the string in the statement, so leaving them to the client
        // would let it write the rest of the query
        return String.format("select %1$s.* from [nt:base] as %1$s where contains(%1$s.*, '%2$s')", SELECTOR,
            SearchUtils.escapeQueryArgument(expression));
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
        final RowIterator rows = results.getRows();
        boolean more = true;
        while (rows.hasNext() && more) {
            // Working with the path alone is cheaper than loading the node, and a query with a join returns the same
            // node once per matching combination, so most of the paths read here are duplicates to be dropped
            final String path = rows.nextRow().getPath();
            more = page.offer(path, () -> serializeNode(resolver, path, selectors));
        }
    }

    /**
     * Writes the query results as they are: one object per row, holding the path of each selector and the value of
     * each column the query asked for. This is what a client that only needs a few properties, or the result of an
     * aggregation, uses instead of paying for the serialization of whole nodes.
     *
     * @param page the paginator for the requested page
     * @param results the query results
     * @throws RepositoryException if reading the query results fails
     */
    private void writeRawResults(final PaginatedJsonResponse page, final QueryResult results)
        throws RepositoryException
    {
        final String[] selectors = results.getSelectorNames();
        final String[] columns = results.getColumnNames();
        final RowIterator rows = results.getRows();
        boolean more = true;
        while (rows.hasNext() && more) {
            final Row row = rows.nextRow();
            // Rows are what the client asked for here, so, unlike whole nodes, two identical ones are not a
            // duplicate to be dropped: the query may well have meant to return both
            more = page.offer(null, () -> serializeRow(row, selectors, columns));
        }
    }

    /**
     * Writes the matches found by the quick search engines. Every engine that can search at least one of the
     * requested node types is asked, until enough results have been collected.
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
            final List<String> types = requested == null ? List.copyOf(engine.getSupportedTypes())
                : Arrays.stream(requested).filter(engine::isTypeSupported).toList();
            if (types.isEmpty()) {
                continue;
            }
            final SearchParameters parameters = SearchParametersFactory.newSearchParameters()
                .withQuery(query)
                .withResourceTypes(types)
                .withMaxResults(page.getRemainingCapacity())
                .build();
            final QuickSearchEngine.Results results = engine.quickSearch(parameters, request.getResourceResolver());
            boolean more = true;
            while (results.hasNext() && more) {
                // Every engine searches node types of its own, so no two engines can return the same match
                more = page.offer(null, results::next, results::skip);
            }
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

    /**
     * Serializes one raw query result row.
     *
     * @param row the row to serialize
     * @param selectors the selector names of the query
     * @param columns the column names of the query
     * @return the serialized row, or {@code null} if it cannot be read
     */
    private JsonObject serializeRow(final Row row, final String[] selectors, final String[] columns)
    {
        try {
            final JsonObjectBuilder builder = Json.createObjectBuilder();
            for (final String selector : selectors) {
                final String path = row.getPath(selector);
                builder.add(selector, path == null ? JsonValue.NULL : Json.createValue(path));
            }
            for (final String column : columns) {
                final Value value = row.getValue(column);
                builder.add(column, value == null ? JsonValue.NULL : Json.createValue(value.getString()));
            }
            return builder.build();
        } catch (final RepositoryException e) {
            LOGGER.warn("Failed to serialize a search result row: {}", e.getMessage(), e);
            return null;
        }
    }
}

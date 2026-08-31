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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.ValueFactory;
import javax.jcr.query.Query;
import javax.jcr.query.RowIterator;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.stream.JsonGenerator;
import jakarta.servlet.Servlet;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingJakartaSafeMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.utils.PaginatedJsonResponse;

/**
 * A servlet that lists, in pages, the entities stored under an entity homepage. It is registered on
 * {@code data/EntityHomepage} with the {@code paginate} selector, so, through the {@code sling:resourceSuperType}
 * chain of the concrete homepage types, it serves e.g. {@code /Submissions.paginate.json} or
 * {@code /Schemas.paginate.json}.
 *
 * <p>
 * The type of the listed entities is, by convention, derived from the homepage's resource type (e.g.
 * {@code sub/SubmissionsHomepage} lists {@code sub:Submission} nodes), unless the homepage node explicitly names
 * another type in a {@code childNodeType} property.
 * </p>
 *
 * <p>
 * This servlet supports the following parameters:
 * </p>
 * <ul>
 * <li>{@code offset}: how many matching entities to skip, {@code 0} by default</li>
 * <li>{@code limit}: how many entities to include at most in the result, {@code 10} by default; {@code 0} only
 * counts the matches without returning any</li>
 * <li>{@code sortBy}: the property to order the results by, {@code jcr:created} by default</li>
 * <li>{@code descending}: if {@code true}, reverses the order of the results</li>
 * <li>{@code filter}: a full text search term that the entities must contain</li>
 * <li>{@code fieldName}, {@code fieldComparator}, {@code fieldValue}: repeatable triples imposing a condition on a
 * property of the entity itself, e.g. {@code status = draft}; the supported comparators are {@code =}, {@code <>},
 * {@code <}, {@code <=}, {@code >}, {@code >=}, {@code LIKE}, {@code NOT LIKE}, {@code ILIKE} (case-insensitive
 * {@code LIKE}), {@code NOT ILIKE}, {@code IS NULL} and {@code IS NOT NULL}; if no
 * comparators are sent, {@code =} is used; the special value {@code @me} is replaced with the current user's id</li>
 * <li>{@code fieldGroup}: optional group identifiers aligned with the field triples; conditions sharing a
 * (non-empty) group are ORed together, while distinct groups and ungrouped conditions are ANDed, so e.g.
 * {@code status = a OR status = b} is expressed as two conditions sharing a group</li>
 * <li>{@code childType}, {@code childFieldName}, {@code childFieldComparator}, {@code childFieldValue},
 * {@code childFieldGroup}: same, but the conditions apply to a descendant of the entity, e.g. only submissions
 * having a {@code sub:Review} descendant with {@code reviewer = @me}; multiple independent descendant conditions
 * may be sent as numbered families {@code childTypeN}, {@code childFieldNName}, {@code childFieldNComparator},
 * {@code childFieldNValue}, {@code childFieldNGroup}, where {@code N} is any number, each family requiring its own
 * matching descendant, e.g. {@code childType1=sub:Review&childField1Name=reviewer&childField1Value=@me}</li>
 * <li>{@code resourceSelectors}: extra selectors to apply when serializing each entity, e.g. {@code deep}</li>
 * <li>{@code req}: an opaque request identifier, echoed back in the response so that the client can discard
 * out-of-order responses</li>
 * </ul>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(resourceTypes = { "data/EntityHomepage" }, methods = { "GET" },
    selectors = { "paginate" }, extensions = { "json" })
public class PaginationServlet extends SlingJakartaSafeMethodsServlet
{
    private static final long serialVersionUID = 5202812849395342527L;

    private static final Logger LOGGER = LoggerFactory.getLogger(PaginationServlet.class);

    /** Matches the parameter names of the descendant condition families, capturing the family's number. */
    private static final Pattern CHILD_PARAMETER =
        Pattern.compile("childType(\\d*+)|childField(\\d*+)(?:Name|Comparator|Value|Group)");

    @Override
    public void doGet(final SlingJakartaHttpServletRequest request, final SlingJakartaHttpServletResponse response)
        throws IOException
    {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        try {
            final RowIterator rows = prepareQuery(request).execute().getRows();
            writeResponse(request, response, rows);
        } catch (final IllegalArgumentException e) {
            PaginatedJsonResponse.writeError(response, SlingJakartaHttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (final RepositoryException e) {
            LOGGER.warn("Failed to execute pagination query: {}", e.getMessage(), e);
            PaginatedJsonResponse.writeError(response, SlingJakartaHttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Failed to execute query");
        }
    }

    /**
     * Builds the JCR query for the request: entities of the homepage's child type, under the homepage, restricted
     * by the filters sent in the request.
     *
     * @param request the current request, targeting an entity homepage
     * @return a query ready to be executed
     * @throws RepositoryException if the resource resolver is not backed by a JCR session
     * @throws IllegalArgumentException if the request parameters are invalid
     */
    private Query prepareQuery(final SlingJakartaHttpServletRequest request) throws RepositoryException
    {
        final Session session = request.getResourceResolver().adaptTo(Session.class);
        if (session == null) {
            throw new RepositoryException("The resource resolver is not backed by a JCR session");
        }
        final Resource homepage = request.getResource();
        final QueryBuilder builder = new QueryBuilder(getNodeType(homepage), homepage.getPath())
            .withFilters(parseFilters(request, "field", session.getUserID()));
        for (final String suffix : getChildFilterSuffixes(request)) {
            builder.withChildFilters(request.getParameter("childType" + suffix),
                parseFilters(request, "childField" + suffix, session.getUserID()));
        }
        final QueryBuilder.BoundQuery bound = builder
            .withFullText(request.getParameter("filter"))
            .withSort(request.getParameter("sortBy"), Boolean.parseBoolean(request.getParameter("descending")))
            .build();
        // Only the statement is logged, never the bindings: the values are the caller's search terms, and this
        // endpoint's whole story is about who may see which content.
        LOGGER.debug("Pagination query: {}", bound.statement());
        final Query query =
            session.getWorkspace().getQueryManager().createQuery(bound.statement(), Query.JCR_SQL2);
        final ValueFactory values = session.getValueFactory();
        for (final Map.Entry<String, String> binding : bound.bindings().entrySet()) {
            query.bindValue(binding.getKey(), values.createValue(binding.getValue()));
        }
        return query;
    }

    /**
     * The type of nodes listed by the targeted homepage: the explicit {@code childNodeType} property if the
     * homepage node has one, otherwise the type derived from the homepage's resource type by the
     * {@code sub/SubmissionsHomepage} holds {@code sub:Submission} naming convention.
     *
     * @param homepage the homepage resource targeted by the request
     * @return a node type name
     */
    private String getNodeType(final Resource homepage)
    {
        final String explicit = homepage.getValueMap().get("childNodeType", String.class);
        if (explicit != null) {
            return explicit;
        }
        return homepage.getResourceType().replace('/', ':').replaceFirst("sHomepage$", "");
    }

    /**
     * Collects the descendant condition families present in the request: the empty suffix for the plain
     * {@code childType}/{@code childField*} parameters, and one numeric suffix per
     * {@code childTypeN}/{@code childFieldN*} family. The suffixes are returned in numeric order, with the plain
     * family first, so the mapping of families onto query joins is deterministic.
     *
     * @param request the current request
     * @return the family suffixes present in the request, possibly empty
     */
    private Collection<String> getChildFilterSuffixes(final SlingJakartaHttpServletRequest request)
    {
        // Shorter digit strings are smaller numbers, so length-then-lexicographic is numeric order without the
        // overflow risk of actually parsing untrusted numbers
        final Set<String> suffixes =
            new TreeSet<>(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()));
        for (final String name : request.getParameterMap().keySet()) {
            final Matcher matcher = CHILD_PARAMETER.matcher(name);
            if (matcher.matches()) {
                suffixes.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
            }
        }
        return suffixes;
    }

    /**
     * Parses one family of repeatable name/comparator/value filter parameters into a list of filters.
     *
     * @param request the current request
     * @param prefix the parameter name prefix, {@code field} for conditions on the entity itself, {@code childField}
     *            for conditions on a descendant node
     * @param currentUser the id of the user making the request, replacing the special value {@code @me}
     * @return a list of filters, empty if no filters with the given prefix are present in the request
     * @throws IllegalArgumentException if the names, comparators, values and groups don't come in complete tuples
     */
    private List<Filter> parseFilters(final SlingJakartaHttpServletRequest request, final String prefix,
        final String currentUser)
    {
        final String[] names = request.getParameterValues(prefix + "Name");
        if (names == null) {
            return List.of();
        }
        final String[] values = getAlignedParameter(request, prefix + "Value", names.length);
        final String[] comparators = getAlignedParameter(request, prefix + "Comparator", names.length);
        final String[] groups = getAlignedParameter(request, prefix + "Group", names.length);
        if (values == null) {
            throw new IllegalArgumentException(
                "A " + prefix + "Value parameter must be provided for every " + prefix + "Name");
        }
        final List<Filter> result = new ArrayList<>(names.length);
        for (int i = 0; i < names.length; ++i) {
            final String value = "@me".equals(values[i]) ? currentUser : values[i];
            final String group = groups == null || groups[i].isEmpty() ? null : groups[i];
            result.add(new Filter(names[i], comparators == null ? "=" : comparators[i], value, group));
        }
        return result;
    }

    /**
     * Reads one of the repeatable parameters accompanying the filter names, enforcing that, if present at all, it
     * is present the same number of times as the names.
     *
     * @param request the current request
     * @param name the parameter name
     * @param expectedLength the number of filter names the parameter must align with
     * @return the parameter values, or {@code null} if the parameter isn't present at all
     * @throws IllegalArgumentException if the parameter is present a different number of times
     */
    private String[] getAlignedParameter(final SlingJakartaHttpServletRequest request, final String name,
        final int expectedLength)
    {
        final String[] values = request.getParameterValues(name);
        if (values != null && values.length != expectedLength) {
            throw new IllegalArgumentException(
                "The " + name + " parameters must be provided once per filter name");
        }
        return values;
    }

    /**
     * Writes the successful response: the requested page of serialized entities, followed by a summary of the
     * pagination status.
     *
     * <p>
     * The query has already been executed by the time this is called, but the result set it returned is lazy, so
     * reading the rows can still fail once part of the response has gone out. That is reported in the summary rather
     * than as an error response, which by then could only be appended to a body that already holds one.
     * </p>
     *
     * @param request the current request
     * @param response the HTTP response
     * @param rows the query results to paginate over
     * @throws IOException if writing the response fails
     */
    private void writeResponse(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response, final RowIterator rows)
        throws IOException
    {
        // The writer doesn't need to be explicitly closed, closing the generator closes it too
        try (JsonGenerator json = Json.createGenerator(response.getWriter())) {
            json.writeStartObject();
            json.writeStartArray("rows");
            final PaginatedJsonResponse page = PaginatedJsonResponse.forRequest(json, request);
            String error = null;
            try {
                writeRows(page, rows, request);
            } catch (final RepositoryException | RuntimeException e) {
                // Unchecked as much as checked: the repository signals a good part of what can go wrong while a lazy
                // result set is being read — a read or memory limit reached, an index failing under it — with an
                // unchecked exception. Letting one out here would abandon the response half-written, leaving the
                // generator closed on an incomplete document and too much of the body already on the wire for an
                // error status to replace it.
                LOGGER.warn("Failed to read the results of a pagination query: {}", e.getMessage(), e);
                error = "Failed to read all the results";
            }
            json.writeEnd();
            page.writeSummary(request.getParameter("req"), error);
            json.writeEnd().flush();
        }
    }

    /**
     * Feeds the query results to the paginator, which writes the requested page and counts the matches. Oak queries
     * can't request distinct results, so when a descendant join produces the same entity multiple times the
     * duplicates have to be dropped here, by path.
     *
     * @param page the paginator for the requested page, positioned inside the {@code rows} array
     * @param rows the query results to paginate over
     * @param request the current request
     * @throws RepositoryException if reading the query results fails
     */
    private void writeRows(final PaginatedJsonResponse page, final RowIterator rows,
        final SlingJakartaHttpServletRequest request) throws RepositoryException
    {
        final String selectors = PaginatedJsonResponse.getResourceSelectors(request);
        final ResourceResolver resolver = request.getResourceResolver();
        boolean more = true;
        while (rows.hasNext() && more) {
            final String path = rows.nextRow().getPath("n");
            more = page.offer(path, () -> serializeRow(resolver, path, selectors));
        }
    }

    /**
     * Serializes one entity for the response.
     *
     * @param resolver the current resource resolver
     * @param path the path of the entity to serialize
     * @param selectors the extra serialization selectors requested by the client, may be an empty string
     * @return the serialized entity, or {@code null} if the entity cannot be serialized; such entities are left out
     *         of the response, though they still count towards the reported total
     */
    private JsonObject serializeRow(final ResourceResolver resolver, final String path, final String selectors)
    {
        try {
            return resolver.resolve(path + selectors).adaptTo(JsonObject.class);
        } catch (final RuntimeException e) {
            LOGGER.warn("Failed to serialize {} for pagination: {}", path, e.getMessage(), e);
            return null;
        }
    }

}

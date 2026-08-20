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
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.query.Query;
import javax.jcr.query.RowIterator;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.stream.JsonGenerator;
import jakarta.servlet.Servlet;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingJakartaSafeMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.utils.UserIds;

/**
 * A servlet that lists, in pages, the entities stored under an entity homepage. It is registered on
 * {@code iap/EntityHomepage} with the {@code paginate} selector, so, through the {@code sling:resourceSuperType}
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
@SlingServletResourceTypes(resourceTypes = { "iap/EntityHomepage" }, methods = { "GET" },
    selectors = { "paginate" }, extensions = { "json" })
public class PaginationServlet extends SlingJakartaSafeMethodsServlet
{
    private static final long serialVersionUID = 5202812849395342527L;

    private static final Logger LOGGER = LoggerFactory.getLogger(PaginationServlet.class);

    /** The filter value standing for whoever is asking. */
    private static final String ME = "@me";

    /** The filter value standing for everything they act as, expanded into an OR over those principals. */
    private static final String MY_PRINCIPALS = "@myPrincipals";

    /** The number of entities returned when no {@code limit} is requested. */
    private static final long DEFAULT_LIMIT = 10;

    /** The maximum number of entities returned in one response, no matter the requested {@code limit}. */
    private static final long MAX_LIMIT = 1000;

    /**
     * How far past the requested page, in pages, to keep counting the total number of matches before declaring the
     * total approximate. Counting means iterating the indexed query results, which is cheap but not free; a deep
     * horizon keeps the reported total exact for all but the largest collections, and a good estimate beyond that.
     */
    private static final long LOOKAHEAD_PAGES = 100;

    /**
     * The most rows one counting batch may span, whatever the requested page size: without this bound, a
     * maximum-limit request could demand counting a hundred thousand rows in one go.
     */
    private static final long MAX_LOOKAHEAD_ROWS = 10_000;

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
            writeError(response, SlingJakartaHttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (final RepositoryException e) {
            LOGGER.warn("Failed to execute pagination query: {}", e.getMessage(), e);
            writeError(response, SlingJakartaHttpServletResponse.SC_INTERNAL_SERVER_ERROR,
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
        // What `@me` stands for: the id the repository records, so that it matches values written earlier by the
        // same person under a differently capitalised login
        final String me = UserIds.canonical(request.getResourceResolver());
        final List<String> principals = principalsOf(session);
        final QueryBuilder builder = new QueryBuilder(getNodeType(homepage), homepage.getPath())
            .withFilters(parseFilters(request, "field", me, principals));
        for (final String suffix : getChildFilterSuffixes(request)) {
            builder.withChildFilters(request.getParameter("childType" + suffix),
                parseFilters(request, "childField" + suffix, me, principals));
        }
        final String statement = builder
            .withFullText(request.getParameter("filter"))
            .withSort(request.getParameter("sortBy"), Boolean.parseBoolean(request.getParameter("descending")))
            .build();
        LOGGER.debug("Pagination query: {}", statement);
        return session.getWorkspace().getQueryManager().createQuery(statement, Query.JCR_SQL2);
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
     * @param principals everything the requesting session acts as, which the special value {@code @myPrincipals}
     *            expands into
     * @return a list of filters, empty if no filters with the given prefix are present in the request
     * @throws IllegalArgumentException if the names, comparators, values and groups don't come in complete tuples
     */
    private List<Filter> parseFilters(final SlingJakartaHttpServletRequest request, final String prefix,
        final String currentUser, final List<String> principals)
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
            final String comparator = comparators == null ? "=" : comparators[i];
            final String group = groups == null || groups[i].isEmpty() ? null : groups[i];
            if (MY_PRINCIPALS.equals(values[i])) {
                result.addAll(principalFilters(names[i], comparator, group, principals, i));
            } else {
                final String value = ME.equals(values[i]) ? currentUser : values[i];
                result.add(new Filter(names[i], comparator, value, group));
            }
        }
        return result;
    }

    /**
     * Expands {@code @myPrincipals} into one condition per principal, ORed together.
     *
     * <p>What it is for: a property naming who may act — a task's {@code performers} — holds principals rather
     * than user ids, so "waiting for me" is not one comparison but "any of the things I act as". Expanded here
     * rather than sent by the client, because a client would have to be told its own group memberships to build
     * it, and because the answer belongs to the session rather than to the request.</p>
     *
     * <p>A session that acts as nothing gets one condition on its own user id, not none: dropping the filter
     * would widen the query to everything, which is the opposite of what asking for one's own work means.</p>
     *
     * @param name the property to compare
     * @param comparator the comparator to use for each condition
     * @param group the group the caller put this filter in, or {@code null} to let the expansion have its own
     * @param principals everything the requesting session acts as
     * @param index the filter's position, used to name a group of its own so that two expansions do not merge
     * @return the filters to OR together, never empty
     */
    private List<Filter> principalFilters(final String name, final String comparator, final String group,
        final List<String> principals, final int index)
    {
        // Its own group, so the OR stands on its own; a caller who named a group is honoured, so an expansion can
        // still be ORed together with a condition of the caller's own
        final String orGroup = group == null ? MY_PRINCIPALS + index : group;
        return principals.stream()
            .map(principal -> new Filter(name, comparator, principal, orGroup))
            .collect(Collectors.toList());
    }

    /**
     * Everything the requesting session acts as: its own user plus every principal it is bound to.
     *
     * <p>Read from the bound principals rather than from group memberships looked up through {@code UserManager},
     * for the reason the deletion vetoes record: with dynamic membership an identity provider's roles arrive as
     * principals with no local group node behind them, so a membership lookup would report that a session belongs
     * to nothing.</p>
     *
     * @param session the requesting session
     * @return the principal names, with the user's own id first; never empty
     * @throws RepositoryException if the session cannot say what it is bound to
     */
    private List<String> principalsOf(final Session session) throws RepositoryException
    {
        final Stream<String> bound = session instanceof JackrabbitSession
            ? ((JackrabbitSession) session).getBoundPrincipals().stream().map(Principal::getName)
            : Stream.empty();
        return Stream.concat(Stream.ofNullable(session.getUserID()), bound)
            .distinct()
            .collect(Collectors.toList());
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
     * @param request the current request
     * @param response the HTTP response
     * @param rows the query results to paginate over
     * @throws IOException if writing the response fails
     * @throws RepositoryException if reading the query results fails
     */
    private void writeResponse(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response, final RowIterator rows)
        throws IOException, RepositoryException
    {
        final long offset = Math.max(0, parseLong(request.getParameter("offset"), 0));
        final long limit = Math.min(Math.max(0, parseLong(request.getParameter("limit"), DEFAULT_LIMIT)), MAX_LIMIT);
        // The writer doesn't need to be explicitly closed, closing the generator closes it too
        try (JsonGenerator json = Json.createGenerator(response.getWriter())) {
            json.writeStartObject();
            json.writeStartArray("rows");
            final long[] counts = writeRows(json, rows, request, offset, limit);
            json.writeEnd();
            writeSummary(json, request, offset, limit, counts);
            json.writeEnd().flush();
        }
    }

    /**
     * Writes the requested page of entities, and counts the total number of matches. Two Oak limitations shape
     * this: queries can't request distinct results, so when a descendant join produces the same entity multiple
     * times the duplicates must be skipped manually; and the query doesn't report a total number of matches, so the
     * results must be counted one by one. To keep the effort bounded, counting stops a fixed number of pages past
     * the requested page, and the total is reported as approximate if there were still more results at that point.
     *
     * @param json the generator where the serialized entities are written
     * @param rows the query results to paginate over
     * @param request the current request
     * @param offset how many unique matches to skip
     * @param limit how many unique matches to include in the response
     * @return the resulting counts: the number of rows written, the number of unique matches seen, and whether that
     *         total is approximate ({@code 1}) or exact ({@code 0})
     * @throws RepositoryException if reading the query results fails
     */
    private long[] writeRows(final JsonGenerator json, final RowIterator rows,
        final SlingJakartaHttpServletRequest request, final long offset, final long limit)
        throws RepositoryException
    {
        final String selectors = getResourceSelectors(request);
        final Set<String> seen = new HashSet<>();
        long returned = 0;
        final long batchSize = Math.min(LOOKAHEAD_PAGES * Math.max(limit, 1), MAX_LOOKAHEAD_ROWS);
        // Count until the end of the batch of pages containing the requested page, plus one more result to know
        // whether the reported total is exact
        final long lookahead = ((offset + Math.max(limit, 1) + batchSize - 1) / batchSize) * batchSize + 1;
        boolean more = false;
        while (rows.hasNext()) {
            final String path = rows.nextRow().getPath("n");
            if (!seen.add(path)) {
                continue;
            }
            if (seen.size() > offset && returned < limit) {
                final JsonObject row = serializeRow(request.getResourceResolver(), path, selectors);
                if (row != null) {
                    json.write(row);
                    ++returned;
                }
            }
            if (seen.size() >= lookahead) {
                more = true;
                break;
            }
        }
        return new long[] { returned, seen.size() - (more ? 1 : 0), more ? 1 : 0 };
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

    /**
     * The extra serialization selectors requested by the client, cleaned up for appending to a resource path.
     *
     * @param request the current request
     * @return a string safe to append to a repository path, either empty or in the form {@code .sel1.sel2}
     */
    private String getResourceSelectors(final SlingJakartaHttpServletRequest request)
    {
        final String selectors = request.getParameter("resourceSelectors");
        if (selectors == null || selectors.isBlank()) {
            return "";
        }
        return ("." + selectors).replaceAll("[/\\s]", "").replaceAll("\\.+", ".");
    }

    /**
     * Writes the summary of the pagination status: the effective offset and limit, the number of returned rows, and
     * the (possibly approximate) total number of matches. The opaque {@code req} request parameter, if sent, is
     * echoed back so the client can match the response to its request.
     *
     * @param json the generator where the summary is written
     * @param request the current request
     * @param offset how many matches were skipped
     * @param limit how many matches were requested
     * @param counts the counts computed while writing the rows: rows written, total matches, and whether the total
     *            is approximate
     */
    private void writeSummary(final JsonGenerator json, final SlingJakartaHttpServletRequest request,
        final long offset, final long limit, final long[] counts)
    {
        final String req = request.getParameter("req");
        if (req != null) {
            json.write("req", req);
        }
        json.write("offset", offset);
        json.write("limit", limit);
        json.write("returnedrows", counts[0]);
        json.write("totalrows", counts[1]);
        json.write("totalIsApproximate", counts[2] == 1);
    }

    /**
     * Writes an error response as a small JSON object.
     *
     * @param response the HTTP response
     * @param status the HTTP status code to send
     * @param message the error message to include in the response
     * @throws IOException if writing the response fails
     */
    private void writeError(final SlingJakartaHttpServletResponse response, final int status, final String message)
        throws IOException
    {
        response.setStatus(status);
        try (JsonGenerator json = Json.createGenerator(response.getWriter())) {
            json.writeStartObject();
            json.write("error", Objects.requireNonNullElse(message, "Invalid request"));
            json.writeEnd().flush();
        }
    }

    /**
     * Converts a request parameter, which may be missing or invalid, into a proper long, with fallback to a default
     * value.
     *
     * @param value the string to convert, may be {@code null} or not a number
     * @param defaultValue the value to use if the input cannot be converted to a number
     * @return the parsed input, if valid, or the default value
     */
    private long parseLong(final String value, final long defaultValue)
    {
        try {
            return Long.parseLong(value);
        } catch (final NumberFormatException e) {
            return defaultValue;
        }
    }
}

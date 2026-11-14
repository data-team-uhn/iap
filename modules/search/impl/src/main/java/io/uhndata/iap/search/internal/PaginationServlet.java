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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
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
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.principals.api.PrincipalContext;
import io.uhndata.iap.principals.api.PrincipalService;
import io.uhndata.iap.utils.JsonResponses;
import io.uhndata.iap.utils.PaginatedJsonResponse;
import io.uhndata.iap.utils.UserIds;

/**
 * Lists, a page at a time, the entities stored under an entity homepage, such as
 * {@code /Submissions.paginate.json}. It is registered on {@code data/EntityHomepage} with the {@code paginate}
 * selector, and a homepage type reaches it through its {@code sling:resourceSuperType}.
 *
 * <p>
 * The listed entities are the nodes of the homepage's {@code childNodeType} under it. A homepage with no
 * {@code childNodeType} lists the type its resource type names: {@code sub/SubmissionsHomepage} lists
 * {@code sub:Submission}.
 * </p>
 *
 * <p>
 * The parameters are:
 * </p>
 * <ul>
 * <li>{@code offset}: how many matching entities to skip, {@code 0} by default.</li>
 * <li>{@code limit}: how many entities to return at most, {@code 10} by default; {@code 0} only counts the
 * matches.</li>
 * <li>{@code sortBy}: the property to order the results by, {@code jcr:created} by default.</li>
 * <li>{@code descending}: {@code true} to reverse the order.</li>
 * <li>{@code filter}: a full-text expression the entities must match.</li>
 * <li>{@code fieldName}, {@code fieldComparator}, {@code fieldValue}: repeatable triples, each a condition on a
 * property of the entity, such as {@code createdBy = @me}. The comparators are {@code =}, {@code <>}, {@code <},
 * {@code <=}, {@code >}, {@code >=}, {@code LIKE}, {@code NOT LIKE}, {@code ILIKE} (a {@code LIKE} that ignores
 * case), {@code NOT ILIKE}, {@code IS NULL} and {@code IS NOT NULL}. A missing or unknown comparator means
 * {@code =}. A value starting with {@code @} is a special name, described below.</li>
 * <li>{@code fieldGroup}: optional group names, one per triple. Conditions in the same group are ORed together, and
 * the groups and the ungrouped conditions are ANDed. Two conditions on {@code createdBy} in one group match an
 * entity created by either person.</li>
 * <li>{@code childType}, {@code childFieldName}, {@code childFieldComparator}, {@code childFieldValue},
 * {@code childFieldGroup}: the same, for conditions on a descendant of the given type. An entity matches if it has
 * such a descendant that meets the conditions, such as a {@code sub:Review} with {@code reviewer = @me}.</li>
 * <li>{@code childTypeN}, {@code childFieldNName}, {@code childFieldNComparator}, {@code childFieldNValue},
 * {@code childFieldNGroup}, where {@code N} is any number: more families of descendant conditions, such as
 * {@code childType1=sub:Review&childField1Name=reviewer&childField1Value=@me}. Families naming different types each
 * need a descendant of their own. Families naming the same type add to one descendant's conditions.</li>
 * <li>{@code resourceSelectors}: extra selectors to serialize each entity with, such as {@code deep}.</li>
 * <li>{@code req}: an opaque request identifier, echoed back so that the client can discard out-of-order
 * responses.</li>
 * </ul>
 *
 * <p>
 * A filter value starting with {@code @} is a special name, and the condition compares the property with whoever it
 * stands for. {@link PrincipalService#ME_SPECIAL_NAME} is the person asking, under the user id the repository
 * records. {@link PrincipalService#MY_PRINCIPALS} is everything they act as: their user id and every group they are
 * in. It matches a property that names principals, such as a task's {@code performers}. The
 * {@link PrincipalService principals service} resolves every other special name.
 * </p>
 *
 * <p>
 * A name standing for several principals becomes several conditions ORed together. A name standing for nobody is
 * compared as written, and matches nothing. Each name is resolved once for the whole listing, with the homepage as
 * the resource it concerns: {@code @creator} stands for the creator of the homepage, not of each entity.
 * </p>
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

    /** Matches the parameter names of the descendant condition families, and captures the family's number. */
    private static final Pattern CHILD_PARAMETER =
        Pattern.compile("childType(\\d*+)|childField(\\d*+)(?:Name|Comparator|Value|Group)");

    /** Resolves the special filter values. */
    @Reference
    private transient PrincipalService principals;

    @Override
    public void doGet(final SlingJakartaHttpServletRequest request, final SlingJakartaHttpServletResponse response)
        throws IOException
    {
        try {
            final RowIterator rows = prepareQuery(request).execute().getRows();
            writeResponse(request, response, rows);
        } catch (final MisalignedParameterException e) {
            JsonResponses.writeError(response, SlingJakartaHttpServletResponse.SC_BAD_REQUEST,
                "`" + e.getParameter() + "` must be sent once per filter name");
        } catch (final IllegalArgumentException e) {
            // A name the query cannot use, or a malformed full-text expression. The message quotes the request and
            // the repository's own grammar, so it stays in the log, and a client's mistake is not a fault.
            LOGGER.debug("Refused a pagination request: {}", e.getMessage(), e);
            JsonResponses.writeError(response, SlingJakartaHttpServletResponse.SC_BAD_REQUEST,
                "The filter, sort or full-text parameters could not be understood");
        } catch (final RepositoryException e) {
            LOGGER.warn("Failed to execute pagination query: {}", e.getMessage(), e);
            JsonResponses.writeError(response, SlingJakartaHttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Failed to execute query");
        }
    }

    /**
     * Builds the query for the request: the entities of the homepage's child type under the homepage, filtered as
     * the request asks.
     *
     * @param request the current request, targeting an entity homepage
     * @return a query ready to be executed
     * @throws RepositoryException if the resource resolver has no JCR session, or the statement is invalid
     * @throws IllegalArgumentException if the request parameters are invalid
     */
    private Query prepareQuery(final SlingJakartaHttpServletRequest request) throws RepositoryException
    {
        final Session session = request.getResourceResolver().adaptTo(Session.class);
        if (session == null) {
            throw new RepositoryException("The resource resolver is not backed by a JCR session");
        }
        final Resource homepage = request.getResource();
        // Under the id the repository records, so `@me` matches values written under a differently
        // capitalised login
        final PrincipalContext asking =
            new PrincipalContext(homepage, UserIds.canonical(request.getResourceResolver()));
        final QueryBuilder builder = QueryBuilder.forEntitiesOf(homepage)
            .withFilters(parseFilters(request, "field", asking));
        for (final String suffix : getChildFilterSuffixes(request)) {
            builder.withChildFilters(request.getParameter("childType" + suffix),
                parseFilters(request, "childField" + suffix, asking));
        }
        final BoundStatement bound = builder
            .withFullText(request.getParameter("filter"))
            .withSort(request.getParameter("sortBy"), Boolean.parseBoolean(request.getParameter("descending")))
            .build();
        LOGGER.debug("Pagination query: {}", bound.statement());
        return bound.createQuery(session);
    }

    /**
     * Collects the descendant condition families the request sends: the empty suffix for the plain
     * {@code childType}/{@code childField*} parameters, and one number per {@code childTypeN}/{@code childFieldN*}
     * family. The suffixes come back in numeric order, the plain family first, so the same request always makes the
     * same joins.
     *
     * @param request the current request
     * @return the family suffixes; empty if the request sends no descendant conditions
     */
    private Collection<String> getChildFilterSuffixes(final SlingJakartaHttpServletRequest request)
    {
        // By length, then as text: numeric order for numbers without leading zeros, without parsing a number that
        // may overflow
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
     * Reads one family of repeatable name, comparator, value and group parameters as filters.
     *
     * @param request the current request
     * @param prefix the parameter name prefix, {@code field} for conditions on the entity itself, {@code childField}
     *            for conditions on a descendant node
     * @param asking what the special names are resolved against: the homepage, and who is asking
     * @return the filters; empty if the request sends no names with the given prefix
     * @throws MisalignedParameterException if the values are not sent once per name, or if the comparators or the
     *             groups are sent, but not once per name
     */
    private List<Filter> parseFilters(final SlingJakartaHttpServletRequest request, final String prefix,
        final PrincipalContext asking)
    {
        final String[] names = request.getParameterValues(prefix + "Name");
        if (names == null) {
            return List.of();
        }
        final String[] values = getAlignedParameter(request, prefix + "Value", names.length);
        final String[] comparators = getAlignedParameter(request, prefix + "Comparator", names.length);
        final String[] groups = getAlignedParameter(request, prefix + "Group", names.length);
        if (values == null) {
            throw new MisalignedParameterException(prefix + "Value");
        }
        final List<Filter> result = new ArrayList<>(names.length);
        for (int i = 0; i < names.length; ++i) {
            final String comparator = comparators == null ? "=" : comparators[i];
            final String group = groups == null || groups[i].isEmpty() ? null : groups[i];
            if (values[i].startsWith("@")) {
                // A group of its own, so the OR stands alone, unless the request named a group to join
                final String orGroup = group == null ? values[i] + i : group;
                result.addAll(orOver(names[i], comparator, orGroup, whoIsMeant(values[i], request, asking)));
            } else {
                result.add(new Filter(names[i], comparator, values[i], group));
            }
        }
        return result;
    }

    /**
     * One condition per principal a special name stands for, all in the same group, so that they are ORed together.
     *
     * @param name the property to compare
     * @param comparator the comparator of each condition, as the request sent it
     * @param group the name of the group they all belong to
     * @param who the principal names the special name stands for
     * @return the filters, one per principal
     */
    private static List<Filter> orOver(final String name, final String comparator, final String group,
        final List<String> who)
    {
        return who.stream()
            .map(principal -> new Filter(name, comparator, principal, group))
            .toList();
    }

    /**
     * Who a special filter value stands for in this request. The session answers {@code @myPrincipals}, and the
     * principals service resolves every other name.
     *
     * <p>A name that stands for nobody is kept as written, so the condition matches nothing. Dropping the condition
     * instead would widen the listing to everybody's entities.</p>
     *
     * @param value the filter value, starting with {@code @}
     * @param request the current request, whose session {@code @myPrincipals} describes
     * @param asking what the name is resolved against: the homepage, and who is asking
     * @return the principal names to compare the property with; the value itself if it stands for nobody
     */
    private List<String> whoIsMeant(final String value, final SlingJakartaHttpServletRequest request,
        final PrincipalContext asking)
    {
        final List<String> who = PrincipalService.MY_PRINCIPALS.equals(value)
            ? this.principals.principalsOf(request.getResourceResolver())
            : this.principals.resolve(List.of(value), asking);
        return who.isEmpty() ? List.of(value) : who;
    }

    /**
     * Reads one of the repeatable parameters that go with the filter names. If it is sent at all, it must be sent
     * once per name.
     *
     * @param request the current request
     * @param name the parameter name
     * @param expectedLength the number of filter names the parameter must align with
     * @return the parameter values; {@code null} if the request does not send the parameter
     * @throws MisalignedParameterException if the parameter is present a different number of times
     */
    private String[] getAlignedParameter(final SlingJakartaHttpServletRequest request, final String name,
        final int expectedLength)
    {
        final String[] values = request.getParameterValues(name);
        if (values != null && values.length != expectedLength) {
            throw new MisalignedParameterException(name);
        }
        return values;
    }

    /**
     * Writes the requested page of serialized entities, then the pagination summary.
     *
     * <p>
     * The query has already run, but its result set is lazy, so reading the rows can fail after part of the response
     * has gone out. Such a failure is reported in the summary, and the response stays a well-formed document.
     * </p>
     *
     * @param request the current request
     * @param response the HTTP response to write into
     * @param rows the query results to paginate over
     * @throws IOException if writing the response fails
     */
    // A page of results is content, streamed as it is read
    @SuppressWarnings("checkstyle:directResponseWriter")
    private void writeResponse(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response, final RowIterator rows)
        throws IOException
    {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        // Closing the generator closes the writer too
        try (JsonGenerator json = Json.createGenerator(response.getWriter())) {
            json.writeStartObject();
            json.writeStartArray("rows");
            final PaginatedJsonResponse page = PaginatedJsonResponse.forRequest(json, request);
            String error = null;
            try {
                writeRows(page, rows, request);
            } catch (final RepositoryException | RuntimeException e) {
                // Unchecked too: Oak reports a read or memory limit reached with an unchecked exception
                LOGGER.warn("Failed to read the results of a pagination query: {}", e.getMessage(), e);
                error = "Failed to read all the results";
            }
            json.writeEnd();
            page.writeSummary(request.getParameter("req"), error);
            json.writeEnd().flush();
        }
    }

    /**
     * Offers the query results to the paginator, which writes the requested page and counts the matches. A
     * descendant join returns the same entity once per matching descendant, and the paginator drops the duplicates
     * by path.
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
     * @param selectors the extra serialization selectors the client asked for, such as {@code .deep}; empty for none
     * @return the serialized entity; {@code null} if it cannot be serialized, and then it is left out of the page but
     *         still counted
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
     * A filter parameter sent a different number of times than the filter names it goes with. It names the parameter,
     * so the answer can say which one is wrong without quoting an exception's message.
     *
     * @since 0.1.0
     */
    private static final class MisalignedParameterException extends IllegalArgumentException
    {
        private static final long serialVersionUID = 1L;

        private final String parameter;

        /**
         * Reports a misaligned parameter.
         *
         * @param parameter the parameter's name, such as {@code fieldValue} or {@code childField2Group}
         */
        MisalignedParameterException(final String parameter)
        {
            super("The " + parameter + " parameter must be sent once per filter name");
            this.parameter = parameter;
        }

        /**
         * The parameter sent the wrong number of times.
         *
         * @return the parameter's name, such as {@code fieldValue}
         */
        String getParameter()
        {
            return this.parameter;
        }
    }
}

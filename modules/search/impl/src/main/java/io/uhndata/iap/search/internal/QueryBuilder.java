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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.Resource;

import io.uhndata.iap.search.api.DescendantFilters;
import io.uhndata.iap.search.api.EntityListing;
import io.uhndata.iap.search.api.Filter;
import io.uhndata.iap.search.api.Operator;

/**
 * Assembles the JCR-SQL2 statement for a pagination request: nodes of one type under a scope path, optionally
 * filtered by their own properties and by those of descendant nodes, ordered by one of their properties.
 *
 * <p>
 * A requested value never reaches the statement. Each becomes a bind variable. A caller can still put a name into
 * the statement: a node type, a property, the property to order by. JCR-SQL2 cannot bind a name, so those are
 * checked against {@link #SAFE_NAME}.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class QueryBuilder
{
    /**
     * A safe JCR node type or property name: an optionally namespace-prefixed name with no characters that could
     * break out of the surrounding query syntax.
     */
    private static final Pattern SAFE_NAME = Pattern.compile("[\\w.-]+(:[\\w.-]+)?");

    private final String nodeType;

    private final String scopePath;

    private List<Filter> filters = List.of();

    private List<DescendantFilters> descendants = List.of();

    private String fullText;

    private String sortBy = "jcr:created";

    private boolean descending;

    /**
     * Simple constructor.
     *
     * @param nodeType the node type of the queried nodes, e.g. {@code sub:Submission}
     * @param scopePath the repository path under which the nodes are looked for
     * @throws IllegalArgumentException if the node type is not a valid name
     * @throws NullPointerException if no scope path is given; a query with no scope would list the whole
     *             repository, so this fails where the mistake is rather than where it would be noticed
     */
    QueryBuilder(final String nodeType, final String scopePath)
    {
        this.nodeType = checkName(nodeType);
        this.scopePath = Objects.requireNonNull(scopePath, "A pagination query needs a scope path");
    }

    /**
     * Starts a query for the entities a listing asks for.
     *
     * @param listing the entities asked for
     * @return a builder holding every condition, the full text search and the order of the listing
     * @throws IllegalArgumentException if a node type or property name in the listing is not a valid name
     */
    static QueryBuilder forListing(final EntityListing listing)
    {
        return new QueryBuilder(listing.entityType(), listing.scopePath())
            .withFilters(listing.filters())
            .withFullText(listing.fullText())
            .withSort(listing.sortBy(), listing.descending())
            .withDescendants(listing.descendants());
    }

    /**
     * The type of nodes a homepage lists. That is its {@code childNodeType} property when it has one. Otherwise it is
     * derived from the resource type: {@code sub/SubmissionsHomepage} lists {@code sub:Submission}.
     *
     * @param homepage the homepage to read
     * @return a node type name
     */
    static String childNodeType(final Resource homepage)
    {
        final String explicit = homepage.getValueMap().get("childNodeType", String.class);
        if (explicit != null) {
            return explicit;
        }
        return homepage.getResourceType().replace('/', ':').replaceFirst("sHomepage$", "");
    }

    /**
     * Adds conditions on properties of the queried nodes themselves.
     *
     * @param newFilters the conditions to impose, may be empty
     * @return this builder, for chaining
     */
    QueryBuilder withFilters(final List<Filter> newFilters)
    {
        this.filters = newFilters;
        return this;
    }

    /**
     * Sets the conditions on descendants of the queried nodes. A queried node matches only if, for each family, it
     * has a descendant of the family's type satisfying all of the family's conditions. Each family requires a
     * descendant of its own, also for a type already used: submissions with an answer to one question and an answer
     * to another are two families on {@code sub:Answer}, which one answer node could never satisfy together.
     *
     * @param newDescendants the descendant condition families, may be empty
     * @return this builder, for chaining
     * @throws IllegalArgumentException if a family's type is not a valid name
     */
    QueryBuilder withDescendants(final List<DescendantFilters> newDescendants)
    {
        newDescendants.forEach(family -> checkName(family.type()));
        this.descendants = List.copyOf(newDescendants);
        return this;
    }

    /**
     * Adds a full text search condition on the queried nodes.
     *
     * @param newFullText the text to search for, may be {@code null} or blank if no full text search is needed
     * @return this builder, for chaining
     */
    QueryBuilder withFullText(final String newFullText)
    {
        this.fullText = newFullText;
        return this;
    }

    /**
     * Sets the ordering of the results. By default results are ordered by creation date, oldest first.
     *
     * @param newSortBy the property of the queried nodes to order by; may be {@code null} or blank to keep the
     *            default {@code jcr:created}
     * @param newDescending {@code true} to reverse the order
     * @return this builder, for chaining
     * @throws IllegalArgumentException if the property to order by is not a valid name
     */
    QueryBuilder withSort(final String newSortBy, final boolean newDescending)
    {
        if (newSortBy != null && !newSortBy.isBlank()) {
            this.sortBy = checkName(newSortBy);
        }
        this.descending = newDescending;
        return this;
    }

    /**
     * Assembles the final JCR-SQL2 statement and the values to bind into it.
     *
     * @return a valid JCR-SQL2 statement together with the value of every bind variable it names
     */
    BoundStatement build()
    {
        return build(true);
    }

    /**
     * Assembles the statement without an ordering, for a caller that only counts the matches.
     *
     * @return a valid JCR-SQL2 statement together with the value of every bind variable it names
     */
    BoundStatement buildCount()
    {
        return build(false);
    }

    private BoundStatement build(final boolean ordered)
    {
        final Map<String, String> bindings = new LinkedHashMap<>();
        final StringBuilder query = new StringBuilder("select n.* from [").append(this.nodeType).append("] as n");
        int childIndex = 0;
        for (final DescendantFilters family : this.descendants) {
            final String alias = "c" + childIndex++;
            query.append(" inner join [").append(family.type()).append("] as ").append(alias)
                .append(" on isdescendantnode(").append(alias).append(", n)");
        }
        // The scope path is the one value that has to stay in the statement: isdescendantnode takes a path, not a
        // static operand, so JCR-SQL2 will not accept a bind variable there. It is the resolved path of the
        // homepage being listed rather than anything a caller sends, and it is escaped for the literal it is.
        query.append(" where isdescendantnode(n, '").append(escape(this.scopePath)).append("')");
        appendConditions(query, "n", this.filters, bindings);
        childIndex = 0;
        for (final DescendantFilters family : this.descendants) {
            appendConditions(query, "c" + childIndex++, family.filters(), bindings);
        }
        if (this.fullText != null && !this.fullText.isBlank()) {
            query.append(" and contains(n.*, ").append(bind(bindings, escapeFullText(this.fullText))).append(')');
        }
        if (ordered) {
            query.append(" order by n.[").append(this.sortBy).append(this.descending ? "] DESC" : "] ASC");
        }
        return new BoundStatement(query.toString(), Map.copyOf(bindings));
    }

    private static void appendConditions(final StringBuilder query, final String source, final List<Filter> filters,
        final Map<String, String> bindings)
    {
        for (final List<Filter> group : Filter.groups(filters)) {
            query.append(" and (")
                .append(group.stream().map(filter -> condition(source, filter, bindings))
                    .collect(Collectors.joining(" or ")))
                .append(')');
        }
    }

    /**
     * Records a value to bind and returns the reference naming it. Variables are numbered in the order they are
     * added, so the same request always produces the same statement.
     *
     * @param bindings the values collected so far, extended by one
     * @param value the value to bind
     * @return the bind variable reference to write into the statement, e.g. {@code $p0}
     */
    private static String bind(final Map<String, String> bindings, final String value)
    {
        final String name = "p" + bindings.size();
        bindings.put(name, value);
        return "$" + name;
    }

    private static String condition(final String source, final Filter filter, final Map<String, String> bindings)
    {
        final Operator comparator = filter.getComparator();
        final String property = source + ".[" + checkName(filter.getName()) + "]";
        if (comparator.isValueless()) {
            return JcrOperators.condition(comparator, property, null);
        }
        return JcrOperators.condition(comparator, property,
            bind(bindings, JcrOperators.boundValue(comparator, value(filter))));
    }

    /**
     * The value a filter compares against, as a string. A filter with no value compares against an empty one, which
     * is what an absent request parameter used to mean once it had been escaped into the statement.
     *
     * @param filter the filter whose value is wanted
     * @return the value, never {@code null}
     */
    private static String value(final Filter filter)
    {
        return filter.getValue() == null ? "" : filter.getValue();
    }

    /**
     * Validates a node type or property name before it is interpolated into a query.
     *
     * @param name the name to validate
     * @return the name itself, if valid
     * @throws IllegalArgumentException if the name contains characters that aren't part of a plain JCR name
     */
    private static String checkName(final String name)
    {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid name in query: " + name);
        }
        return name;
    }

    /**
     * Escapes a literal value before it is interpolated into a query string. Doubling the quote is JCR-SQL2's only
     * string escape. A backslash is an ordinary character in a string literal, and {@code \'} is a parse error.
     *
     * <p>
     * Only the scope path is still written into the statement, every other value being bound instead, so this is
     * the one place the escaping has to be right rather than one of several.
     * </p>
     *
     * @param value the value to escape
     * @return the value with quotes doubled
     */
    private static String escape(final String value)
    {
        return value.replace("'", "''");
    }

    /**
     * Escapes a full text search term before it is bound into a {@code contains()} call. Binding removes the string
     * literal's escaping, not this one. The full text grammar is applied to whatever the bind variable holds. The
     * backslash escapes, and both quote characters open a phrase. A trailing backslash escapes the closing quote.
     * An odd number of quotes leaves a phrase unterminated. Either fails the query to parse.
     *
     * <p>
     * The grammar's <em>meaning</em> is left alone. A leading {@code -} still excludes a term, and {@code OR} still
     * reads as the operator. Making them literal would remove the only way to ask for them.
     * </p>
     *
     * <p>
     * The term is stripped first. A full text expression has to start with a term. A leading space makes it fail to
     * parse, and the whole listing comes back as a bad request. A trailing space, and any amount of space between
     * the words, are already fine.
     * </p>
     *
     * @param value the value to escape, never {@code null} or blank; the only caller has already checked that there
     *            is a term to search for
     * @return the escaped search expression
     */
    private static String escapeFullText(final String value)
    {
        // Backslash first, or it would escape the escapes added after it
        return value.strip().replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'");
    }

}

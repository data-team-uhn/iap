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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Assembles the JCR-SQL2 statement for a pagination request: nodes of one type under a scope path, optionally
 * filtered by their own properties and by the properties of descendant nodes, ordered by one of their properties.
 *
 * <p>
 * Requested values never reach the statement. Each one becomes a bind variable, so nothing the caller sends has to
 * be escaped to be safe, and the statement a request produces depends only on the <em>shape</em> of that request.
 * What a caller can still put into the statement itself is a name — a node type, a property, the property to order
 * by — and those are checked against {@link #SAFE_NAME} rather than escaped, since JCR-SQL2 offers no way to bind
 * them.
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

    private final Map<String, List<Filter>> childFilters = new LinkedHashMap<>();

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
     * Adds conditions on the properties of a descendant node: a queried node only matches if it has at least one
     * descendant of the given type satisfying all of the given conditions. Each call adds a new required
     * descendant, so calling this multiple times with distinct types requires one matching descendant per type;
     * calling it again with an already required type adds to that type's conditions. A call with a {@code null} or
     * blank type and no conditions is a no-op, leaving previously added descendant conditions untouched.
     *
     * @param newChildType the node type of the descendant, e.g. {@code sub:Review}; may be {@code null} or blank if
     *            no descendant conditions are needed
     * @param newChildFilters the conditions to impose on the descendant, may be {@code null} or empty
     * @return this builder, for chaining
     * @throws IllegalArgumentException if conditions are given without a descendant node type, or the type is not a
     *             valid name
     */
    QueryBuilder withChildFilters(final String newChildType, final List<Filter> newChildFilters)
    {
        final List<Filter> toAdd = newChildFilters == null ? List.of() : newChildFilters;
        if (newChildType == null || newChildType.isBlank()) {
            if (!toAdd.isEmpty()) {
                throw new IllegalArgumentException("Child filters require a childType parameter");
            }
            return this;
        }
        this.childFilters.computeIfAbsent(checkName(newChildType), key -> new ArrayList<>()).addAll(toAdd);
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
    BoundQuery build()
    {
        final Map<String, String> bindings = new LinkedHashMap<>();
        final StringBuilder query = new StringBuilder("select n.* from [").append(this.nodeType).append("] as n");
        int childIndex = 0;
        for (final String childType : this.childFilters.keySet()) {
            final String alias = "c" + childIndex++;
            query.append(" inner join [").append(childType).append("] as ").append(alias)
                .append(" on isdescendantnode(").append(alias).append(", n)");
        }
        // The scope path is the one value that has to stay in the statement: isdescendantnode takes a path, not a
        // static operand, so JCR-SQL2 will not accept a bind variable there. It is the resolved path of the
        // homepage being listed rather than anything a caller sends, and it is escaped for the literal it is.
        query.append(" where isdescendantnode(n, '").append(escape(this.scopePath)).append("')");
        appendConditions(query, "n", this.filters, bindings);
        childIndex = 0;
        for (final List<Filter> filtersForChild : this.childFilters.values()) {
            appendConditions(query, "c" + childIndex++, filtersForChild, bindings);
        }
        if (this.fullText != null && !this.fullText.isBlank()) {
            query.append(" and contains(n.*, ").append(bind(bindings, escapeFullText(this.fullText))).append(')');
        }
        query.append(" order by n.[").append(this.sortBy).append(this.descending ? "] DESC" : "] ASC");
        return new BoundQuery(query.toString(), Map.copyOf(bindings));
    }

    private static void appendConditions(final StringBuilder query, final String source, final List<Filter> filters,
        final Map<String, String> bindings)
    {
        for (final List<Filter> group : groupFilters(filters)) {
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

    /**
     * Collects the filters into their groups: filters sharing a group id end up ORed together, while each ungrouped
     * filter stands on its own. The order of the resulting groups follows the order in which they first appear.
     *
     * @param filters the filters to group
     * @return a list of non-empty filter groups
     */
    private static List<List<Filter>> groupFilters(final List<Filter> filters)
    {
        final List<List<Filter>> groups = new ArrayList<>();
        final Map<String, List<Filter>> namedGroups = new HashMap<>();
        for (final Filter filter : filters) {
            if (filter.getGroup() == null) {
                groups.add(List.of(filter));
            } else {
                namedGroups.computeIfAbsent(filter.getGroup(), key -> {
                    final List<Filter> group = new ArrayList<>();
                    groups.add(group);
                    return group;
                }).add(filter);
            }
        }
        return groups;
    }

    private static String condition(final String source, final Filter filter, final Map<String, String> bindings)
    {
        final Operator comparator = filter.getComparator();
        final String property = source + ".[" + checkName(filter.getName()) + "]";
        if (comparator.isValueless()) {
            return comparator.apply(property, null);
        }
        return comparator.apply(property, bind(bindings, comparator.prepareValue(value(filter))));
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
     * Escapes a literal value before it is interpolated into a query string. Doubling the quote is JCR-SQL2's
     * only string escape: backslashes are ordinary characters in a string literal, and a {@code \'} sequence is
     * a parse error, which used to turn every value containing an apostrophe into a failed query.
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
     * literal's escaping, not this: the full text grammar is applied to whatever the bind variable holds, so its own
     * layer still has to be neutralized. The backslash escapes, and both quote characters open a phrase — a trailing
     * backslash would escape the closing quote, and an odd number of quotes, one inch mark or one apostrophe's worth
     * of ordinary typing, would leave a phrase unterminated and fail the query to parse.
     *
     * <p>
     * What this deliberately leaves alone is the grammar's <em>meaning</em>, as opposed to its syntax: a leading
     * {@code -} still excludes a term and {@code OR} still reads as the operator, so a search reaching those is
     * answered oddly rather than refused. Making them literal would take away the only way to ask for them.
     * </p>
     *
     * <p>
     * The term is stripped first. A full text expression has to start with a term, so a leading space — which is
     * what a paste into the search box, or an autocompletion, routinely leaves in front of what was typed — makes
     * the expression fail to parse and the whole listing come back as a bad request, for input that is perfectly
     * good. A trailing space, and any amount of space between the words, are already fine.
     * </p>
     *
     * @param value the value to escape, never {@code null} and never blank: the only caller has already established
     *            that there is a term to search for
     * @return the escaped search expression
     */
    private static String escapeFullText(final String value)
    {
        // Backslash first, or it would escape the escapes added after it
        return value.strip().replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'");
    }

    /**
     * A JCR-SQL2 statement together with the value of every bind variable it names. Keeping the two together is what
     * lets the statement be assembled without ever holding a requested value.
     *
     * @param statement the statement, naming one bind variable per value
     * @param bindings the value of each bind variable, keyed by name without the {@code $}
     *
     * @version $Id$
     * @since 0.1.0
     */
    record BoundQuery(String statement, Map<String, String> bindings)
    {
    }
}

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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.Resource;

/**
 * Builds the JCR-SQL2 statement that lists the entities under a homepage. The statement selects the nodes of one type
 * under a scope path, filters them by their own properties and those of their descendants, and orders them.
 *
 * <p>
 * Every requested value becomes a bind variable. A request can also name a node type, a property, or the property
 * to order by. JCR-SQL2 cannot bind a name, so each name is checked against {@link #SAFE_NAME} instead.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class QueryBuilder
{
    /**
     * A node type or property name that is safe to write into a statement: word characters, dots and dashes, with an
     * optional namespace prefix. Nothing in it can end the brackets around it.
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
     * Starts a statement for the nodes of a type under a path.
     *
     * @param nodeType the node type of the listed nodes, such as {@code sub:Submission}
     * @param scopePath the path the nodes are listed under, such as {@code /Submissions}
     * @throws IllegalArgumentException if the node type is not a valid name
     * @throws NullPointerException if the scope path is {@code null}
     */
    QueryBuilder(final String nodeType, final String scopePath)
    {
        this.nodeType = checkName(nodeType);
        this.scopePath = Objects.requireNonNull(scopePath, "A pagination query needs a scope path");
    }

    /**
     * Starts a statement for the entities a homepage holds: the nodes of its child type, under it.
     *
     * @param homepage the homepage whose entities are listed
     * @return a builder scoped to that homepage
     * @throws IllegalArgumentException if the homepage's child type is not a valid name
     */
    static QueryBuilder forEntitiesOf(final Resource homepage)
    {
        return new QueryBuilder(childNodeType(homepage), homepage.getPath());
    }

    /**
     * The type of nodes a homepage lists. That is its {@code childNodeType} property when it has one. Otherwise it is
     * derived from the resource type: {@code sub/SubmissionsHomepage} lists {@code sub:Submission}.
     *
     * @param homepage the homepage to read
     * @return a node type name, such as {@code sub:Submission}
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
     * Sets the conditions on the properties of the listed nodes themselves.
     *
     * @param newFilters the conditions; empty for none
     * @return this builder, for chaining
     */
    QueryBuilder withFilters(final List<Filter> newFilters)
    {
        this.filters = newFilters;
        return this;
    }

    /**
     * Adds conditions on the properties of a descendant. A listed node matches only if it has a descendant of the
     * given type that meets the conditions. Each type needs a descendant of its own. Calling this again with the
     * same type adds to that type's conditions. A {@code null} or blank type with no conditions changes nothing.
     *
     * @param newChildType the node type of the descendant, such as {@code sub:Review}; {@code null} or blank for
     *            none
     * @param newChildFilters the conditions on the descendant; {@code null} or empty for none
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
     * Sets a full-text condition on the listed nodes.
     *
     * @param newFullText the full-text expression to look for; {@code null} or blank for none
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
     * @param newSortBy the property of the listed nodes to order by; {@code null} or blank to keep the default,
     *            {@code jcr:created}
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
     * Builds the statement and the values to bind into it.
     *
     * @return the JCR-SQL2 statement, with the value of every bind variable it names
     */
    BoundStatement build()
    {
        return build(true);
    }

    /**
     * Builds the statement without an ordering, to count the matches.
     *
     * @return the JCR-SQL2 statement, with the value of every bind variable it names
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
        for (final String childType : this.childFilters.keySet()) {
            final String alias = "c" + childIndex++;
            query.append(" inner join [").append(childType).append("] as ").append(alias)
                .append(" on isdescendantnode(").append(alias).append(", n)");
        }
        // Written into the statement, not bound: isdescendantnode takes a path, not a static operand, and JCR-SQL2
        // accepts no bind variable there. The path is the homepage's own, not anything the request sends.
        query.append(" where isdescendantnode(n, '").append(escape(this.scopePath)).append("')");
        appendConditions(query, "n", this.filters, bindings);
        childIndex = 0;
        for (final List<Filter> filtersForChild : this.childFilters.values()) {
            appendConditions(query, "c" + childIndex++, filtersForChild, bindings);
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
     * @return the bind variable reference to write into the statement, such as {@code $p0}
     */
    private static String bind(final Map<String, String> bindings, final String value)
    {
        final String name = "p" + bindings.size();
        bindings.put(name, value);
        return "$" + name;
    }

    /**
     * Collects the filters into their groups. Filters sharing a group are ORed together, and an ungrouped filter is
     * a group of its own. A group takes the place of its first filter.
     *
     * @param filters the filters to group
     * @return the groups, none of them empty
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
     * The value a filter compares against. A filter with no value compares against the empty string.
     *
     * @param filter the filter whose value is wanted
     * @return the value; the empty string if the filter has none
     */
    private static String value(final Filter filter)
    {
        return filter.getValue() == null ? "" : filter.getValue();
    }

    /**
     * Checks a node type or property name before it is written into a statement.
     *
     * @param name the name to check
     * @return the name itself
     * @throws IllegalArgumentException if the name is {@code null}, or does not match {@link #SAFE_NAME}
     */
    private static String checkName(final String name)
    {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid name in query: " + name);
        }
        return name;
    }

    /**
     * Escapes a value for a string literal in a statement. Doubling the quote is JCR-SQL2's only string escape. A
     * backslash is an ordinary character in a string literal, and {@code \'} is a parse error.
     *
     * @param value the value to escape
     * @return the value with quotes doubled
     */
    private static String escape(final String value)
    {
        return value.replace("'", "''");
    }

    /**
     * Escapes a full-text term before it is bound into a {@code contains()} call. Binding needs no string literal
     * escaping, but the full-text grammar applies to whatever the bind variable holds. In that grammar the backslash
     * escapes, and both quote characters open a phrase. A trailing backslash or an odd number of quotes makes the
     * expression fail to parse.
     *
     * <p>
     * The operators are left alone. A leading {@code -} still excludes a term, and {@code OR} still joins two
     * terms. There would be no other way to ask for them.
     * </p>
     *
     * <p>
     * The term is stripped first, since a full-text expression must start with a term.
     * </p>
     *
     * @param value the term to escape, neither {@code null} nor blank
     * @return the escaped full-text expression
     */
    private static String escapeFullText(final String value)
    {
        // Backslash first, or it would escape the escapes added after it
        return value.strip().replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'");
    }
}

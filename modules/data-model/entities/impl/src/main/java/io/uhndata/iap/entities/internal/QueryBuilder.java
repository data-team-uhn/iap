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

import java.util.List;
import java.util.regex.Pattern;

/**
 * Assembles the JCR-SQL2 statement for a pagination request: nodes of one type under a scope path, optionally
 * filtered by their own properties and by the properties of a descendant node, ordered by one of their properties.
 * Every name interpolated into the statement is validated, and every value is escaped, so the resulting statement
 * only queries what the caller declared.
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

    private String childType;

    private List<Filter> childFilters = List.of();

    private String fullText;

    private String sortBy = "jcr:created";

    private boolean descending;

    /**
     * Simple constructor.
     *
     * @param nodeType the node type of the queried nodes, e.g. {@code sub:Submission}
     * @param scopePath the repository path under which the nodes are looked for
     * @throws IllegalArgumentException if the node type is not a valid name
     */
    QueryBuilder(final String nodeType, final String scopePath)
    {
        this.nodeType = checkName(nodeType);
        this.scopePath = scopePath;
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
     * descendant of the given type satisfying all of the given conditions.
     *
     * @param newChildType the node type of the descendant, e.g. {@code sub:Review}; may be {@code null} or blank if
     *            no descendant conditions are needed
     * @param newChildFilters the conditions to impose on the descendant, may be empty
     * @return this builder, for chaining
     * @throws IllegalArgumentException if conditions are given without a descendant node type, or the type is not a
     *             valid name
     */
    QueryBuilder withChildFilters(final String newChildType, final List<Filter> newChildFilters)
    {
        if (newChildType == null || newChildType.isBlank()) {
            if (!newChildFilters.isEmpty()) {
                throw new IllegalArgumentException("Child filters require a childType parameter");
            }
            return this;
        }
        this.childType = checkName(newChildType);
        this.childFilters = newChildFilters;
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
     * Assembles the final JCR-SQL2 statement.
     *
     * @return a valid JCR-SQL2 statement
     */
    String build()
    {
        final StringBuilder query = new StringBuilder("select n.* from [").append(this.nodeType).append("] as n");
        if (this.childType != null) {
            query.append(" inner join [").append(this.childType).append("] as c on isdescendantnode(c, n)");
        }
        query.append(" where isdescendantnode(n, '").append(escape(this.scopePath)).append("')");
        appendConditions(query, "n", this.filters);
        appendConditions(query, "c", this.childFilters);
        if (this.fullText != null && !this.fullText.isBlank()) {
            query.append(" and contains(n.*, '").append(escape(this.fullText)).append("')");
        }
        query.append(" order by n.[").append(this.sortBy).append(this.descending ? "] DESC" : "] ASC");
        return query.toString();
    }

    private static void appendConditions(final StringBuilder query, final String source, final List<Filter> filters)
    {
        for (final Filter filter : filters) {
            appendCondition(query, source, filter);
        }
    }

    private static void appendCondition(final StringBuilder query, final String source, final Filter filter)
    {
        final String property = source + ".[" + checkName(filter.getName()) + "]";
        if (filter.isValueless()) {
            query.append(" and ").append(property).append(' ').append(filter.getComparator());
        } else if ("<>".equals(filter.getComparator())) {
            // `x <> y` is evaluated on each entry of a multi-valued property and never matches an empty one;
            // `not x = y` behaves intuitively for both single and multi-valued properties
            query.append(" and not ").append(property).append(" = '").append(escape(filter.getValue())).append('\'');
        } else {
            query.append(" and ").append(property).append(' ').append(filter.getComparator()).append(" '")
                .append(escape(filter.getValue())).append('\'');
        }
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
     * Escapes a literal value before it is interpolated into a query string.
     *
     * @param value the value to escape, may be {@code null}
     * @return the value with quotes and backslashes escaped, or an empty string if the value was {@code null}
     */
    private static String escape(final String value)
    {
        return value == null ? "" : value.replaceAll("['\\\\]", "\\\\$0");
    }
}

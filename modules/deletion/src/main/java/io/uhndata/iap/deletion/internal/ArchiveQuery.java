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
package io.uhndata.iap.deletion.internal;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;

import io.uhndata.iap.utils.DateUtils;

/**
 * The JCR-SQL2 statements that list and count the archive entries under a node.
 *
 * <p>
 * Nothing a client sends reaches a statement unescaped. A column name cannot be a bound variable, so the sort column
 * is taken from a fixed set. The filter is escaped twice: once as a {@code LIKE} pattern, then as a string literal.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ArchiveQuery
{
    /** The columns an entry may be ordered by, as JCR property names. */
    static final Set<String> SORTABLE_PROPERTIES = Set.of("jcr:created", "deletedBy", "requestedPath");

    /** The column to sort by when none, or an unknown one, is asked for. */
    static final String DEFAULT_SORT = "jcr:created";

    private ArchiveQuery()
    {
        // Utility class
    }

    /**
     * The statement listing the archive entries under a node.
     *
     * @param root the absolute path to look under, usually {@code /Archive}
     * @param filter text the requested path or the requesting user must contain, ignoring case; {@code null} or
     *            blank to keep every entry
     * @param sortBy the property to sort by; anything outside {@link #SORTABLE_PROPERTIES} sorts by
     *            {@link #DEFAULT_SORT}
     * @param descending {@code true} to sort in descending order
     * @return a JCR-SQL2 statement
     */
    static String entries(final String root, final String filter, final String sortBy, final boolean descending)
    {
        final StringBuilder statement = new StringBuilder(under(root));
        if (filter != null && !filter.isBlank()) {
            final String pattern = "'%" + literal(likePattern(filter.toLowerCase(Locale.ROOT))) + "%'";
            statement.append(" AND (LOWER(entry.[requestedPath]) LIKE ").append(pattern)
                .append(" OR LOWER(entry.[deletedBy]) LIKE ").append(pattern).append(')');
        }
        statement.append(" ORDER BY entry.[").append(sortColumn(sortBy)).append(']')
            .append(descending ? " DESC" : " ASC");
        return statement.toString();
    }

    /**
     * The statement counting the archive entries under a node that were created at or after an instant.
     *
     * @param root the absolute path to look under, usually {@code /Archive}
     * @param since the earliest creation instant to count, as {@link #timestamp} formats it
     * @return a JCR-SQL2 statement
     */
    static String createdSince(final String root, final String since)
    {
        return under(root) + " AND entry.[jcr:created] >= CAST('" + literal(since) + "' AS DATE)";
    }

    /**
     * The statement counting every archive entry under a node.
     *
     * @param root the absolute path to look under, usually {@code /Archive}
     * @return a JCR-SQL2 statement
     */
    static String all(final String root)
    {
        return under(root);
    }

    /**
     * Formats an instant for {@link #createdSince}.
     *
     * @param epochMillis the instant, in milliseconds since the epoch
     * @return an ISO-8601 timestamp in UTC, such as {@code 2026-11-05T14:00:00.000+00:00}
     */
    static String timestamp(final long epochMillis)
    {
        return DateUtils.toString(Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC));
    }

    private static String under(final String root)
    {
        return "SELECT entry.* FROM [del:ArchiveEntry] AS entry WHERE ISDESCENDANTNODE(entry, '"
            + literal(root) + "')";
    }

    private static String sortColumn(final String sortBy)
    {
        return SORTABLE_PROPERTIES.contains(sortBy) ? sortBy : DEFAULT_SORT;
    }

    /**
     * Escapes a value for a JCR-SQL2 string literal, where a single quote is written twice.
     *
     * @param value the raw value
     * @return the value, ready to place between single quotes
     */
    static String literal(final String value)
    {
        return value.replace("'", "''");
    }

    /**
     * Escapes a value for a {@code LIKE} pattern, so that {@code %} and {@code _} match themselves. A pattern still
     * needs {@link #literal} to go into a statement.
     *
     * @param value the raw value
     * @return the value with {@code \}, {@code %} and {@code _} escaped by a backslash
     */
    static String likePattern(final String value)
    {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}

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
 * The JCR-SQL2 statements behind the archive viewer: one listing the archive entries under a subtree, one counting
 * those created since a given instant.
 *
 * <p>
 * Nothing a client sends is interpolated raw. The sort column is chosen from a fixed set rather than quoted, since a
 * column name cannot be a bound variable and an unrecognised one is a bug or an attack rather than a value to escape;
 * the free-text filter is escaped twice over, once for the {@code LIKE} pattern it becomes and once for the string
 * literal that carries it, because those are two different escapes and composing them at the call site is what keeps
 * them from being confused for one another.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ArchiveQuery
{
    /** The columns an entry may be ordered by, as JCR property names. */
    static final Set<String> SORTABLE = Set.of("jcr:created", "deletedBy", "requestedPath");

    /** The column used when none is asked for: most recently archived first. */
    static final String DEFAULT_SORT = "jcr:created";

    private ArchiveQuery()
    {
        // Utility class
    }

    /**
     * The statement listing the archive entries under a subtree.
     *
     * @param root the absolute path to look under, usually {@code /Archive}
     * @param filter a case-insensitive substring the requested path or the requesting user must contain; ignored
     *            when blank
     * @param sortBy the property to order by; anything outside {@link #SORTABLE} falls back to {@link #DEFAULT_SORT}
     * @param descending whether to reverse the order
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
     * The statement counting the archive entries under a subtree that were created at or after an instant.
     *
     * @param root the absolute path to look under, usually {@code /Archive}
     * @param since the earliest creation instant to count, as an ISO-8601 timestamp
     * @return a JCR-SQL2 statement
     */
    static String createdSince(final String root, final String since)
    {
        return under(root) + " AND entry.[jcr:created] >= CAST('" + literal(since) + "' AS DATE)";
    }

    /**
     * The statement counting every archive entry under a subtree.
     *
     * @param root the absolute path to look under, usually {@code /Archive}
     * @return a JCR-SQL2 statement
     */
    static String all(final String root)
    {
        return under(root);
    }

    /**
     * Formats an instant the way {@link #createdSince} expects it.
     *
     * @param epochMillis the instant, in milliseconds since the epoch
     * @return an ISO-8601 timestamp
     */
    static String timestamp(final long epochMillis)
    {
        return DateUtils.toString(Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC));
    }

    private static String under(final String root)
    {
        return "SELECT entry.* FROM [iap:ArchiveEntry] AS entry WHERE ISDESCENDANTNODE(entry, '"
            + literal(root) + "')";
    }

    private static String sortColumn(final String sortBy)
    {
        return SORTABLE.contains(sortBy) ? sortBy : DEFAULT_SORT;
    }

    /**
     * Escapes a value for a JCR-SQL2 string literal, where a quote is written twice.
     *
     * @param value the raw value
     * @return the value, safe to place between single quotes
     */
    static String literal(final String value)
    {
        return value.replace("'", "''");
    }

    /**
     * Escapes a value for use inside a {@code LIKE} pattern, so that its wildcards are matched literally. Separate
     * from {@link #literal} on purpose: this one decides what the pattern <em>means</em>, that one decides how it
     * survives being written into a statement, and a value normally needs both.
     *
     * @param value the raw value
     * @return the value with the pattern metacharacters escaped
     */
    static String likePattern(final String value)
    {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}

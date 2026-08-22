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

import java.util.ArrayList;
import java.util.List;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.query.Query;
import javax.jcr.query.RowIterator;

/**
 * Runs the archive browser's queries against the repository.
 *
 * <p>
 * Every scan is bounded. A count is the number of entries seen before the bound is reached, and the caller is told
 * whether it stopped early, so that an archive which has grown without limit slows a page down by a fixed amount
 * rather than by however much has been deleted over the years. Queries run with the session they are given — the
 * requester's — so an archive the requester cannot read simply yields nothing, which is the same rule the restore
 * and purge endpoints already rely on.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ArchiveSearch
{
    /** How many entries a single request will look at before it stops counting. */
    static final long MAX_SCAN = 10000;

    private ArchiveSearch()
    {
        // Utility class
    }

    /**
     * The number of entries a statement matches, up to a bound. The bound is supplied rather than fixed so that a
     * caller can be tested against it without first producing enough entries to reach the real one.
     *
     * @param session the session to query with
     * @param statement the JCR-SQL2 statement to run
     * @param cap how many entries to look at before stopping
     * @return how many entries matched, and whether the scan stopped at the bound
     * @throws RepositoryException if the query cannot be run
     */
    static Count count(final Session session, final String statement, final long cap) throws RepositoryException
    {
        final RowIterator rows = run(session, statement);
        long seen = 0;
        while (rows.hasNext() && seen < cap) {
            rows.nextRow();
            seen++;
        }
        return new Count(seen, rows.hasNext());
    }

    /**
     * The paths of the entries a statement matches, one page of them, plus how many there are in total.
     *
     * @param session the session to query with
     * @param statement the JCR-SQL2 statement to run
     * @param offset how many matches to skip
     * @param limit how many paths to return at most
     * @return the page of paths and the total
     * @throws RepositoryException if the query cannot be run
     */
    static Page page(final Session session, final String statement, final long offset, final long limit)
        throws RepositoryException
    {
        return page(session, statement, offset, limit, MAX_SCAN);
    }

    /**
     * One page of matches, with the scan bound supplied. Tests set the bound rather than producing enough entries
     * to reach the real one.
     *
     * @param session the session to query with
     * @param statement the JCR-SQL2 statement to run
     * @param offset how many matches to skip
     * @param limit how many paths to return at most
     * @param cap how many entries to look at before stopping
     * @return the page of paths and the total
     * @throws RepositoryException if the query cannot be run
     */
    static Page page(final Session session, final String statement, final long offset, final long limit,
        final long cap) throws RepositoryException
    {
        final RowIterator rows = run(session, statement);
        final List<String> paths = new ArrayList<>();
        long seen = 0;
        while (rows.hasNext() && seen < cap) {
            final String path = rows.nextRow().getPath();
            if (seen >= offset && paths.size() < limit) {
                paths.add(path);
            }
            seen++;
        }
        return new Page(paths, new Count(seen, rows.hasNext()));
    }

    private static RowIterator run(final Session session, final String statement) throws RepositoryException
    {
        return session.getWorkspace().getQueryManager().createQuery(statement, Query.JCR_SQL2).execute().getRows();
    }

    /**
     * How many entries were counted, and whether counting stopped before the end.
     *
     * @param value the number of entries seen
     * @param approximate whether the scan stopped at {@link #MAX_SCAN} with matches left over
     * @version $Id$
     * @since 0.1.0
     */
    record Count(long value, boolean approximate)
    {
    }

    /**
     * One page of entry paths, and the total the page was taken from.
     *
     * @param paths the paths in the page, in query order
     * @param total how many entries matched altogether
     * @version $Id$
     * @since 0.1.0
     */
    record Page(List<String> paths, Count total)
    {
    }
}

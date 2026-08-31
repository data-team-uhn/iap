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

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.query.RowIterator;

import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports a search that has no index to work with and will walk the repository instead.
 *
 * <p>
 * Purely diagnostic: nothing here may fail a request the repository would have served. It is separate from the
 * servlet because it answers a different question — not what the client asked for, but what answering it will cost —
 * and it carries its own rule about what may be written to a log.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class QueryPlanChecker
{
    private static final Logger LOGGER = LoggerFactory.getLogger(QueryPlanChecker.class);

    /** How much of a statement to write into a log message. */
    private static final int MAX_LOGGED_STATEMENT = 500;

    private QueryPlanChecker()
    {
        // Utility class, not to be instantiated
    }

    /**
     * Logs a warning if the query has no index to work with. Since the statement comes from the client, an expensive
     * one is a mistake, or an attack, that is worth being able to attribute to this endpoint; the repository logs its
     * own traversal warnings, but only once the damage is being done, and without saying who asked.
     *
     * <p>
     * Asking for the plan means planning the query twice. That is deliberate: planning is what the repository does
     * before it reads anything, and it is cheap next to a traversal, which is precisely the case this exists to
     * report.
     * </p>
     *
     * @param session the session to plan the query in
     * @param bound the statement about to be executed, already known to parse
     */
    static void warnIfUnindexed(final Session session, final BoundStatement bound)
    {
        try {
            final RowIterator plan = bound.explain(session).execute().getRows();
            if (!plan.hasNext()) {
                return;
            }
            final Value[] columns = plan.nextRow().getValues();
            if (columns.length > 0 && Strings.CI.contains(columns[0].getString(), "traverse")) {
                LOGGER.warn("The search query [{}] has no index to use and will walk the repository: {}",
                    forLog(bound.statement()), columns[0].getString());
            }
        } catch (final RepositoryException | RuntimeException e) {
            // Everything here is diagnostics; never fail a request the repository would have served. The unchecked
            // exceptions matter as much as the repository's own: a malformed full-text expression reaches the
            // repository's parser as an IllegalArgumentException, and a request that only asks for a plan it cannot
            // have is still a request that can be answered.
            LOGGER.debug("Could not obtain the plan of the search query [{}]: {}", forLog(bound.statement()),
                e.getMessage(), e);
        }
    }

    /**
     * Prepares a statement for a log message. A statement the client sent whole is logged as it is, so a line break
     * in it would let a client write log entries of its own choosing. A generated statement carries nothing the user
     * typed — the text is bound, and the bindings are never logged — so the length cap is the only part of this that
     * still applies to one.
     *
     * @param statement the statement to log
     * @return the statement on a single line, no longer than {@value #MAX_LOGGED_STATEMENT} characters
     */
    static String forLog(final String statement)
    {
        final String oneLine = statement.replaceAll("\\s+", " ");
        return oneLine.length() > MAX_LOGGED_STATEMENT
            ? oneLine.substring(0, MAX_LOGGED_STATEMENT) + "..." : oneLine;
    }
}

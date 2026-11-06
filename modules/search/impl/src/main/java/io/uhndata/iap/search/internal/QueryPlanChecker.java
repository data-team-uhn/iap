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

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;

/**
 * Reports a search that has no index to use, and walks the repository instead.
 *
 * <p>
 * It only reports. Nothing here fails a request the repository would have served.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class QueryPlanChecker
{
    /**
     * The phrase an unindexed query is recorded under. Two records are the same fault if their component, operation
     * and phrase match. The phrase never quotes the statement, or each distinct search would leave a record of its
     * own.
     */
    static final String UNINDEXED_PROBLEM = "A search query has no index to use and walks the repository";

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryPlanChecker.class);

    /** How much of a statement to write into a log message. */
    private static final int MAX_LOGGED_STATEMENT = 500;

    private QueryPlanChecker()
    {
        // Utility class
    }

    /**
     * Logs a warning and records a problem if the query has no index to use. This plans the query a second time,
     * which is cheap next to the traversal it reports.
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
                // A problem, not a failure: the query is served. The recorded statements say which index to add.
                ErrorLogger.logProblem(UNINDEXED_PROBLEM,
                    ErrorContext.of(QueryPlanChecker.class, "checkQueryPlan")
                        .actingFor(session.getUserID())
                        .with("statement", forLog(bound.statement()))
                        .with("plan", columns[0].getString()));
            }
        } catch (final RepositoryException | RuntimeException e) {
            // Unchecked too: Oak throws an IllegalArgumentException for a malformed full-text expression. A plan
            // that cannot be had fails nothing.
            LOGGER.debug("Could not obtain the plan of the search query [{}]: {}", forLog(bound.statement()),
                e.getMessage(), e);
        }
    }

    /**
     * Prepares a statement for a log message. A client can send a whole statement, and a line break in it would let
     * the client write log entries of its own.
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

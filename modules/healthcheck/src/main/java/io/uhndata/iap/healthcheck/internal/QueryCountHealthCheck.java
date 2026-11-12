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
package io.uhndata.iap.healthcheck.internal;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.query.Query;
import javax.jcr.query.RowIterator;

import org.apache.felix.hc.api.FormattingResultLog;
import org.apache.felix.hc.api.HealthCheck;
import org.apache.felix.hc.api.Result;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.utils.DateUtils;

/**
 * Checks that JCR-SQL2 queries return the expected number of rows. Each child of
 * {@code /libs/iap/healthcheck/queryCountChecks} configures one query:
 * <ul>
 * <li>{@code query}: a JCR-SQL2 select query</li>
 * <li>{@code comparator}: one of {@code <}, {@code <=}, {@code =}, {@code >=}, {@code >}, {@code !=}</li>
 * <li>{@code compareAgainst}: the number the row count is compared with, a long from 0 to
 * {@code Long.MAX_VALUE - 1}</li>
 * </ul>
 * The query passes when its row count, the comparator and {@code compareAgainst} make a true statement, such as
 * {@code 3 > 0}. The check reads at most {@code compareAgainst + 1} rows, since no comparison needs more.
 *
 * <p>
 * A child with an unknown comparator, or a {@code compareAgainst} outside that range, is reported as a configuration
 * error, and the other children still run. A row count is never negative, so comparing it with a negative number
 * gives the same answer whatever the query finds.
 * </p>
 *
 * <p>
 * A query may contain date placeholders, as in {@code WHERE submission.[jcr:created] > '${today}'}. Before running
 * the query, the check replaces each with midnight at the start of a day, in the server's time zone, such as
 * {@code 2026-11-05T00:00:00.000-05:00}:
 * </p>
 * <ul>
 * <li>{@code ${yesterday}}: the start of yesterday</li>
 * <li>{@code ${today}}: the start of today</li>
 * <li>{@code ${tomorrow}}: the start of tomorrow</li>
 * </ul>
 *
 * <p>
 * A module adds the queries it needs as children of that node, in its own initial content.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(
    service = HealthCheck.class,
    property = {
        HealthCheck.TAGS + "=iap",
        HealthCheck.NAME + "=IAP query counts"
    },
    immediate = true)
public final class QueryCountHealthCheck implements HealthCheck
{
    /** The node whose children configure the queries. */
    public static final String CONFIGURATION_PATH =
        "/libs/iap/healthcheck/queryCountChecks";

    /** The property holding a JCR-SQL2 select query, which may contain date placeholders. */
    public static final String QUERY_PROPERTY = "query";

    /**
     * The property holding how the row count is compared: one of {@code <}, {@code <=}, {@code =}, {@code >=},
     * {@code >}, {@code !=}.
     */
    public static final String COMPARATOR_PROPERTY = "comparator";

    /** The property holding the number the row count is compared with, a long from 0 to {@code Long.MAX_VALUE - 1}. */
    public static final String COMPARE_AGAINST_PROPERTY = "compareAgainst";

    /** Replaced with midnight at the start of yesterday, in the server's time zone. */
    public static final String YESTERDAY_PLACEHOLDER = "${yesterday}";

    /** Replaced with midnight at the start of today, in the server's time zone. */
    public static final String TODAY_PLACEHOLDER = "${today}";

    /** Replaced with midnight at the start of tomorrow, in the server's time zone. */
    public static final String TOMORROW_PLACEHOLDER = "${tomorrow}";

    @FunctionalInterface
    private interface Checker
    {
        boolean check(long actualCount, long compareAgainst);
    }

    private static final Map<String, Checker> COMPARATORS_BY_SYMBOL = Map.of(
        "<", (a, b) -> a < b,
        "<=", (a, b) -> a <= b,
        "=", (a, b) -> a == b,
        ">=", (a, b) -> a >= b,
        ">", (a, b) -> a > b,
        "!=", (a, b) -> a != b);

    /**
     * One configured comparison, such as {@code > 0}.
     *
     * @param comparator one of the six comparator symbols, or anything else a child was given
     * @param compareAgainst the number the row count is compared with
     * @since 0.1.0
     */
    private record Comparison(String comparator, long compareAgainst)
    {
        /**
         * Checks that this comparison can run.
         *
         * @return what is wrong with it, as a phrase naming no values, or nothing if it can run
         */
        Optional<String> findProblem()
        {
            if (!COMPARATORS_BY_SYMBOL.containsKey(this.comparator)) {
                return Optional.of("A query count check has an unknown comparator");
            }
            // The row limit, compareAgainst + 1, overflows at Long.MAX_VALUE
            if (this.compareAgainst < 0 || this.compareAgainst == Long.MAX_VALUE) {
                return Optional.of("A query count check compares against a number outside 0 to Long.MAX_VALUE - 1");
            }
            return Optional.empty();
        }

        /**
         * The most rows the query needs to return, one more than {@link #compareAgainst}. Only meaningful for a
         * comparison with no {@link #findProblem() problem}.
         *
         * @return the row limit
         */
        long rowLimit()
        {
            return this.compareAgainst + 1;
        }

        boolean holdsFor(final long rowCount)
        {
            return COMPARATORS_BY_SYMBOL.get(this.comparator).check(rowCount, this.compareAgainst);
        }

        @Override
        public String toString()
        {
            return this.comparator + " " + this.compareAgainst;
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryCountHealthCheck.class);

    @Reference
    private ResourceResolverFactory rrf;

    @Override
    public Result execute()
    {
        final FormattingResultLog result = new FormattingResultLog();
        int passed = 0;
        int failed = 0;
        try (ResourceResolver resolver = this.rrf.getServiceResourceResolver(
            Map.of(ResourceResolverFactory.SUBSERVICE, "healthcheck"))) {
            final Session session = resolver.adaptTo(Session.class);
            if (session == null) {
                result.healthCheckError("The resource resolver is not backed by a JCR session");
                return new Result(result);
            }
            if (!session.nodeExists(CONFIGURATION_PATH)) {
                result.info("No query count checks configured.");
                return new Result(result);
            }
            final NodeIterator configurations = session.getNode(CONFIGURATION_PATH).getNodes();
            while (configurations.hasNext()) {
                try {
                    if (runSingleCheck(configurations.nextNode(), result, session)) {
                        passed++;
                    } else {
                        failed++;
                    }
                } catch (final RepositoryException e) {
                    LOGGER.error("Unexpected exception while running query count check", e);
                    result.healthCheckError("Cannot run count check: {}", e.getMessage(), e);
                }
            }
        } catch (LoginException | RepositoryException e) {
            result.healthCheckError("Healthcheck module not set up properly: {}", e.getMessage());
        }
        result.info("{} query count checks passed" + (failed != 0 ? " and {} failed" : ""), passed, failed);
        return new Result(result);
    }

    /**
     * Runs one configured query, and adds to the result whether its row count satisfies the comparison.
     *
     * @param configuration a child of {@link #CONFIGURATION_PATH}
     * @param result the log this query's outcome is added to
     * @param session the service session to run the query with
     * @return {@code true} if the row count satisfies the comparison; {@code false} if it does not, or if the
     *         comparison is misconfigured
     * @throws RepositoryException if one of the three properties is missing or of the wrong type, or the query cannot
     *             be parsed or run
     */
    private boolean runSingleCheck(final Node configuration,
        final FormattingResultLog result, final Session session)
        throws RepositoryException
    {
        final String query = resolveDatePlaceholders(configuration.getProperty(QUERY_PROPERTY).getString());
        final Comparison comparison = new Comparison(
            configuration.getProperty(COMPARATOR_PROPERTY).getString(),
            configuration.getProperty(COMPARE_AGAINST_PROPERTY).getLong());

        final Optional<String> problem = comparison.findProblem();
        if (problem.isPresent()) {
            result.healthCheckError("{} in configuration '{}', which compares with '{}'",
                problem.get(), configuration.getName(), comparison);
            recordConfigurationProblem(configuration, comparison, problem.get());
            return false;
        }

        final long actualCount = countRows(query, comparison.rowLimit(), session);
        if (comparison.holdsFor(actualCount)) {
            result.debug("Count check passed for '{}'", configuration.getName());
            return true;
        }
        result.critical(
            "Count check failed for '{}': result was {}, expected {}",
            configuration.getName(), actualCount, comparison);
        return false;
    }

    private void recordConfigurationProblem(final Node configuration, final Comparison comparison,
        final String problem)
        throws RepositoryException
    {
        ErrorLogger.logProblem(problem, ErrorContext.of(QueryCountHealthCheck.class, "execute")
            .about(configuration.getPath())
            .with(COMPARATOR_PROPERTY, comparison.comparator())
            .with(COMPARE_AGAINST_PROPERTY, comparison.compareAgainst()));
    }

    private long countRows(final String query, final long limit, final Session session) throws RepositoryException
    {
        final Query jcrQuery = session.getWorkspace().getQueryManager().createQuery(query, Query.JCR_SQL2);
        jcrQuery.setLimit(limit);
        final RowIterator rows = jcrQuery.execute().getRows();
        long actualCount = 0;
        while (rows.hasNext()) {
            rows.nextRow();
            actualCount++;
        }
        return actualCount;
    }

    private String resolveDatePlaceholders(final String query)
    {
        // One clock reading for all three placeholders, so that midnight cannot fall between them
        final ZonedDateTime now = ZonedDateTime.now();
        return query
            .replace(YESTERDAY_PLACEHOLDER, midnightOf(now.minusDays(1)))
            .replace(TODAY_PLACEHOLDER, midnightOf(now))
            .replace(TOMORROW_PLACEHOLDER, midnightOf(now.plusDays(1)));
    }

    /**
     * Formats midnight at the start of a day.
     *
     * @param date any time on that day, in the time zone to use
     * @return midnight in the {@code 2026-11-05T00:00:00.000-05:00} form JCR reads as a date
     */
    private static String midnightOf(final ZonedDateTime date)
    {
        return DateUtils.PREFERRED_DATETIME_FORMAT.format(DateUtils.atMidnight(date));
    }
}

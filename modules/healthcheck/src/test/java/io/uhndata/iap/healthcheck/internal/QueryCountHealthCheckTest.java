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
import java.util.List;
import java.util.stream.StreamSupport;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Workspace;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.QueryResult;
import javax.jcr.query.RowIterator;

import org.apache.felix.hc.api.Result;
import org.apache.jackrabbit.commons.iterator.NodeIteratorAdapter;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.osgi.framework.Constants;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.errortracking.api.ErrorLoggerService;
import io.uhndata.iap.utils.DateUtils;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link QueryCountHealthCheck}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class QueryCountHealthCheckTest
{
    private static final String QUERY = "SELECT * FROM [test:Entity] AS entity";

    private static final String BAD_CHILD = "badChild";

    private static final String GOOD_CHILD = "goodChild";

    private final SlingContext context = new SlingContext();

    private final ErrorLoggerService recorder = Mockito.mock(ErrorLoggerService.class);

    private QueryCountHealthCheck check;

    private final ResourceResolverFactory rrf = Mockito.mock(ResourceResolverFactory.class);

    private final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);

    private final Session session = Mockito.mock(Session.class);

    private final Query query = Mockito.mock(Query.class);

    @BeforeEach
    void setUp() throws Exception
    {
        this.context.registerService(ResourceResolverFactory.class, this.rrf, Constants.SERVICE_RANKING, 1);
        this.check = this.context.registerInjectActivateService(new QueryCountHealthCheck());
        ErrorLogger.setService(this.recorder);
        Mockito.when(this.rrf.getServiceResourceResolver(Mockito.anyMap())).thenReturn(this.resolver);
        Mockito.when(this.resolver.adaptTo(Session.class)).thenReturn(this.session);
        final Workspace workspace = Mockito.mock(Workspace.class);
        final QueryManager queryManager = Mockito.mock(QueryManager.class);
        Mockito.when(this.session.getWorkspace()).thenReturn(workspace);
        Mockito.when(workspace.getQueryManager()).thenReturn(queryManager);
        Mockito.when(queryManager.createQuery(Mockito.anyString(), Mockito.eq(Query.JCR_SQL2)))
            .thenReturn(this.query);
        // Oak's QueryImpl refuses a negative limit this way
        Mockito.doThrow(new IllegalArgumentException("Limit may not be negative"))
            .when(this.query).setLimit(Mockito.longThat(limit -> limit < 0));
    }

    @AfterEach
    void tearDown()
    {
        ErrorLogger.unsetService(this.recorder);
    }

    @Test
    void allComparatorsWork() throws Exception
    {
        // The query returns 1 row each time, and each comparison holds
        assertEquals(Result.Status.OK, executeSingleCheck("<", 2, 1).getStatus());
        assertEquals(Result.Status.OK, executeSingleCheck("<=", 1, 1).getStatus());
        assertEquals(Result.Status.OK, executeSingleCheck("=", 1, 1).getStatus());
        assertEquals(Result.Status.OK, executeSingleCheck(">=", 1, 1).getStatus());
        assertEquals(Result.Status.OK, executeSingleCheck(">", 0, 1).getStatus());
        assertEquals(Result.Status.OK, executeSingleCheck("!=", 0, 1).getStatus());
        // Each comparison that does not hold fails
        assertEquals(Result.Status.CRITICAL, executeSingleCheck("<", 1, 1).getStatus());
        assertEquals(Result.Status.CRITICAL, executeSingleCheck("<=", 0, 1).getStatus());
        assertEquals(Result.Status.CRITICAL, executeSingleCheck("=", 2, 1).getStatus());
        assertEquals(Result.Status.CRITICAL, executeSingleCheck(">=", 2, 1).getStatus());
        assertEquals(Result.Status.CRITICAL, executeSingleCheck(">", 1, 1).getStatus());
        assertEquals(Result.Status.CRITICAL, executeSingleCheck("!=", 1, 1).getStatus());
    }

    @Test
    void failsWhenTheCountDoesNotMatch() throws Exception
    {
        assertEquals(Result.Status.CRITICAL, executeSingleCheck(">", 1, 1).getStatus());
    }

    @Test
    void limitsTheQueryToOneRowMoreThanTheExpectedCount() throws Exception
    {
        executeSingleCheck("=", 5, 1);
        Mockito.verify(this.query).setLimit(6);
    }

    @Test
    void readsUpToTheLargestLimitAQueryTakes() throws Exception
    {
        assertEquals(Result.Status.OK, executeSingleCheck("<", Long.MAX_VALUE - 1, 1).getStatus());
        Mockito.verify(this.query).setLimit(Long.MAX_VALUE);
    }

    @Test
    void reportsANegativeCountAsThatChildsConfigurationProblem() throws Exception
    {
        assertOnlyTheBadChildFails(-5);
    }

    @Test
    void reportsMinusOneAsThatChildsConfigurationProblem() throws Exception
    {
        assertOnlyTheBadChildFails(-1);
    }

    @Test
    void reportsACountWithNoLimitAsThatChildsConfigurationProblem() throws Exception
    {
        assertOnlyTheBadChildFails(Long.MAX_VALUE);
    }

    @Test
    void recordsAnOutOfRangeCount() throws Exception
    {
        configure(configuration(BAD_CHILD, ">", -5));

        this.check.execute();

        verifyProblemRecordedFor(BAD_CHILD);
    }

    @Test
    void resolvesDatePlaceholders() throws Exception
    {
        final String template = "SELECT * FROM [test:Entity] AS entity WHERE entity.[jcr:created] > '"
            + QueryCountHealthCheck.YESTERDAY_PLACEHOLDER + "' AND entity.[jcr:created] < '"
            + QueryCountHealthCheck.TOMORROW_PLACEHOLDER + "' AND entity.[jcr:created] <> '"
            + QueryCountHealthCheck.TODAY_PLACEHOLDER + "'";
        final Node configuration = configuration(GOOD_CHILD, ">=", 0);
        final Property queryProperty = property(template, 0);
        Mockito.when(configuration.getProperty(QueryCountHealthCheck.QUERY_PROPERTY)).thenReturn(queryProperty);
        configure(configuration);
        answer(1);

        this.check.execute();

        final ArgumentCaptor<String> executedQuery = ArgumentCaptor.forClass(String.class);
        Mockito.verify(this.session.getWorkspace().getQueryManager())
            .createQuery(executedQuery.capture(), Mockito.eq(Query.JCR_SQL2));
        assertFalse(executedQuery.getValue().contains("${"));
        assertTrue(executedQuery.getValue()
            .contains(DateUtils.toString(DateUtils.atMidnight(ZonedDateTime.now()))));
    }

    @Test
    void reportsErrorOnInvalidComparator() throws Exception
    {
        configure(configuration(BAD_CHILD, "~=", 1));
        assertEquals(Result.Status.HEALTH_CHECK_ERROR, this.check.execute().getStatus());
    }

    @Test
    void recordsAnInvalidComparator() throws Exception
    {
        configure(configuration(BAD_CHILD, "~=", 1));

        this.check.execute();

        verifyProblemRecordedFor(BAD_CHILD);
    }

    @Test
    void reportsErrorOnUnexpectedRepositoryFailures() throws Exception
    {
        final Node broken = Mockito.mock(Node.class);
        Mockito.when(broken.getProperty(QueryCountHealthCheck.QUERY_PROPERTY))
            .thenThrow(new RepositoryException("broken"));
        configure(broken);

        assertEquals(Result.Status.HEALTH_CHECK_ERROR, this.check.execute().getStatus());
    }

    @Test
    void passesWhenNoChecksAreConfigured() throws Exception
    {
        Mockito.when(this.session.nodeExists(QueryCountHealthCheck.CONFIGURATION_PATH)).thenReturn(false);
        assertEquals(Result.Status.OK, this.check.execute().getStatus());
    }

    @Test
    void reportsErrorWithoutAJcrSession()
    {
        Mockito.when(this.resolver.adaptTo(Session.class)).thenReturn(null);
        assertEquals(Result.Status.HEALTH_CHECK_ERROR, this.check.execute().getStatus());
    }

    @Test
    void reportsErrorWhenTheServiceUserIsNotSetUp() throws Exception
    {
        Mockito.when(this.rrf.getServiceResourceResolver(Mockito.anyMap()))
            .thenThrow(new LoginException("no service user"));
        assertEquals(Result.Status.HEALTH_CHECK_ERROR, this.check.execute().getStatus());
    }

    /**
     * Runs the health check over a child comparing against {@code compareAgainst}, beside a good child that passes,
     * and checks that only the first is reported, as a configuration problem.
     *
     * @param compareAgainst the bad child's out-of-range number
     * @throws RepositoryException never, since only mocks are set up
     */
    private void assertOnlyTheBadChildFails(final long compareAgainst) throws RepositoryException
    {
        configure(configuration(BAD_CHILD, ">", compareAgainst), configuration(GOOD_CHILD, ">=", 0));
        answer(1);

        final Result result = this.check.execute();

        final List<String> errors = messages(result, Result.Status.HEALTH_CHECK_ERROR);
        assertAll(
            () -> assertEquals(1, errors.size()),
            () -> assertTrue(errors.get(0).contains(BAD_CHILD)),
            () -> assertTrue(errors.get(0).contains(Long.toString(compareAgainst))),
            () -> assertTrue(messages(result, Result.Status.OK).contains("1 query count checks passed and 1 failed")));
    }

    private List<String> messages(final Result result, final Result.Status status)
    {
        return StreamSupport.stream(result.spliterator(), false)
            .filter(entry -> entry.getStatus() == status)
            .map(entry -> entry.getMessage())
            .toList();
    }

    private void verifyProblemRecordedFor(final String child)
    {
        Mockito.verify(this.recorder).logProblem(Mockito.anyString(), Mockito.argThat((ErrorContext recorded) ->
            QueryCountHealthCheck.class.getName().equals(recorded.getComponent())
                && (QueryCountHealthCheck.CONFIGURATION_PATH + "/" + child).equals(recorded.getSubject())));
    }

    /**
     * Runs the health check with a single configured count check against a query returning the given number of
     * rows.
     *
     * @param comparator the comparator to configure, such as {@code >=}
     * @param compareAgainst the number to compare the row count with
     * @param actualRows the number of rows the query returns
     * @return the result of the whole health check
     * @throws RepositoryException never, since only mocks are set up
     */
    private Result executeSingleCheck(final String comparator, final long compareAgainst, final int actualRows)
        throws RepositoryException
    {
        configure(configuration(GOOD_CHILD, comparator, compareAgainst));
        answer(actualRows);
        return this.check.execute();
    }

    private void configure(final Node... configurations) throws RepositoryException
    {
        Mockito.when(this.session.nodeExists(QueryCountHealthCheck.CONFIGURATION_PATH)).thenReturn(true);
        final Node holder = Mockito.mock(Node.class);
        Mockito.when(this.session.getNode(QueryCountHealthCheck.CONFIGURATION_PATH)).thenReturn(holder);
        Mockito.when(holder.getNodes()).thenReturn(new NodeIteratorAdapter(List.of(configurations)));
    }

    private Node configuration(final String name, final String comparator, final long compareAgainst)
        throws RepositoryException
    {
        final Node configuration = Mockito.mock(Node.class);
        final Property queryProperty = property(QUERY, 0);
        final Property comparatorProperty = property(comparator, 0);
        final Property countProperty = property(null, compareAgainst);
        Mockito.when(configuration.getProperty(QueryCountHealthCheck.QUERY_PROPERTY))
            .thenReturn(queryProperty);
        Mockito.when(configuration.getProperty(QueryCountHealthCheck.COMPARATOR_PROPERTY))
            .thenReturn(comparatorProperty);
        Mockito.when(configuration.getProperty(QueryCountHealthCheck.COMPARE_AGAINST_PROPERTY))
            .thenReturn(countProperty);
        Mockito.when(configuration.getName()).thenReturn(name);
        Mockito.when(configuration.getPath()).thenReturn(QueryCountHealthCheck.CONFIGURATION_PATH + "/" + name);
        return configuration;
    }

    private Property property(final String stringValue, final long longValue) throws RepositoryException
    {
        final Property property = Mockito.mock(Property.class);
        Mockito.when(property.getString()).thenReturn(stringValue);
        Mockito.when(property.getLong()).thenReturn(longValue);
        return property;
    }

    /**
     * Makes the mocked query return the given number of rows.
     *
     * @param rows how many rows the query returns
     * @throws RepositoryException never, since only mocks are set up
     */
    private void answer(final int rows) throws RepositoryException
    {
        final QueryResult queryResult = Mockito.mock(QueryResult.class);
        final RowIterator rowIterator = Mockito.mock(RowIterator.class);
        Mockito.when(this.query.execute()).thenReturn(queryResult);
        Mockito.when(queryResult.getRows()).thenReturn(rowIterator);
        final Boolean[] followUps = new Boolean[rows];
        for (int i = 0; i < rows - 1; i++) {
            followUps[i] = true;
        }
        if (rows > 0) {
            followUps[rows - 1] = false;
        }
        Mockito.when(rowIterator.hasNext()).thenReturn(rows > 0, followUps);
    }
}

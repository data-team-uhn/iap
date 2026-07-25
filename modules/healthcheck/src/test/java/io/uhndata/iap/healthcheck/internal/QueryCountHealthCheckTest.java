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

import java.lang.reflect.Field;
import java.time.ZonedDateTime;
import java.util.List;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.uhndata.iap.utils.DateUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link QueryCountHealthCheck}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class QueryCountHealthCheckTest
{
    private static final String QUERY = "SELECT * FROM [iap:TestEntity] AS entity";

    private final QueryCountHealthCheck check = new QueryCountHealthCheck();

    private final ResourceResolverFactory rrf = Mockito.mock(ResourceResolverFactory.class);

    private final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);

    private final Session session = Mockito.mock(Session.class);

    private final Query query = Mockito.mock(Query.class);

    @BeforeEach
    void setUp() throws Exception
    {
        final Field reference = QueryCountHealthCheck.class.getDeclaredField("rrf");
        reference.setAccessible(true);
        reference.set(this.check, this.rrf);
        Mockito.when(this.rrf.getServiceResourceResolver(Mockito.anyMap())).thenReturn(this.resolver);
        Mockito.when(this.resolver.adaptTo(Session.class)).thenReturn(this.session);
        final Workspace workspace = Mockito.mock(Workspace.class);
        final QueryManager queryManager = Mockito.mock(QueryManager.class);
        Mockito.when(this.session.getWorkspace()).thenReturn(workspace);
        Mockito.when(workspace.getQueryManager()).thenReturn(queryManager);
        Mockito.when(queryManager.createQuery(Mockito.anyString(), Mockito.eq(Query.JCR_SQL2)))
            .thenReturn(this.query);
    }

    @Test
    void allComparatorsWork() throws Exception
    {
        // The query always returns 1 row; each comparison should hold
        assertEquals(Result.Status.OK, executeSingleCheck("<", 2, 1).getStatus());
        assertEquals(Result.Status.OK, executeSingleCheck("<=", 1, 1).getStatus());
        assertEquals(Result.Status.OK, executeSingleCheck("=", 1, 1).getStatus());
        assertEquals(Result.Status.OK, executeSingleCheck(">=", 1, 1).getStatus());
        assertEquals(Result.Status.OK, executeSingleCheck(">", 0, 1).getStatus());
        assertEquals(Result.Status.OK, executeSingleCheck("!=", 0, 1).getStatus());
        // ... and each comparison should also fail when it doesn't hold
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
    void resolvesDatePlaceholders() throws Exception
    {
        final String template = "SELECT * FROM [iap:TestEntity] AS entity WHERE entity.[jcr:created] > '"
            + QueryCountHealthCheck.YESTERDAY_PLACEHOLDER + "' AND entity.[jcr:created] < '"
            + QueryCountHealthCheck.TOMORROW_PLACEHOLDER + "' AND entity.[jcr:created] <> '"
            + QueryCountHealthCheck.TODAY_PLACEHOLDER + "'";
        configure(configuration(template, ">=", 0));
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
        configure(configuration(QUERY, "~=", 1));
        assertEquals(Result.Status.HEALTH_CHECK_ERROR, this.check.execute().getStatus());
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
     * Runs the health check with a single configured count check against a query returning the given number of
     * rows.
     *
     * @param comparator the comparison operator to configure
     * @param compareAgainst the expected count to configure
     * @param actualRows the number of rows the query will return
     * @return the health check result
     * @throws RepositoryException never, this is just mock setup
     */
    private Result executeSingleCheck(final String comparator, final long compareAgainst, final int actualRows)
        throws RepositoryException
    {
        configure(configuration(QUERY, comparator, compareAgainst));
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

    private Node configuration(final String queryText, final String comparator, final long compareAgainst)
        throws RepositoryException
    {
        final Node configuration = Mockito.mock(Node.class);
        final Property queryProperty = property(queryText, 0);
        final Property comparatorProperty = property(comparator, 0);
        final Property countProperty = property(null, compareAgainst);
        Mockito.when(configuration.getProperty(QueryCountHealthCheck.QUERY_PROPERTY))
            .thenReturn(queryProperty);
        Mockito.when(configuration.getProperty(QueryCountHealthCheck.COMPARATOR_PROPERTY))
            .thenReturn(comparatorProperty);
        Mockito.when(configuration.getProperty(QueryCountHealthCheck.COMPARE_AGAINST_PROPERTY))
            .thenReturn(countProperty);
        Mockito.when(configuration.getName()).thenReturn("testConfiguration");
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
     * @param rows how many rows the query should return
     * @throws RepositoryException never, this is just mock setup
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

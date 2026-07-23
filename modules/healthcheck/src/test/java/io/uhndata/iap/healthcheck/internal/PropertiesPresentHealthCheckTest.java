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
import java.util.List;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.felix.hc.api.Result;
import org.apache.jackrabbit.commons.iterator.NodeIteratorAdapter;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PropertiesPresentHealthCheck}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class PropertiesPresentHealthCheckTest
{
    private static final String CHECKED_PATH = "/checked/property";

    private final PropertiesPresentHealthCheck check = new PropertiesPresentHealthCheck();

    private final ResourceResolverFactory rrf = Mockito.mock(ResourceResolverFactory.class);

    private final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);

    private final Session session = Mockito.mock(Session.class);

    @BeforeEach
    void setUp() throws Exception
    {
        final Field reference = PropertiesPresentHealthCheck.class.getDeclaredField("rrf");
        reference.setAccessible(true);
        reference.set(this.check, this.rrf);
        Mockito.when(this.rrf.getServiceResourceResolver(Mockito.anyMap())).thenReturn(this.resolver);
        Mockito.when(this.resolver.adaptTo(Session.class)).thenReturn(this.session);
    }

    @Test
    void passesWhenPropertyExists() throws Exception
    {
        configure(configuration(CHECKED_PATH, null));
        Mockito.when(this.session.propertyExists(CHECKED_PATH)).thenReturn(true);

        assertEquals(Result.Status.OK, this.check.execute().getStatus());
    }

    @Test
    void passesWhenPropertyHasTheRequiredValue() throws Exception
    {
        configure(configuration(CHECKED_PATH, "expected"));
        Mockito.when(this.session.propertyExists(CHECKED_PATH)).thenReturn(true);
        final Property actualProperty = property("expected");
        Mockito.when(this.session.getProperty(CHECKED_PATH)).thenReturn(actualProperty);

        assertEquals(Result.Status.OK, this.check.execute().getStatus());
    }

    @Test
    void failsWhenPropertyIsMissing() throws Exception
    {
        configure(configuration(CHECKED_PATH, null));
        Mockito.when(this.session.propertyExists(CHECKED_PATH)).thenReturn(false);

        assertEquals(Result.Status.CRITICAL, this.check.execute().getStatus());
    }

    @Test
    void failsWhenPropertyHasTheWrongValue() throws Exception
    {
        configure(configuration(CHECKED_PATH, "expected"));
        Mockito.when(this.session.propertyExists(CHECKED_PATH)).thenReturn(true);
        final Property actualProperty = property("actual");
        Mockito.when(this.session.getProperty(CHECKED_PATH)).thenReturn(actualProperty);

        assertEquals(Result.Status.CRITICAL, this.check.execute().getStatus());
    }

    @Test
    void reportsErrorOnUnexpectedRepositoryFailures() throws Exception
    {
        final Node broken = Mockito.mock(Node.class);
        Mockito.when(broken.getProperty(PropertiesPresentHealthCheck.PATH_PROPERTY))
            .thenThrow(new RepositoryException("broken"));
        configure(broken);

        assertEquals(Result.Status.HEALTH_CHECK_ERROR, this.check.execute().getStatus());
    }

    @Test
    void warnsWhenNoConfigurationExists() throws Exception
    {
        Mockito.when(this.session.nodeExists(PropertiesPresentHealthCheck.CONFIGURATION_PATH)).thenReturn(false);

        assertEquals(Result.Status.WARN, this.check.execute().getStatus());
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

    @Test
    void mixedResultsReportTheWorstStatus() throws Exception
    {
        final String otherPath = "/other/property";
        configure(configuration(CHECKED_PATH, null), configuration(otherPath, null));
        Mockito.when(this.session.propertyExists(CHECKED_PATH)).thenReturn(true);
        Mockito.when(this.session.propertyExists(otherPath)).thenReturn(false);

        final Result result = this.check.execute();
        assertEquals(Result.Status.CRITICAL, result.getStatus());
        assertTrue(result.iterator().hasNext());
    }

    private void configure(final Node... configurations) throws RepositoryException
    {
        Mockito.when(this.session.nodeExists(PropertiesPresentHealthCheck.CONFIGURATION_PATH)).thenReturn(true);
        final Node holder = Mockito.mock(Node.class);
        Mockito.when(this.session.getNode(PropertiesPresentHealthCheck.CONFIGURATION_PATH)).thenReturn(holder);
        Mockito.when(holder.getNodes()).thenReturn(new NodeIteratorAdapter(List.of(configurations)));
    }

    private Node configuration(final String propertyPath, final String requiredValue) throws RepositoryException
    {
        final Node configuration = Mockito.mock(Node.class);
        final Property pathProperty = property(propertyPath);
        Mockito.when(configuration.getProperty(PropertiesPresentHealthCheck.PATH_PROPERTY))
            .thenReturn(pathProperty);
        if (requiredValue != null) {
            final Property valueProperty = property(requiredValue);
            Mockito.when(configuration.hasProperty(PropertiesPresentHealthCheck.VALUE_PROPERTY)).thenReturn(true);
            Mockito.when(configuration.getProperty(PropertiesPresentHealthCheck.VALUE_PROPERTY))
                .thenReturn(valueProperty);
        }
        return configuration;
    }

    private Property property(final String value) throws RepositoryException
    {
        final Property property = Mockito.mock(Property.class);
        Mockito.when(property.getString()).thenReturn(value);
        return property;
    }
}

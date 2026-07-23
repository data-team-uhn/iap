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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.felix.hc.api.Result;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link ServicesPresentHealthCheck}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ServicesPresentHealthCheckTest
{
    private static final String SERVICE_CLASS = "org.example.Service";

    private final ServicesPresentHealthCheck check = new ServicesPresentHealthCheck();

    private final BundleContext context = Mockito.mock(BundleContext.class);

    private final ResourceResolverFactory rrf = Mockito.mock(ResourceResolverFactory.class);

    private final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);

    @BeforeEach
    void setUp() throws Exception
    {
        final Field reference = ServicesPresentHealthCheck.class.getDeclaredField("rrf");
        reference.setAccessible(true);
        reference.set(this.check, this.rrf);
        this.check.activate(this.context);
        Mockito.when(this.rrf.getServiceResourceResolver(Mockito.anyMap())).thenReturn(this.resolver);
    }

    @Test
    void passesWhenTheServiceIsRegistered() throws Exception
    {
        configure(configuration(SERVICE_CLASS, null));
        final ServiceReference<?> implementation = Mockito.mock(ServiceReference.class);
        Mockito.when(this.context.getAllServiceReferences(SERVICE_CLASS, null))
            .thenReturn(new ServiceReference<?>[] { implementation });

        assertEquals(Result.Status.OK, this.check.execute().getStatus());
    }

    @Test
    void passesWhenTheServiceMatchesTheFilter() throws Exception
    {
        configure(configuration(SERVICE_CLASS, "(component.name=example)"));
        final ServiceReference<?> implementation = Mockito.mock(ServiceReference.class);
        Mockito.when(this.context.getAllServiceReferences(SERVICE_CLASS, "(component.name=example)"))
            .thenReturn(new ServiceReference<?>[] { implementation });

        assertEquals(Result.Status.OK, this.check.execute().getStatus());
    }

    @Test
    void failsWhenTheServiceIsMissing() throws Exception
    {
        configure(configuration(SERVICE_CLASS, null));
        Mockito.when(this.context.getAllServiceReferences(SERVICE_CLASS, null)).thenReturn(null);

        assertEquals(Result.Status.CRITICAL, this.check.execute().getStatus());
    }

    @Test
    void reportsErrorOnBlankServiceClass() throws Exception
    {
        // A blank service class would match every registered service, so it is a configuration error
        configure(configuration(" ", null));

        assertEquals(Result.Status.HEALTH_CHECK_ERROR, this.check.execute().getStatus());
        Mockito.verify(this.context, Mockito.never()).getAllServiceReferences(Mockito.any(), Mockito.any());
    }

    @Test
    void reportsErrorOnInvalidFilterSyntax() throws Exception
    {
        configure(configuration(SERVICE_CLASS, "invalid filter"));
        Mockito.when(this.context.getAllServiceReferences(SERVICE_CLASS, "invalid filter"))
            .thenThrow(new InvalidSyntaxException("invalid", "invalid filter"));

        assertEquals(Result.Status.HEALTH_CHECK_ERROR, this.check.execute().getStatus());
    }

    @Test
    void warnsWhenNoConfigurationExists()
    {
        Mockito.when(this.resolver.getResource(ServicesPresentHealthCheck.CONFIGURATION_PATH)).thenReturn(null);
        assertEquals(Result.Status.WARN, this.check.execute().getStatus());

        final Resource nonExisting = Mockito.mock(Resource.class);
        Mockito.when(nonExisting.isResourceType(Resource.RESOURCE_TYPE_NON_EXISTING)).thenReturn(true);
        Mockito.when(this.resolver.getResource(ServicesPresentHealthCheck.CONFIGURATION_PATH))
            .thenReturn(nonExisting);
        assertEquals(Result.Status.WARN, this.check.execute().getStatus());
    }

    @Test
    void reportsErrorWhenTheServiceUserIsNotSetUp() throws Exception
    {
        Mockito.when(this.rrf.getServiceResourceResolver(Mockito.anyMap()))
            .thenThrow(new LoginException("no service user"));
        assertEquals(Result.Status.HEALTH_CHECK_ERROR, this.check.execute().getStatus());
    }

    private void configure(final Resource... configurations)
    {
        final Resource holder = Mockito.mock(Resource.class);
        Mockito.when(this.resolver.getResource(ServicesPresentHealthCheck.CONFIGURATION_PATH)).thenReturn(holder);
        Mockito.when(holder.getChildren()).thenReturn(List.of(configurations));
    }

    private Resource configuration(final String serviceClass, final String filter)
    {
        final Resource configuration = Mockito.mock(Resource.class);
        final Map<String, Object> values = new HashMap<>();
        values.put(ServicesPresentHealthCheck.SERVICE_PROPERTY, serviceClass);
        if (filter != null) {
            values.put(ServicesPresentHealthCheck.FILTER_PROPERTY, filter);
        }
        Mockito.when(configuration.getValueMap()).thenReturn(new ValueMapDecorator(values));
        Mockito.when(configuration.getName()).thenReturn("testConfiguration");
        return configuration;
    }
}

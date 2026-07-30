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
package io.uhndata.iap.errortracking.internal;

import java.lang.reflect.Field;
import java.util.Map;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;

/**
 * Wires the components under test to the test's own repository. The mock service resolvers do not share the
 * repository the test populates, and the SCR metadata that would let the mock OSGi runtime inject the factory is
 * only generated when the bundle is packaged, so both are done by hand here.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class TestResolvers
{
    /** Only static methods, no instances. */
    private TestResolvers()
    {
        // Utility class
    }

    /**
     * Injects a resolver factory handing out the given resolver into a component's {@code resolverFactory} field.
     *
     * @param component the component under test
     * @param resolver the resolver every request should be served with, {@code null} to make every request fail as
     *            it would with a missing service user
     * @throws ReflectiveOperationException if the component has no such field
     */
    public static void inject(final Object component, final ResourceResolver resolver)
        throws ReflectiveOperationException
    {
        final Field field = component.getClass().getDeclaredField("resolverFactory");
        field.setAccessible(true);
        field.set(component, factory(resolver));
    }

    private static ResourceResolverFactory factory(final ResourceResolver resolver)
    {
        // Closing is suppressed: the components under test close every resolver they open, and the test context
        // owns this one
        final ResourceResolver shared = resolver == null ? null : new ResourceResolverWrapper(resolver)
        {
            @Override
            public void close()
            {
                // The test context owns this resolver
            }
        };
        return new ResourceResolverFactory()
        {
            @Override
            public ResourceResolver getResourceResolver(final Map<String, Object> authenticationInfo)
            {
                return shared;
            }

            @Deprecated
            @Override
            public ResourceResolver getAdministrativeResourceResolver(final Map<String, Object> authenticationInfo)
            {
                return shared;
            }

            @Override
            public ResourceResolver getServiceResourceResolver(final Map<String, Object> authenticationInfo)
                throws LoginException
            {
                if (shared == null) {
                    throw new LoginException("No such service user");
                }
                return shared;
            }

            @Override
            public ResourceResolver getThreadResourceResolver()
            {
                return shared;
            }

            @Override
            public java.util.List<String> getSearchPath()
            {
                return java.util.List.of("/apps", "/libs");
            }
        };
    }
}

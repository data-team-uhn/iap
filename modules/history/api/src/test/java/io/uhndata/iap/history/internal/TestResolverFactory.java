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
package io.uhndata.iap.history.internal;

import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;

/**
 * Hands out the test's own resolver as if it were a service resolver, and counts the service logins.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class TestResolverFactory implements ResourceResolverFactory
{
    private final ResourceResolver resolver;

    private int serviceLogins;

    private TestResolverFactory(final ResourceResolver resolver)
    {
        this.resolver = resolver;
    }

    /**
     * A factory whose service logins all return the given resolver. Closing it does nothing: the code under test
     * closes what it is given, and the test needs the same repository afterwards.
     *
     * @param resolver the resolver to hand out
     * @return a factory serving that resolver
     */
    static TestResolverFactory serving(final ResourceResolver resolver)
    {
        return new TestResolverFactory(new ResourceResolverWrapper(resolver)
        {
            @Override
            public void close()
            {
                // The test still needs it
            }
        });
    }

    /**
     * A factory with no service user behind it.
     *
     * @return a factory whose service logins all fail
     */
    static TestResolverFactory refusingLogin()
    {
        return new TestResolverFactory(null);
    }

    /**
     * How many service resolvers were asked for.
     *
     * @return the number of service logins attempted
     */
    int serviceLogins()
    {
        return this.serviceLogins;
    }

    @Override
    public ResourceResolver getResourceResolver(final Map<String, Object> authenticationInfo)
    {
        return this.resolver;
    }

    @Override
    @Deprecated
    public ResourceResolver getAdministrativeResourceResolver(final Map<String, Object> authenticationInfo)
    {
        return this.resolver;
    }

    @Override
    public ResourceResolver getServiceResourceResolver(final Map<String, Object> authenticationInfo)
        throws LoginException
    {
        ++this.serviceLogins;
        if (this.resolver == null) {
            throw new LoginException("No service user in this test");
        }
        return this.resolver;
    }

    @Override
    public ResourceResolver getThreadResourceResolver()
    {
        return this.resolver;
    }

    @Override
    public List<String> getSearchPath()
    {
        return List.of("/apps", "/libs");
    }
}

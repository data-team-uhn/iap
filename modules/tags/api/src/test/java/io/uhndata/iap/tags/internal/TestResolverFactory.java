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
package io.uhndata.iap.tags.internal;

import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;

/**
 * Hands out the test's own resource resolver as the service user's, since the mock service resolvers do not share
 * the repository the test populates. Closing the handed out resolver is ignored, so that the code under test closing
 * a stale one does not take the test's resolver down with it.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class TestResolverFactory implements ResourceResolverFactory
{
    private final ResourceResolver resolver;

    /**
     * Basic constructor.
     *
     * @param resolver the resolver to hand out, {@code null} to stand in for a missing service user, which makes
     *            every request for a service resolver fail
     */
    public TestResolverFactory(final ResourceResolver resolver)
    {
        this.resolver = resolver == null ? null : new ResourceResolverWrapper(resolver)
        {
            @Override
            public void close()
            {
                // The test context owns this resolver
            }
        };
    }

    @Override
    public ResourceResolver getResourceResolver(final Map<String, Object> authenticationInfo)
    {
        return this.resolver;
    }

    @Deprecated
    @Override
    public ResourceResolver getAdministrativeResourceResolver(final Map<String, Object> authenticationInfo)
    {
        return this.resolver;
    }

    @Override
    public ResourceResolver getServiceResourceResolver(final Map<String, Object> authenticationInfo)
        throws LoginException
    {
        if (this.resolver == null) {
            throw new LoginException("No such service user");
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

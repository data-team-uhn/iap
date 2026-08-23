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
package io.uhndata.iap.links.internal;

import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;

/**
 * Dispatches service resolver requests by subservice, since the two service users of the links module have very
 * different test semantics: definition reads get the test's own resolver, so they see the uncommitted fixture,
 * while the container-creating writes keep the real mock factory, whose resolvers only see committed content,
 * exactly like at runtime. Closing the handed out definitions resolver is ignored, so that the code under test
 * closing a stale one does not take the test's resolver down with it. Either side can be {@code null} to stand in
 * for that service user missing, which makes requests for it fail.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class TestResolverFactory implements ResourceResolverFactory
{
    private final ResourceResolver definitionsResolver;

    private final ResourceResolverFactory writesDelegate;

    /**
     * Basic constructor.
     *
     * @param definitionsResolver the resolver handed out for the definitions subservice, usually the test's own,
     *            or {@code null} to stand in for a missing {@code iap-link-types} service user
     * @param writesDelegate the factory serving every other request, usually the mock context's own, or
     *            {@code null} to stand in for a missing {@code iap-links} service user
     */
    public TestResolverFactory(final ResourceResolver definitionsResolver,
        final ResourceResolverFactory writesDelegate)
    {
        this.definitionsResolver = definitionsResolver == null ? null
            : new ResourceResolverWrapper(definitionsResolver)
            {
                @Override
                public void close()
                {
                    // The test context owns this resolver
                }
            };
        this.writesDelegate = writesDelegate;
    }

    @Override
    public ResourceResolver getServiceResourceResolver(final Map<String, Object> authenticationInfo)
        throws LoginException
    {
        final Object subservice = authenticationInfo == null ? null : authenticationInfo.get(SUBSERVICE);
        if (LinkManagerImpl.DEFINITIONS_SUBSERVICE.equals(subservice)) {
            if (this.definitionsResolver == null) {
                throw new LoginException("No such service user");
            }
            return this.definitionsResolver;
        }
        if (this.writesDelegate == null) {
            throw new LoginException("No such service user");
        }
        return this.writesDelegate.getServiceResourceResolver(authenticationInfo);
    }

    @Override
    public ResourceResolver getResourceResolver(final Map<String, Object> authenticationInfo)
        throws LoginException
    {
        return this.writesDelegate.getResourceResolver(authenticationInfo);
    }

    @Deprecated
    @Override
    public ResourceResolver getAdministrativeResourceResolver(final Map<String, Object> authenticationInfo)
        throws LoginException
    {
        return this.writesDelegate.getAdministrativeResourceResolver(authenticationInfo);
    }

    @Override
    public ResourceResolver getThreadResourceResolver()
    {
        return this.definitionsResolver;
    }

    @Override
    public List<String> getSearchPath()
    {
        return List.of("/apps", "/libs");
    }
}

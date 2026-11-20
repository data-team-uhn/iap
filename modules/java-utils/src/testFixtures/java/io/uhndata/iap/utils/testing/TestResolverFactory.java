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
package io.uhndata.iap.utils.testing;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;

/**
 * Logs the code under test in as its service users, with the resolvers the test chooses. Sling-mock's own service
 * resolvers see only what the test has committed. A test usually hands out its own resolver instead, which also sees
 * the fixture it has not committed.
 * <p>
 * Every subservice is served the same way, unless the test sets one apart with {@link #withSubservice} or
 * {@link #withoutSubservice}. A missing service user is a login that fails with a {@link LoginException}, as it
 * does in Sling.
 * <p>
 * A resolver passed in as a {@link ResourceResolver} ignores {@code close()}. The test context owns it, and the
 * code under test closes every resolver it opens.
 * <p>
 * Only service logins are supported. Every other method throws {@link UnsupportedOperationException}.
 * <p>
 * A module's tests use it through the {@code iap-java-utils} test jar, in the module's {@code tests} profile.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class TestResolverFactory implements ResourceResolverFactory
{
    private static final ServiceLogin REFUSED = authenticationInfo -> {
        throw new LoginException("No such service user");
    };

    private final ServiceLogin everySubservice;

    private final Map<String, ServiceLogin> subservices;

    /**
     * Answers one service login.
     *
     * @version $Id$
     * @since 0.1.0
     */
    @FunctionalInterface
    public interface ServiceLogin
    {
        /**
         * Logs in as a service user.
         *
         * @param authenticationInfo what the code under test asked for, usually naming a
         *            {@link ResourceResolverFactory#SUBSERVICE}; {@code null} if it asked for nothing
         * @return the resolver to hand to the code under test
         * @throws LoginException to stand in for a missing service user
         */
        ResourceResolver login(Map<String, Object> authenticationInfo) throws LoginException;
    }

    private TestResolverFactory(final ServiceLogin everySubservice, final Map<String, ServiceLogin> subservices)
    {
        this.everySubservice = everySubservice;
        this.subservices = subservices;
    }

    /**
     * A factory logging every service user in with the given resolver.
     *
     * @param resolver the resolver to hand out, usually the test context's own
     * @return a factory serving that resolver to every subservice
     */
    public static TestResolverFactory serving(final ResourceResolver resolver)
    {
        return answeredBy(served(resolver));
    }

    /**
     * A factory with no service users, so that every service login fails.
     *
     * @return a factory refusing every subservice
     */
    public static TestResolverFactory withoutServiceUser()
    {
        return answeredBy(REFUSED);
    }

    /**
     * A factory answering every service login with the given function. A test uses it to count logins, to record
     * what was asked for, or to answer successive logins differently.
     *
     * @param login answers each service login
     * @return a factory answering every subservice through that function
     */
    public static TestResolverFactory answeredBy(final ServiceLogin login)
    {
        return new TestResolverFactory(login, Map.of());
    }

    /**
     * A copy of this factory that logs one subservice in with its own resolver.
     *
     * @param subservice the subservice name, such as {@code link-types}
     * @param resolver the resolver to hand out for that subservice
     * @return a new factory; this one is unchanged
     */
    public TestResolverFactory withSubservice(final String subservice, final ResourceResolver resolver)
    {
        return this.withLogin(subservice, served(resolver));
    }

    /**
     * A copy of this factory without one service user, so that its logins fail.
     *
     * @param subservice the subservice name, such as {@code link-types}
     * @return a new factory; this one is unchanged
     */
    public TestResolverFactory withoutSubservice(final String subservice)
    {
        return this.withLogin(subservice, REFUSED);
    }

    @Override
    public ResourceResolver getServiceResourceResolver(final Map<String, Object> authenticationInfo)
        throws LoginException
    {
        return this.loginFor(authenticationInfo).login(authenticationInfo);
    }

    private ServiceLogin loginFor(final Map<String, Object> authenticationInfo)
    {
        if (authenticationInfo != null && authenticationInfo.get(SUBSERVICE) instanceof final String subservice) {
            return this.subservices.getOrDefault(subservice, this.everySubservice);
        }
        return this.everySubservice;
    }

    @Override
    public ResourceResolver getResourceResolver(final Map<String, Object> authenticationInfo)
    {
        throw new UnsupportedOperationException();
    }

    @Deprecated
    @Override
    public ResourceResolver getAdministrativeResourceResolver(final Map<String, Object> authenticationInfo)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public ResourceResolver getThreadResourceResolver()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<String> getSearchPath()
    {
        throw new UnsupportedOperationException();
    }

    private TestResolverFactory withLogin(final String subservice, final ServiceLogin login)
    {
        final Map<String, ServiceLogin> copy = new HashMap<>(this.subservices);
        copy.put(subservice, login);
        return new TestResolverFactory(this.everySubservice, Map.copyOf(copy));
    }

    private static ServiceLogin served(final ResourceResolver resolver)
    {
        final ResourceResolver unclosable = new ResourceResolverWrapper(resolver)
        {
            @Override
            public void close()
            {
                // The test context owns this resolver
            }
        };
        return authenticationInfo -> unclosable;
    }
}

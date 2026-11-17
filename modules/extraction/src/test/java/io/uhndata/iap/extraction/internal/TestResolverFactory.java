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
package io.uhndata.iap.extraction.internal;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;

/**
 * Opens sessions on the test context's resolver, as the service user's. Each session is new, and refuses any use
 * after it is closed, so code that keeps a closed session fails here as it would in production. Closing one leaves
 * the context's resolver open. Given {@code null}, it acts as if the service user is missing and refuses to log in.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class TestResolverFactory implements ResourceResolverFactory
{
    private final ResourceResolver resolver;

    TestResolverFactory(final ResourceResolver resolver)
    {
        this.resolver = resolver;
    }

    /** Opens a session on the shared resolver, or returns {@code null} without a service user. */
    private ResourceResolver openSession()
    {
        if (this.resolver == null) {
            return null;
        }
        final ResourceResolver guarded = (ResourceResolver) Proxy.newProxyInstance(
            TestResolverFactory.class.getClassLoader(), new Class<?>[] { ResourceResolver.class },
            new Session(this.resolver));
        // Wrapped, so the resources it hands out lead back to the guarded session
        return new ResourceResolverWrapper(guarded);
    }

    /** One session: passes every call on to the shared resolver until it is closed. */
    private static final class Session implements InvocationHandler
    {
        private final ResourceResolver shared;

        private boolean closed;

        Session(final ResourceResolver shared)
        {
            this.shared = shared;
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable
        {
            if ("close".equals(method.getName())) {
                this.closed = true;
                return null;
            }
            if ("isLive".equals(method.getName())) {
                return !this.closed;
            }
            if (Object.class.equals(method.getDeclaringClass())) {
                return method.invoke(this, args);
            }
            if (this.closed) {
                throw new IllegalStateException("The session was used after it was closed");
            }
            try {
                return method.invoke(this.shared, args);
            } catch (final InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    @Override
    public ResourceResolver getResourceResolver(final Map<String, Object> authenticationInfo)
    {
        return openSession();
    }

    @Deprecated
    @Override
    public ResourceResolver getAdministrativeResourceResolver(final Map<String, Object> authenticationInfo)
    {
        return openSession();
    }

    @Override
    public ResourceResolver getServiceResourceResolver(final Map<String, Object> authenticationInfo)
        throws LoginException
    {
        if (this.resolver == null) {
            throw new LoginException("No such service user");
        }
        return openSession();
    }

    @Override
    public ResourceResolver getThreadResourceResolver()
    {
        return openSession();
    }

    @Override
    public List<String> getSearchPath()
    {
        return List.of("/apps", "/libs");
    }
}

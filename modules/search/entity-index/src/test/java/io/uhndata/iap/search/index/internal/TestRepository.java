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
package io.uhndata.iap.search.index.internal;

import java.util.List;
import java.util.Objects;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.testing.mock.sling.NodeTypeDefinitionScanner;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;

/**
 * A real repository that knows the test node types, where node types and property types behave as they do in
 * production.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class TestRepository
{
    private TestRepository()
    {
        // Utility class
    }

    /**
     * A context backed by an in-memory Oak.
     *
     * @return a new context
     */
    static SlingContext newContext()
    {
        return new SlingContext(ResourceResolverType.JCR_OAK);
    }

    /**
     * The context's session, once the test node types are registered.
     *
     * @param context a context from {@link #newContext()}
     * @return its session
     * @throws RepositoryException if the node types cannot be registered
     */
    static Session session(final SlingContext context) throws RepositoryException
    {
        final Session session = Objects.requireNonNull(context.resourceResolver().adaptTo(Session.class));
        NodeTypeDefinitionScanner.get().register(session, List.of("SLING-INF/nodetypes/test.cnd"),
            ResourceResolverType.JCR_OAK.getNodeTypeMode());
        return session;
    }
}

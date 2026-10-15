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
package io.uhndata.iap.submissions.internal;

import java.util.Objects;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;

/**
 * Gives nodes of the mock repository the {@code jcr:uuid} that Oak gives every referenceable node, so that their
 * models can be pointed at.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class Referenceables
{
    private Referenceables()
    {
    }

    /**
     * Sets each node's {@code jcr:uuid} to the identifier the mock repository knows it by.
     *
     * @param context the test's context, on a {@code JCR_MOCK} repository
     * @param paths the nodes
     * @throws RepositoryException when a node cannot be written
     * @throws PersistenceException when the change cannot be committed
     */
    public static void identify(final SlingContext context, final String... paths)
        throws RepositoryException, PersistenceException
    {
        for (final String path : paths) {
            final Node node = Objects.requireNonNull(
                Objects.requireNonNull(context.resourceResolver().getResource(path)).adaptTo(Node.class));
            node.setProperty("jcr:uuid", node.getIdentifier());
        }
        context.resourceResolver().commit();
    }
}

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
package io.uhndata.iap.datarequirement.internal;

import java.util.Objects;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;

/**
 * Gives nodes of the mock repository the {@code jcr:uuid} that Oak gives every referenceable node, and points
 * REFERENCE properties at them. The context must be on a {@code JCR_MOCK} repository.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class Referenceables
{
    private final SlingContext context;

    /**
     * Works on the repository of one test's context.
     *
     * @param context the test's context, on a {@code JCR_MOCK} repository
     */
    Referenceables(final SlingContext context)
    {
        this.context = context;
    }

    /**
     * Sets each node's {@code jcr:uuid} to the identifier the mock repository knows it by.
     *
     * @param paths the paths of the nodes
     */
    void identify(final String... paths)
    {
        try {
            for (final String path : paths) {
                final Node node = getNode(path);
                node.setProperty("jcr:uuid", node.getIdentifier());
            }
            this.context.resourceResolver().commit();
        } catch (final RepositoryException | PersistenceException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Points a REFERENCE property of one node at another.
     *
     * @param fromPath the path of the node holding the property
     * @param property the property's name
     * @param toPath the path of the node it points at
     */
    void setReference(final String fromPath, final String property, final String toPath)
    {
        try {
            getNode(fromPath).setProperty(property, getNode(toPath));
            this.context.resourceResolver().commit();
        } catch (final RepositoryException | PersistenceException e) {
            throw new IllegalStateException(e);
        }
    }

    private Node getNode(final String path)
    {
        return Objects.requireNonNull(
            Objects.requireNonNull(this.context.resourceResolver().getResource(path)).adaptTo(Node.class));
    }
}

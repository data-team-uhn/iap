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

import java.util.Objects;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;

/**
 * Makes nodes of a mock repository referenceable, for code that finds a node by its {@code jcr:uuid} or follows a
 * REFERENCE property to it. Oak gives every referenceable node a {@code jcr:uuid}. The mock repository gives none,
 * and this sets it to the identifier the mock repository knows the node by. It works only on a {@code JCR_MOCK}
 * repository, since Oak protects {@code jcr:uuid}.
 *
 * <p>Each call commits its change. A node that does not exist fails with a {@link NullPointerException}, and a
 * failing repository with an {@link IllegalStateException}.</p>
 *
 * <p>A module's tests use it through the {@code iap-java-utils} test jar, in the module's {@code tests} profile.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class Referenceables
{
    private static final String UUID_PROPERTY = "jcr:uuid";

    private final ResourceResolver resolver;

    /**
     * Works on the repository a resolver reads.
     *
     * @param resolver the test context's resolver, on a {@code JCR_MOCK} repository
     */
    public Referenceables(@NotNull final ResourceResolver resolver)
    {
        this.resolver = resolver;
    }

    /**
     * Sets each node's {@code jcr:uuid} to the identifier the mock repository knows it by.
     *
     * @param paths the absolute paths of the nodes
     */
    public void identify(@NotNull final String... paths)
    {
        try {
            for (final String path : paths) {
                final Node node = nodeAt(path);
                node.setProperty(UUID_PROPERTY, node.getIdentifier());
            }
            this.resolver.commit();
        } catch (final RepositoryException | PersistenceException e) {
            throw new IllegalStateException("Cannot identify " + String.join(", ", paths), e);
        }
    }

    /**
     * Points a REFERENCE property of one node at another.
     *
     * @param fromPath the absolute path of the node holding the property
     * @param property the property's name, such as {@code schemaVersion}
     * @param toPath the absolute path of the node it points at
     */
    public void setReference(@NotNull final String fromPath, @NotNull final String property,
        @NotNull final String toPath)
    {
        try {
            nodeAt(fromPath).setProperty(property, nodeAt(toPath));
            this.resolver.commit();
        } catch (final RepositoryException | PersistenceException e) {
            throw new IllegalStateException("Cannot point " + fromPath + "/" + property + " at " + toPath, e);
        }
    }

    private Node nodeAt(final String path)
    {
        final Resource resource = Objects.requireNonNull(this.resolver.getResource(path), () -> "No node at " + path);
        return Objects.requireNonNull(resource.adaptTo(Node.class));
    }
}

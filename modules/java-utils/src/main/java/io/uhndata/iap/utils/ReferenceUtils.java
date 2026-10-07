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
package io.uhndata.iap.utils;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;

/**
 * Writing references, which the resource API cannot: it stores a string holding the identifier, which a node type
 * declaring a {@code REFERENCE} refuses at commit, so a reference is set on the JCR node instead.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class ReferenceUtils
{
    private ReferenceUtils()
    {
    }

    /**
     * Points a property of one resource at another, as a {@code REFERENCE}.
     *
     * @param holder the resource the property is set on
     * @param property the property's name
     * @param target the resource it points at, which must be referenceable
     * @throws PersistenceException when either resource is not a node, or the repository refuses the reference
     */
    public static void setReference(@NotNull final Resource holder, @NotNull final String property,
        @NotNull final Resource target) throws PersistenceException
    {
        final Node node = holder.adaptTo(Node.class);
        final Node referenced = target.adaptTo(Node.class);
        final String failure = "Cannot point " + property + " of " + holder.getPath() + " at " + target.getPath();
        if (node == null || referenced == null) {
            throw new PersistenceException(failure);
        }
        try {
            node.setProperty(property, referenced);
        } catch (final RepositoryException e) {
            throw new PersistenceException(failure, e);
        }
    }
}

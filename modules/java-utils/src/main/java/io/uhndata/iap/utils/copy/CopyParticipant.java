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
package io.uhndata.iap.utils.copy;

import java.util.Map;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;

import org.jetbrains.annotations.NotNull;

/**
 * What a module knows about copying the content it is responsible for: what is maintained rather than held, and so
 * not copied, and what has to be adjusted once a copy is made. A module registers one as an OSGi service, and
 * every {@link ContentCopier} consults it. Each method has a default that changes nothing.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface CopyParticipant
{
    /**
     * Whether a property is left out of copies, e.g. one computed from the content around it. One participant
     * answering {@code true} is enough: the property is left out whatever all the others answer.
     *
     * @param property a property of a node being copied
     * @return {@code true} to leave it out
     * @throws RepositoryException when the property cannot be read
     */
    default boolean skips(@NotNull final Property property) throws RepositoryException
    {
        return false;
    }

    /**
     * Whether a child node is left out of copies, with everything under it, e.g. a container the module maintains.
     * One participant answering {@code true} is enough: the child is left out whatever all the others answer.
     *
     * @param child a child of a node being copied
     * @return {@code true} to leave it out
     * @throws RepositoryException when the node cannot be read
     */
    default boolean skips(@NotNull final Node child) throws RepositoryException
    {
        return false;
    }

    /**
     * Adjusts a finished copy, e.g. pointing values that name copied nodes at their copies.
     *
     * @param source the node copied
     * @param copy the node that received the copy
     * @param identifiers the identifiers of the copied referenceable nodes, each original's mapped to its copy's
     * @throws RepositoryException when the copy cannot be adjusted
     */
    default void afterCopy(@NotNull final Node source, @NotNull final Node copy,
        @NotNull final Map<String, String> identifiers) throws RepositoryException
    {
        // Nothing to adjust by default
    }
}

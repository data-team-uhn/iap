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
 * What a module knows about copying its own content: what is computed rather than held, and so not copied, and what
 * to adjust once a copy is made. A module registers one as an OSGi service, and the {@link ContentCopier} consults
 * every one. Each method has a default that changes nothing.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface CopyParticipant
{
    /**
     * Whether a property is left out of copies, such as one computed from the content around it. One participant
     * answering {@code true} is enough, whatever the others answer.
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
     * Whether a child node is left out of copies, with everything under it, such as a container the module
     * maintains. One participant answering {@code true} is enough, whatever the others answer.
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
     * Adjusts a finished copy, such as pointing values that name copied nodes at their copies. It runs once per
     * copy, after every node and reference is in place.
     *
     * @param source the node copied
     * @param copy the node that received the copy
     * @param identifiers the {@code jcr:uuid} of each copied referenceable node, mapped to its copy's; the source's
     *     identifier, a {@code jcr:uuid} or not, maps to the copy's
     * @throws RepositoryException when the copy cannot be adjusted
     */
    default void afterCopy(@NotNull final Node source, @NotNull final Node copy,
        @NotNull final Map<String, String> identifiers) throws RepositoryException
    {
        // Nothing to adjust by default
    }
}

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
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.jetbrains.annotations.NotNull;

/**
 * Copies content: a node's properties and descendants into another node, whatever their structure. Each descendant
 * keeps its name, type, mixins and order, and binaries are copied too. A reference to a node inside the copy points
 * at that node's copy, and a reference to anything outside is kept. Protected properties, {@code jcr:lastModified}
 * and {@code jcr:lastModifiedBy} are never copied. Each {@link CopyParticipant} can leave out more, or adjust the
 * finished copy.
 *
 * <p>The copy is made in the session of the nodes given, and left among its pending changes.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface ContentCopier
{
    /**
     * Copies what a node holds into another node. The target keeps its own name, type, mixins and properties, except
     * the properties the source also has, which the copy overwrites. A child the target already has, such as an
     * autocreated one, is filled in rather than duplicated.
     *
     * @param source the node copied
     * @param target the node receiving the copy
     * @param skipped the names of the source's own properties that are not copied, such as a label the target has
     *            its own of; it does not apply to the descendants
     * @param dropped the values left out of the source's own multi-valued properties, by property name, such as tags
     *            that say where the source stands; a reference's value is the {@code jcr:uuid} it holds
     * @return the {@code jcr:uuid} of each copied referenceable node, mapped to its copy's; the source's identifier,
     *         a {@code jcr:uuid} or not, maps to the target's
     * @throws RepositoryException when the source cannot be read or the copy cannot be written
     */
    @NotNull
    Map<String, String> copy(@NotNull Node source, @NotNull Node target, @NotNull Set<String> skipped,
        @NotNull Map<String, Set<String>> dropped) throws RepositoryException;
}

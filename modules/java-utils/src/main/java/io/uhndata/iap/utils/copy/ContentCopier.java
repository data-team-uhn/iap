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
 * Copies content: a node's properties and descendants into another node, recursively, whatever their structure.
 * Each node keeps its name, type, mixins and position among its siblings, and binaries are copied with it.
 * References to nodes inside the copy are pointed at their copies, and references to anything outside are kept.
 * Protected properties and modification stamps belong to the repository and are never copied; what else is left
 * out, or adjusted once the copy is made, is contributed by the modules that know, as {@link CopyParticipant}s.
 *
 * <p>The copy is made in the session of the nodes given, and saved with it: in a workflow, as part of the engine's
 * single commit.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface ContentCopier
{
    /**
     * Copies what a node holds into another node. The target keeps its own name, type and properties, except those
     * the source also has, which the copy overwrites; children the target already has, such as autocreated ones,
     * are filled in rather than duplicated.
     *
     * @param source the node copied
     * @param target the node receiving the copy
     * @param skipped properties of the source node itself that are not copied, e.g. a label the target has its own
     *            of; its descendants are copied whole
     * @param dropped values left out of multi-valued properties of the source node itself, by property name, a
     *            reference's by the identifier it holds; e.g. tags that say where the source stands rather than what
     *            it holds
     * @return the identifiers of the copied referenceable nodes, each original's mapped to its copy's
     * @throws RepositoryException when the source cannot be read or the copy cannot be written
     */
    @NotNull
    Map<String, String> copy(@NotNull Node source, @NotNull Node target, @NotNull Set<String> skipped,
        @NotNull Map<String, Set<String>> dropped) throws RepositoryException;
}

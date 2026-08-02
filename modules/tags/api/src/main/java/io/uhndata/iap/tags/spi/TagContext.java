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
package io.uhndata.iap.tags.spi;

import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Everything a {@link TagProcessor} may look at while computing the derived tags of one node. The context is created
 * by the commit editor for one node of one commit and must not be retained past the {@link TagProcessor#computeTags}
 * call that received it.
 *
 * <p>
 * What the context offers is deliberately bounded by the processor's {@link TagProcessor#getScope() scope}: the
 * editor only visits the nodes a commit changed, so a processor reading state that the editor does not watch would
 * produce tags that silently go stale when that state changes. Only {@link TagProcessor.Scope#ENTITY} processors get
 * a {@link #getScopeRoot() scope root}, and only because the editor recomputes all of them whenever anything inside
 * that entity changes.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface TagContext
{
    /**
     * The current state of the node being processed, including any derived tag properties already recomputed for it
     * in this commit.
     *
     * @return a node state, never {@code null}
     */
    @NotNull
    NodeState getNode();

    /**
     * The current state of the processed node's parent. In the {@link TagProcessor.Phase#TOP_DOWN} and
     * {@link TagProcessor.Phase#LOCAL} phases the parent's derived tags are already recomputed for this commit.
     *
     * @return a node state, {@code null} when the processed node is the repository root
     */
    @Nullable
    NodeState getParent();

    /**
     * The absolute path of the node being processed, e.g. for resolving references or for logging.
     *
     * @return an absolute path, {@code /} for the repository root
     */
    @NotNull
    String getPath();

    /**
     * The state of the {@code iap:Entity} node enclosing the processed node, the recomputation unit of an
     * {@link TagProcessor.Scope#ENTITY} processor. The processed node itself is the scope root when it is the entity.
     *
     * @return a node state, {@code null} for a {@link TagProcessor.Scope#NODE} processor, and for an
     *         {@link TagProcessor.Scope#ENTITY} one processing a node outside any entity
     */
    @Nullable
    NodeState getScopeRoot();

    /**
     * The tag definitions in effect for this commit.
     *
     * @return the definitions, never {@code null}
     */
    @NotNull
    TagDefinitions getDefinitions();
}

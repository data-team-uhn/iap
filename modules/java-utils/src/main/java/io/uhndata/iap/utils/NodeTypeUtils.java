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

import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Answers whether a node or a node type is of some type, against the node type registry Oak keeps at
 * {@code /jcr:system/jcr:nodeTypes}. For code that sees the repository as node states, such as a commit hook,
 * and has no JCR session to ask.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class NodeTypeUtils
{
    /** The transitively expanded supertypes Oak stores on each registered node type. */
    private static final String SUPERTYPES_PROPERTY = "rep:supertypes";

    private NodeTypeUtils()
    {
    }

    /**
     * The node type registry.
     *
     * @param root the repository root node state
     * @return the {@code /jcr:system/jcr:nodeTypes} node state
     */
    @NotNull
    public static NodeState registry(@NotNull final NodeState root)
    {
        return root.getChildNode("jcr:system").getChildNode("jcr:nodeTypes");
    }

    /**
     * Whether a node type is the wanted one or extends it.
     *
     * @param type the node type to check, may be {@code null}
     * @param wanted the node type it should be, or extend
     * @param registry the node type registry, as {@link #registry(NodeState)} finds it
     * @return {@code false} for a {@code null} type, or one the registry does not know and that is not
     *         {@code wanted} itself
     */
    public static boolean isOrExtends(@Nullable final String type, @NotNull final String wanted,
        @NotNull final NodeState registry)
    {
        if (type == null) {
            return false;
        }
        return wanted.equals(type) || StreamSupport.stream(
            registry.getChildNode(type).getNames(SUPERTYPES_PROPERTY).spliterator(), false).anyMatch(wanted::equals);
    }

    /**
     * Whether a node is of the wanted type, or a subtype of it, by its primary type or any of its mixins.
     *
     * @param node the node to check
     * @param wanted the node type it should have
     * @param registry the node type registry, as {@link #registry(NodeState)} finds it
     * @return {@code true} if one of the node's types is or extends {@code wanted}
     */
    public static boolean isOfType(@NotNull final NodeState node, @NotNull final String wanted,
        @NotNull final NodeState registry)
    {
        return Stream.concat(Stream.ofNullable(node.getName("jcr:primaryType")),
            StreamSupport.stream(node.getNames("jcr:mixinTypes").spliterator(), false))
            .anyMatch(type -> isOrExtends(type, wanted, registry));
    }
}

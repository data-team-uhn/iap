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
package io.uhndata.iap.tags.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;

import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.spi.state.NodeState;

import io.uhndata.iap.tags.api.TagManager;

/**
 * Answers the three node type questions of tag propagation, from the node type registry under
 * {@code /jcr:system/jcr:nodeTypes}. May a node store the tag properties? Is it a {@code data:Entity}, the scope of an
 * {@link io.uhndata.iap.tags.spi.TagProcessor.Scope#ENTITY} computation? Is it a {@code tag:Boundary}, where
 * aggregated tags stop? The answers are cached per type name, so an instance must not outlive its commit.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class NodeTypeInspector
{
    /** The node type of the entities that scope an entity-scoped computation. */
    private static final String ENTITY_TYPE = "data:Entity";

    private static final String BOUNDARY_TYPE = TagManager.BOUNDARY_MIXIN;

    private static final String PRIMARY_TYPE = "jcr:primaryType";

    private static final String MIXIN_TYPES = "jcr:mixinTypes";

    /** Holds every supertype of a registered node type, the supertypes of its supertypes included. */
    private static final String SUPERTYPES_PROPERTY = "rep:supertypes";

    private final NodeState registry;

    private final Map<String, Boolean> writable = new HashMap<>();

    private final Map<String, Boolean> entities = new HashMap<>();

    private final Map<String, Boolean> boundaries = new HashMap<>();

    /**
     * Basic constructor.
     *
     * @param root the repository root state holding the node type registry
     */
    public NodeTypeInspector(final NodeState root)
    {
        this.registry = root.getChildNode("jcr:system").getChildNode("jcr:nodeTypes");
    }

    /**
     * Checks whether the tag properties may be stored on the given node. One of the node's types, or one of their
     * supertypes, must declare the {@code tags} property by name, as {@code tag:Taggable} does.
     *
     * <p>
     * A type that only accepts any property, such as {@code nt:unstructured}, does not count. The repository root
     * extends it, and a derived tag written there would fail the commit of anybody who may not write the root. A node
     * of such a type becomes taggable by adding the {@code tag:Taggable} mixin.
     * </p>
     *
     * @param node the node to check
     * @return {@code true} if one of the node's types declares the tag properties
     */
    public boolean canStoreTags(final NodeState node)
    {
        return anyTypeMatches(node.getProperty(PRIMARY_TYPE), this::isWritableType)
            || anyTypeMatches(node.getProperty(MIXIN_TYPES), this::isWritableType);
    }

    /**
     * Checks whether aggregated tags stop at the given node: whether it carries the {@code tag:Boundary} mixin, or one
     * of its types extends it, as {@code data:EntityHomepage} does.
     *
     * @param node the node to check
     * @return {@code true} if aggregated tags may not travel past this node
     */
    public boolean isTagBoundary(final NodeState node)
    {
        return anyTypeMatches(node.getProperty(PRIMARY_TYPE), this::isBoundaryType)
            || anyTypeMatches(node.getProperty(MIXIN_TYPES), this::isBoundaryType);
    }

    /**
     * Checks whether the given node is an entity, the scope of an entity-scoped computation: whether its primary type
     * is {@code data:Entity} or a subtype of it.
     *
     * @param node the node to check
     * @return {@code true} if the node is an entity
     */
    public boolean isEntity(final NodeState node)
    {
        final PropertyState primaryType = node.getProperty(PRIMARY_TYPE);
        if (primaryType == null || primaryType.isArray()) {
            return false;
        }
        return this.entities.computeIfAbsent(primaryType.getValue(Type.NAME), this::computeEntityType);
    }

    private boolean computeEntityType(final String type)
    {
        if (ENTITY_TYPE.equals(type)) {
            return true;
        }
        final PropertyState supertypes = this.registry.getChildNode(type).getProperty(SUPERTYPES_PROPERTY);
        return supertypes != null && StreamSupport.stream(supertypes.getValue(Type.NAMES).spliterator(), false)
            .anyMatch(ENTITY_TYPE::equals);
    }

    private static boolean anyTypeMatches(final PropertyState types, final Predicate<String> verdict)
    {
        if (types == null) {
            return false;
        }
        if (types.isArray()) {
            return StreamSupport.stream(types.getValue(Type.NAMES).spliterator(), false).anyMatch(verdict);
        }
        return verdict.test(types.getValue(Type.NAME));
    }

    private boolean isWritableType(final String type)
    {
        return this.writable.computeIfAbsent(type, this::computeWritableType);
    }

    private boolean isBoundaryType(final String type)
    {
        return this.boundaries.computeIfAbsent(type, this::computeBoundaryType);
    }

    private boolean computeBoundaryType(final String type)
    {
        if (BOUNDARY_TYPE.equals(type)) {
            return true;
        }
        final PropertyState supertypes = this.registry.getChildNode(type).getProperty(SUPERTYPES_PROPERTY);
        return supertypes != null && StreamSupport.stream(supertypes.getValue(Type.NAMES).spliterator(), false)
            .anyMatch(BOUNDARY_TYPE::equals);
    }

    private boolean computeWritableType(final String type)
    {
        final NodeState definition = this.registry.getChildNode(type);
        if (!definition.exists()) {
            return false;
        }
        if (accepts(definition)) {
            return true;
        }
        final PropertyState supertypes = definition.getProperty(SUPERTYPES_PROPERTY);
        return supertypes != null && StreamSupport.stream(supertypes.getValue(Type.NAMES).spliterator(), false)
            .anyMatch(supertype -> accepts(this.registry.getChildNode(supertype)));
    }

    private boolean accepts(final NodeState definition)
    {
        return definition.getChildNode("rep:namedPropertyDefinitions").hasChildNode(TagManager.TAGS_PROPERTY);
    }
}

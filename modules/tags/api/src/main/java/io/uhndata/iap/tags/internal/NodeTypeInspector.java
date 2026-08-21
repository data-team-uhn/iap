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
 * Answers the three node type questions the tag propagation needs, against the node type registry materialized at
 * {@code /jcr:system/jcr:nodeTypes}: whether a node may store the tag properties at all, whether it is the
 * {@code iap:Entity} that bounds an {@link io.uhndata.iap.tags.spi.TagProcessor.Scope#ENTITY} computation, and
 * whether it is the {@code iap:TagBoundary} that aggregated tags travel up to and no further. Verdicts are cached
 * per type name, so one instance must not outlive the commit it was created for.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class NodeTypeInspector
{
    /** The node type of the standalone records that bound an entity-scoped computation. */
    private static final String ENTITY_TYPE = "iap:Entity";

    /** The mixin declaring that aggregated tags stop at a node rather than climbing past it. */
    private static final String BOUNDARY_TYPE = TagManager.BOUNDARY_MIXIN;

    private static final String PRIMARY_TYPE = "jcr:primaryType";

    private static final String MIXIN_TYPES = "jcr:mixinTypes";

    /** Holds the full, transitively expanded set of supertypes of a registered node type. */
    private static final String SUPERTYPES = "rep:supertypes";

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
     * Checks whether tag properties may be stored on the given node: one of the node's primary or mixin types, or one
     * of their supertypes, must declare the tag properties by name, which is what {@code iap:Taggable} does and what
     * every {@code iap:Content} node inherits.
     *
     * <p>
     * Taggability has to be <em>declared</em> rather than merely tolerated. A type that happens to accept residual
     * properties — {@code nt:unstructured}, and therefore {@code rep:root} and every free-form container in the
     * repository — has not opted into tags, and derived properties written onto such nodes are how an aggregated tag
     * used to reach the repository root. A node of such a type becomes taggable by adding the mixin.
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
     * Checks whether the given node is where aggregated tags stop, i.e. whether it carries the
     * {@code iap:TagBoundary} mixin, either directly or through one of its types' supertypes as
     * {@code iap:EntityHomepage} does.
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
     * Checks whether the given node is one of the standalone records that bound an entity-scoped computation, i.e.
     * whether its primary type is {@code iap:Entity} or a subtype of it.
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
        final PropertyState supertypes = this.registry.getChildNode(type).getProperty(SUPERTYPES);
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
        final PropertyState supertypes = this.registry.getChildNode(type).getProperty(SUPERTYPES);
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
        final PropertyState supertypes = definition.getProperty(SUPERTYPES);
        return supertypes != null && StreamSupport.stream(supertypes.getValue(Type.NAMES).spliterator(), false)
            .anyMatch(supertype -> accepts(this.registry.getChildNode(supertype)));
    }

    private boolean accepts(final NodeState definition)
    {
        return definition.getChildNode("rep:namedPropertyDefinitions").hasChildNode(TagManager.TAGS_PROPERTY);
    }
}

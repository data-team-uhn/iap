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
import java.util.stream.StreamSupport;

import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.spi.state.NodeState;

import io.uhndata.iap.tags.api.TagManager;

/**
 * Answers the two node type questions the tag propagation needs, against the node type registry materialized at
 * {@code /jcr:system/jcr:nodeTypes}: whether a node may store the tag properties at all, and whether it is the
 * {@code iap:Entity} that bounds an {@link io.uhndata.iap.tags.spi.TagProcessor.Scope#ENTITY} computation. Verdicts
 * are cached per type name, so one instance must not outlive the commit it was created for.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class NodeTypeInspector
{
    /** The node type of the standalone records that bound an entity-scoped computation. */
    private static final String ENTITY_TYPE = "iap:Entity";

    private static final String PRIMARY_TYPE = "jcr:primaryType";

    /** Holds the full, transitively expanded set of supertypes of a registered node type. */
    private static final String SUPERTYPES = "rep:supertypes";

    private final NodeState registry;

    private final Map<String, Boolean> writable = new HashMap<>();

    private final Map<String, Boolean> entities = new HashMap<>();

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
     * of their supertypes, must either declare the tag properties by name (like {@code iap:Content} does) or accept
     * residual properties (like {@code nt:unstructured} does). Writing to any other node would be rejected by the
     * type validation, failing the whole commit.
     *
     * @param node the node to check
     * @return {@code true} if one of the node's types accepts the tag properties
     */
    public boolean canStoreTags(final NodeState node)
    {
        return checkTypes(node.getProperty(PRIMARY_TYPE)) || checkTypes(node.getProperty("jcr:mixinTypes"));
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

    private boolean checkTypes(final PropertyState types)
    {
        if (types == null) {
            return false;
        }
        if (types.isArray()) {
            return StreamSupport.stream(types.getValue(Type.NAMES).spliterator(), false).anyMatch(this::isWritableType);
        }
        return isWritableType(types.getValue(Type.NAME));
    }

    private boolean isWritableType(final String type)
    {
        return this.writable.computeIfAbsent(type, this::computeWritableType);
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
        return definition.hasChildNode("rep:residualPropertyDefinitions")
            || definition.getChildNode("rep:namedPropertyDefinitions").hasChildNode(TagManager.TAGS_PROPERTY);
    }
}

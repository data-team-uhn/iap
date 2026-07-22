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

import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.spi.state.NodeState;

import io.uhndata.iap.tags.api.TagManager;

/**
 * Decides whether tag properties may be stored on a node, by checking the node's types against the node type
 * registry materialized at {@code /jcr:system/jcr:nodeTypes}: the node's primary or mixin types, or any of their
 * supertypes, must either declare the tag properties by name (like {@code iap:Content} does) or accept residual
 * properties (like {@code nt:unstructured} or {@code sling:Folder} do). Writing to any other node would be rejected
 * by the type validation, failing the whole commit. Verdicts are cached, so one instance must not outlive the
 * commit it was created for.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class TagWritabilityChecker
{
    private final NodeState registry;

    private final Map<String, Boolean> cache = new HashMap<>();

    /**
     * Basic constructor.
     *
     * @param root the repository root state holding the node type registry
     */
    public TagWritabilityChecker(final NodeState root)
    {
        this.registry = root.getChildNode("jcr:system").getChildNode("jcr:nodeTypes");
    }

    /**
     * Checks whether tag properties may be stored on the given node.
     *
     * @param node the node to check
     * @return {@code true} if one of the node's types accepts the tag properties
     */
    public boolean canStoreTags(final NodeState node)
    {
        return checkTypes(node.getProperty("jcr:primaryType")) || checkTypes(node.getProperty("jcr:mixinTypes"));
    }

    private boolean checkTypes(final PropertyState types)
    {
        if (types == null) {
            return false;
        }
        if (types.isArray()) {
            for (final String type : types.getValue(Type.NAMES)) {
                if (isWritableType(type)) {
                    return true;
                }
            }
            return false;
        }
        return isWritableType(types.getValue(Type.NAME));
    }

    private boolean isWritableType(final String type)
    {
        return this.cache.computeIfAbsent(type, this::computeWritableType);
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
        // rep:supertypes holds the full, transitively expanded set of supertypes
        final PropertyState supertypes = definition.getProperty("rep:supertypes");
        if (supertypes != null) {
            for (final String supertype : supertypes.getValue(Type.NAMES)) {
                if (accepts(this.registry.getChildNode(supertype))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean accepts(final NodeState definition)
    {
        return definition.hasChildNode("rep:residualPropertyDefinitions")
            || definition.getChildNode("rep:namedPropertyDefinitions").hasChildNode(TagManager.TAGS_PROPERTY);
    }
}

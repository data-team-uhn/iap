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
package io.uhndata.iap.entities.internal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry;
import org.apache.jackrabbit.oak.spi.state.NodeState;

/**
 * Answers the node type questions that decide what a change modifies, from the node type registry stored at
 * {@code /jcr:system/jcr:nodeTypes}. It caches what it reads, so one instance serves one commit.
 *
 * <p>Whether a change is part of a node's content follows its definition's {@code onParentVersion}: what a version
 * of the node leaves out ({@code IGNORE}) is not its content. When several definitions could apply, the change
 * counts unless all of them say {@code IGNORE}.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class NodeTypeRules
{
    private static final String ENTITY_TYPE = "data:Entity";

    private static final String LAST_MODIFIED_MIXIN = "mix:lastModified";

    private static final String IGNORE_ACTION = "IGNORE";

    private static final String ON_PARENT_VERSION_PROPERTY = "jcr:onParentVersion";

    private static final String REQUIRED_PRIMARY_TYPES_PROPERTY = "jcr:requiredPrimaryTypes";

    private static final String PRIMARY_TYPE_PROPERTY = "jcr:primaryType";

    private static final String MIXIN_TYPES_PROPERTY = "jcr:mixinTypes";

    private static final String SUPERTYPES_PROPERTY = "rep:supertypes";

    /** The registry stores these properties' definitions under another name, which the definition node can hold. */
    private static final Map<String, String> REGISTRY_NAMES =
        Map.of(PRIMARY_TYPE_PROPERTY, "rep:primaryType", MIXIN_TYPES_PROPERTY, "rep:mixinTypes",
            "jcr:uuid", "rep:uuid");

    private final NodeState registry;

    /** Each type looked up so far, with all its supertypes. */
    private final Map<String, Set<String>> lineages = new HashMap<>();

    /**
     * Constructor.
     *
     * @param root the repository root holding the node type registry
     */
    NodeTypeRules(final NodeState root)
    {
        this.registry = root.getChildNode("jcr:system").getChildNode("jcr:nodeTypes");
    }

    /**
     * Whether a node is an entity, a standalone record whose parts are its content.
     *
     * @param node the node to check
     * @return {@code true} if its primary type is {@code data:Entity} or a subtype of it
     */
    boolean isEntity(final NodeState node)
    {
        return lineage(node.getName(PRIMARY_TYPE_PROPERTY)).contains(ENTITY_TYPE);
    }

    /**
     * Whether a node records when it was last modified, through its primary type or a mixin.
     *
     * @param node the node to check
     * @return {@code true} if one of its types is {@code mix:lastModified} or a subtype of it
     */
    boolean tracksModifications(final NodeState node)
    {
        return types(node).anyMatch(type -> lineage(type).contains(LAST_MODIFIED_MIXIN));
    }

    /**
     * Whether a property is left out of its node's content.
     *
     * @param node the node holding the property
     * @param name the property name
     * @return {@code true} if every definition that could apply to it says {@code IGNORE}
     */
    boolean isUnversionedProperty(final NodeState node, final String name)
    {
        final String registryName = REGISTRY_NAMES.getOrDefault(name, name);
        final List<NodeState> named = types(node)
            .flatMap(type -> children(definition(type).getChildNode("rep:namedPropertyDefinitions")
                .getChildNode(registryName)))
            .collect(Collectors.toList());
        return allIgnored(named.isEmpty()
            ? types(node).flatMap(type -> children(definition(type).getChildNode("rep:residualPropertyDefinitions")))
                .collect(Collectors.toList())
            : named);
    }

    /**
     * Whether a child node is left out of its parent's content.
     *
     * @param parent the parent node
     * @param name the child's name
     * @param child the child node, as it was before the commit if the commit removes it
     * @return {@code true} if every definition that could apply to it says {@code IGNORE}
     */
    boolean isUnversionedChild(final NodeState parent, final String name, final NodeState child)
    {
        final Set<String> childTypes = lineage(child.getName(PRIMARY_TYPE_PROPERTY));
        final Predicate<NodeState> admitsChild = definition -> childTypes.containsAll(names(definition));
        final List<NodeState> named = types(parent)
            .flatMap(type -> children(definition(type).getChildNode("rep:namedChildNodeDefinitions")
                .getChildNode(name)))
            .filter(admitsChild)
            .collect(Collectors.toList());
        return allIgnored(named.isEmpty()
            ? types(parent).flatMap(type -> children(definition(type).getChildNode("rep:residualChildNodeDefinitions")))
                .filter(admitsChild)
                .collect(Collectors.toList())
            : named);
    }

    private static boolean allIgnored(final List<NodeState> definitions)
    {
        return !definitions.isEmpty()
            && definitions.stream()
                .allMatch(definition -> IGNORE_ACTION.equals(definition.getString(ON_PARENT_VERSION_PROPERTY)));
    }

    private Set<String> lineage(final String type)
    {
        if (type == null) {
            return Set.of();
        }
        return this.lineages.computeIfAbsent(type, this::readLineage);
    }

    private Set<String> readLineage(final String type)
    {
        final Set<String> lineage = new HashSet<>(names(definition(type).getProperty(SUPERTYPES_PROPERTY)));
        lineage.add(type);
        return lineage;
    }

    private NodeState definition(final String type)
    {
        return this.registry.getChildNode(type);
    }

    private static Stream<String> types(final NodeState node)
    {
        final String primary = node.getName(PRIMARY_TYPE_PROPERTY);
        return Stream.concat(primary == null ? Stream.empty() : Stream.of(primary),
            StreamSupport.stream(node.getNames(MIXIN_TYPES_PROPERTY).spliterator(), false));
    }

    private static Stream<NodeState> children(final NodeState node)
    {
        return StreamSupport.stream(node.getChildNodeEntries().spliterator(), false)
            .map(ChildNodeEntry::getNodeState);
    }

    private static Set<String> names(final NodeState childDefinition)
    {
        return names(childDefinition.getProperty(REQUIRED_PRIMARY_TYPES_PROPERTY));
    }

    private static Set<String> names(final PropertyState property)
    {
        if (property == null) {
            return Set.of();
        }
        return StreamSupport.stream(property.getValue(Type.NAMES).spliterator(), false).collect(Collectors.toSet());
    }
}

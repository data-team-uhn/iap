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
package io.uhndata.iap.entities.nodetypes;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.jetbrains.annotations.NotNull;

/**
 * Answers the node type questions that decide what a change modifies, from the node type registry stored at
 * {@code /jcr:system/jcr:nodeTypes}. It caches the type hierarchy it reads, so one instance serves one commit.
 *
 * <p>Whether an item is part of a node's content follows its definition's {@code onParentVersion}. A version of the
 * node leaves out what is {@code IGNORE} or {@code COMPUTE}, so neither is content. The definition is chosen as Oak
 * chooses it. When several of the node's types offer one, the item is content if any of them keeps it.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class NodeTypeRules
{
    private static final String ENTITY_TYPE = "data:Entity";

    private static final String LAST_MODIFIED_MIXIN = "mix:lastModified";

    /** The {@code onParentVersion} actions under which a version does not keep the value it finds. */
    private static final Set<String> UNVERSIONED_ON_PARENT_VERSION_ACTIONS = Set.of("IGNORE", "COMPUTE");

    /** The {@code onParentVersion} action of a value worked out from elsewhere. */
    private static final Set<String> COMPUTED_ON_PARENT_VERSION_ACTIONS = Set.of("COMPUTE");

    private static final String ON_PARENT_VERSION_PROPERTY = "jcr:onParentVersion";

    private static final String REQUIRED_PRIMARY_TYPES_PROPERTY = "jcr:requiredPrimaryTypes";

    private static final String PRIMARY_TYPE_PROPERTY = "jcr:primaryType";

    private static final String MIXIN_TYPES_PROPERTY = "jcr:mixinTypes";

    private static final String SUPERTYPES_PROPERTY = "rep:supertypes";

    private static final String NAMED_PROPERTIES = "rep:namedPropertyDefinitions";

    private static final String RESIDUAL_PROPERTIES = "rep:residualPropertyDefinitions";

    private static final String NAMED_CHILDREN = "rep:namedChildNodeDefinitions";

    private static final String RESIDUAL_CHILDREN = "rep:residualChildNodeDefinitions";

    /** The names the registry files these properties' definitions under. */
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
    public NodeTypeRules(@NotNull final NodeState root)
    {
        this.registry = root.getChildNode("jcr:system").getChildNode("jcr:nodeTypes");
    }

    /**
     * Whether a node is an entity, a standalone record whose parts are its content.
     *
     * @param node the node to check
     * @return {@code true} if its primary type is {@code data:Entity} or a subtype of it
     */
    public boolean isEntity(@NotNull final NodeState node)
    {
        return lineage(node.getName(PRIMARY_TYPE_PROPERTY)).contains(ENTITY_TYPE);
    }

    /**
     * Whether a node records when it was last modified, through its primary type or a mixin.
     *
     * @param node the node to check
     * @return {@code true} if one of its types is {@code mix:lastModified} or a subtype of it
     */
    public boolean tracksModifications(@NotNull final NodeState node)
    {
        return types(node).anyMatch(type -> lineage(type).contains(LAST_MODIFIED_MIXIN));
    }

    /**
     * Whether a property is left out of its node's content.
     *
     * @param node the node holding the property
     * @param property the property, as it was before the commit if the commit removes it
     * @return {@code true} if no definition that could apply to it keeps it in a version
     */
    public boolean isUnversionedProperty(@NotNull final NodeState node, @NotNull final PropertyState property)
    {
        return allDeclare(propertyDefinitions(node, property), UNVERSIONED_ON_PARENT_VERSION_ACTIONS);
    }

    /**
     * Whether a property holds a value worked out from elsewhere, such as the tags a node derives from its
     * surroundings.
     *
     * @param node the node holding the property
     * @param property the property, as it was before the commit if the commit removes it
     * @return {@code true} if every definition that could apply to it declares {@code COMPUTE}
     */
    public boolean isComputedProperty(@NotNull final NodeState node, @NotNull final PropertyState property)
    {
        return allDeclare(propertyDefinitions(node, property), COMPUTED_ON_PARENT_VERSION_ACTIONS);
    }

    /**
     * Whether a child node is left out of its parent's content. Named definitions come before residual ones, and a
     * definition applies only to a child of a type it accepts.
     *
     * @param parent the parent node
     * @param name the child's node name
     * @param child the child node, as it was before the commit if the commit removes it
     * @return {@code true} if no definition that could apply to it keeps it in a version
     */
    public boolean isUnversionedChild(@NotNull final NodeState parent, @NotNull final String name,
        @NotNull final NodeState child)
    {
        final Set<String> childTypes = lineage(child.getName(PRIMARY_TYPE_PROPERTY));
        final Predicate<NodeState> accepted =
            definition -> childTypes.containsAll(names(definition.getProperty(REQUIRED_PRIMARY_TYPES_PROPERTY)));
        return allDeclare(firstFound(List.of(
            () -> definitionsUnder(parent, accepted, NAMED_CHILDREN, name),
            () -> definitionsUnder(parent, accepted, RESIDUAL_CHILDREN))), UNVERSIONED_ON_PARENT_VERSION_ACTIONS);
    }

    /**
     * The definitions that could apply to a property. Oak looks for a named definition for the value's type, then
     * for any type, then for residual definitions in the same order.
     *
     * @param node the node holding the property
     * @param property the property
     * @return the definitions of the first kind found; empty if the node's types declare none
     */
    private List<NodeState> propertyDefinitions(final NodeState node, final PropertyState property)
    {
        final String name = REGISTRY_NAMES.getOrDefault(property.getName(), property.getName());
        final String exact = property.getType().toString();
        final String any = (property.isArray() ? Type.UNDEFINEDS : Type.UNDEFINED).toString();
        return firstFound(List.of(
            () -> definitions(node, NAMED_PROPERTIES, name, exact),
            () -> definitions(node, NAMED_PROPERTIES, name, any),
            () -> definitions(node, RESIDUAL_PROPERTIES, exact),
            () -> definitions(node, RESIDUAL_PROPERTIES, any)));
    }

    private static boolean allDeclare(final List<NodeState> definitions, final Set<String> actions)
    {
        return !definitions.isEmpty() && definitions.stream()
            .allMatch(definition -> actions.contains(definition.getString(ON_PARENT_VERSION_PROPERTY)));
    }

    /**
     * The first non-empty set of definitions, trying each source in turn.
     *
     * @param sources where to look, in the order Oak looks
     * @return the definitions found; empty if no source has any
     */
    private static List<NodeState> firstFound(final List<Supplier<List<NodeState>>> sources)
    {
        return sources.stream().map(Supplier::get).filter(found -> !found.isEmpty()).findFirst().orElse(List.of());
    }

    /**
     * The definition at a path inside each of a node's types, where the type has one.
     *
     * @param node the node whose types to look in
     * @param path the path inside a type's registry entry
     * @return the definitions found, one at most per type
     */
    private List<NodeState> definitions(final NodeState node, final String... path)
    {
        return types(node).map(type -> walk(definition(type), path)).filter(NodeState::exists).toList();
    }

    /**
     * The definitions under a path inside each of a node's types, keeping those that pass a filter.
     *
     * @param node the node whose types to look in
     * @param accepted which definitions to keep
     * @param path the path inside a type's registry entry
     * @return the definitions found
     */
    private List<NodeState> definitionsUnder(final NodeState node, final Predicate<NodeState> accepted,
        final String... path)
    {
        return types(node).flatMap(type -> children(walk(definition(type), path))).filter(accepted).toList();
    }

    private static NodeState walk(final NodeState start, final String... path)
    {
        NodeState current = start;
        for (final String name : path) {
            current = current.getChildNode(name);
        }
        return current;
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
        return Stream.concat(Stream.ofNullable(node.getName(PRIMARY_TYPE_PROPERTY)),
            stream(node.getNames(MIXIN_TYPES_PROPERTY)));
    }

    private static Stream<NodeState> children(final NodeState node)
    {
        return stream(node.getChildNodeEntries()).map(ChildNodeEntry::getNodeState);
    }

    private static Set<String> names(final PropertyState property)
    {
        if (property == null) {
            return Set.of();
        }
        return stream(property.getValue(Type.NAMES)).collect(Collectors.toSet());
    }

    private static <T> Stream<T> stream(final Iterable<T> items)
    {
        return StreamSupport.stream(items.spliterator(), false);
    }
}

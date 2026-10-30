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

import java.util.Calendar;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.memory.PropertyStates;
import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.util.ISO8601;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Answers whether a node or a node type is of some type, and fills in what a node's type autocreates, against the
 * node type registry Oak keeps at {@code /jcr:system/jcr:nodeTypes}. For code that sees the repository as node
 * states, such as a commit hook, and has no JCR session to ask.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class NodeTypeUtils
{
    /** The transitively expanded supertypes Oak stores on each registered node type. */
    private static final String SUPERTYPES_PROPERTY = "rep:supertypes";

    private static final String JCR_PRIMARY_TYPE = "jcr:primaryType";

    private static final String JCR_MIXIN_TYPES = "jcr:mixinTypes";

    private static final String JCR_UUID = "jcr:uuid";

    private static final String JCR_CREATED = "jcr:created";

    private static final String JCR_CREATED_BY = "jcr:createdBy";

    private static final String JCR_LAST_MODIFIED = "jcr:lastModified";

    private static final String JCR_LAST_MODIFIED_BY = "jcr:lastModifiedBy";

    private static final String NAMED_PROPERTY_DEFINITIONS = "rep:namedPropertyDefinitions";

    private static final String NAMED_CHILD_NODE_DEFINITIONS = "rep:namedChildNodeDefinitions";

    /** Oak files the definition of {@code jcr:uuid} under this name. */
    private static final String REP_UUID = "rep:uuid";

    /** Oak files the definitions of {@code jcr:primaryType} and {@code jcr:mixinTypes} under these names. */
    private static final Set<String> TYPE_DEFINITIONS = Set.of("rep:primaryType", "rep:mixinTypes");

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
     * @param wanted the node type it should be or extend
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
        return Stream.concat(Stream.ofNullable(node.getName(JCR_PRIMARY_TYPE)),
            StreamSupport.stream(node.getNames(JCR_MIXIN_TYPES).spliterator(), false))
            .anyMatch(type -> isOrExtends(type, wanted, registry));
    }

    /**
     * Fills in the properties and child nodes that a node's types autocreate, on the node and every node below it.
     * The JCR API does this for a node it creates, but a node written straight into a commit's {@link NodeBuilder}
     * gets nothing. A commit editor writing new content calls this once it is written, so a node type's defaults
     * apply without the content restating them. What a node already has is left alone, so filling the same subtree
     * twice changes nothing.
     *
     * @param node the root of the subtree to fill in
     * @param registry the node type registry, as {@link #registry(NodeState)} finds it
     * @param userId who to record as {@code jcr:createdBy} and {@code jcr:lastModifiedBy}, {@code null} for nobody
     * @param now what to record as {@code jcr:created} and {@code jcr:lastModified}
     */
    public static void autoCreateItems(@NotNull final NodeBuilder node, @NotNull final NodeState registry,
        @Nullable final String userId, @NotNull final Calendar now)
    {
        Stream.concat(Stream.ofNullable(node.getName(JCR_PRIMARY_TYPE)),
            StreamSupport.stream(node.getNames(JCR_MIXIN_TYPES).spliterator(), false))
            .map(registry::getChildNode)
            .forEach(type -> {
                autoCreateProperties(node, type, userId, now);
                autoCreateChildren(node, type);
            });
        for (final String child : node.getChildNodeNames()) {
            autoCreateItems(node.getChildNode(child), registry, userId, now);
        }
    }

    /** Oak flattens a type's supertypes into its own definitions, so one type is all there is to read. */
    private static void autoCreateProperties(final NodeBuilder node, final NodeState type, final String userId,
        final Calendar now)
    {
        for (final ChildNodeEntry definitions : type.getChildNode(NAMED_PROPERTY_DEFINITIONS).getChildNodeEntries()) {
            final String name = REP_UUID.equals(definitions.getName()) ? JCR_UUID : definitions.getName();
            final NodeState definition = autoCreated(definitions.getNodeState());
            if (definition == null || TYPE_DEFINITIONS.contains(name) || node.hasProperty(name)) {
                continue;
            }
            final PropertyState property = switch (name) {
                case JCR_UUID -> PropertyStates.createProperty(name, UUID.randomUUID().toString());
                case JCR_CREATED, JCR_LAST_MODIFIED ->
                    PropertyStates.createProperty(name, ISO8601.format(now), Type.DATE);
                case JCR_CREATED_BY, JCR_LAST_MODIFIED_BY ->
                    PropertyStates.createProperty(name, Objects.toString(userId, ""));
                default -> defaultValue(name, definition);
            };
            if (property != null) {
                node.setProperty(property);
            }
        }
    }

    private static PropertyState defaultValue(final String name, final NodeState definition)
    {
        final PropertyState values = definition.getProperty("jcr:defaultValues");
        if (values == null) {
            return null;
        }
        if (definition.getBoolean("jcr:multiple")) {
            return PropertyStates.createProperty(name, values.getValue(values.getType()), values.getType());
        }
        final Type<?> single = values.getType().getBaseType();
        return values.count() == 0 ? null : PropertyStates.createProperty(name, values.getValue(single, 0), single);
    }

    private static void autoCreateChildren(final NodeBuilder node, final NodeState type)
    {
        for (final ChildNodeEntry definitions : type.getChildNode(NAMED_CHILD_NODE_DEFINITIONS)
            .getChildNodeEntries()) {
            final NodeState definition = autoCreated(definitions.getNodeState());
            final String childType = definition == null ? null : definition.getName("jcr:defaultPrimaryType");
            if (childType != null && !node.hasChildNode(definitions.getName())) {
                node.child(definitions.getName()).setProperty(JCR_PRIMARY_TYPE, childType, Type.NAME);
            }
        }
    }

    /**
     * The autocreated one among the definitions of one item name, which Oak keeps one per required type.
     *
     * @param definitions the definitions of one name
     * @return the first autocreated definition, or {@code null} when none is
     */
    private static NodeState autoCreated(final NodeState definitions)
    {
        for (final ChildNodeEntry definition : definitions.getChildNodeEntries()) {
            if (definition.getNodeState().getBoolean("jcr:autoCreated")) {
                return definition.getNodeState();
            }
        }
        return null;
    }
}

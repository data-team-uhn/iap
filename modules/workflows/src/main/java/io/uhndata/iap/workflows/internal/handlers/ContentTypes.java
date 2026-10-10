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
package io.uhndata.iap.workflows.internal.handlers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.nodetype.NodeDefinition;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.NodeTypeManager;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.workflows.models.Activity;

/**
 * The types of content a creating activity offers. The activity lists them as the children of its {@code types}
 * node, each with the {@code nodeType} to create and the {@code label} it is offered under; the node types of the
 * parent decide the rest: a type is offered only where the parent's content type declares it holds children of that
 * type. Definitions that merely tolerate children are not taken as saying what a node holds: those requiring no
 * more than {@code nt:base}, and those JCR's and Sling's own types declare, such as {@code nt:folder}'s, which every
 * {@code sling:Folder}, and so all content, inherits. So one activity can serve several kinds of container, each
 * offering only what it is meant to hold. How content of a type is named is the activity's to say, and a type
 * listed can say otherwise: whether it takes a name of its own at all ({@code named}, true unless false), the
 * {@code namePattern} such a name must match, and the {@code nameHint} that says so in words; likewise the
 * {@code orderProperty} that numbers content of the type by its place among its siblings (see {@link Placement}).
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class ContentTypes
{
    /** The activity's child listing the types. */
    static final String TYPES = "types";

    /** The name of a definition allowing children, or properties, of any name. */
    static final String RESIDUAL = "*";

    /** The type every node is, which a definition accepting anything requires. */
    private static final String CATCH_ALL = "nt:base";

    /** The namespaces of JCR's, Oak's and Sling's own types, which say how content is stored, not what it holds. */
    private static final Set<String> BUILT_IN = Set.of("nt", "mix", "jcr", "rep", "oak", "sling");

    /**
     * One type of content an activity offers to create.
     *
     * @param nodeType the node type to create
     * @param label what it is offered under
     * @param named whether content of this type may be given a name of its own
     * @param namePattern the pattern such a name must match, or {@code null} for any a node can have
     * @param nameHint what such a name may be, in words, or {@code null}
     * @param orderProperty the property numbering content of this type by its place, or {@code null} for none
     * @version $Id$
     * @since 0.1.0
     */
    public record Type(String nodeType, String label, boolean named, String namePattern, String nameHint,
        String orderProperty)
    {
        /**
         * What new content of this type is called when nothing better names it: the type's name without its
         * namespace, starting in lower case, e.g. {@code question} for {@code sch:Question}.
         *
         * @return a node name
         */
        public String defaultName()
        {
            final String local = this.nodeType.substring(this.nodeType.indexOf(':') + 1);
            return Character.toLowerCase(local.charAt(0)) + local.substring(1);
        }
    }

    private ContentTypes()
    {
        // Utility class
    }

    /**
     * The types an activity lists, in order.
     *
     * @param activity a creating activity
     * @return the types, none when it lists none
     */
    public static List<Type> listedBy(final Activity activity)
    {
        final Content types = activity.getChild(TYPES, Content.class);
        if (types == null) {
            return List.of();
        }
        final String pattern = activity.get(ContentNames.NAME_PATTERN, String.class);
        final String hint = activity.get(ContentNames.NAME_HINT, String.class);
        final String order = activity.get(Placement.ORDER_PROPERTY, String.class);
        return types.getChildren(Content.class).stream()
            .filter(type -> type.get("nodeType", String.class) != null)
            .map(type -> {
                final String nodeType = type.get("nodeType", String.class);
                return new Type(nodeType, Objects.requireNonNullElse(type.get("label", String.class), nodeType),
                    !Boolean.FALSE.equals(type.get(ContentNames.NAMED, Boolean.class)),
                    Optional.ofNullable(type.get(ContentNames.NAME_PATTERN, String.class)).orElse(pattern),
                    Optional.ofNullable(type.get(ContentNames.NAME_HINT, String.class)).orElse(hint),
                    Optional.ofNullable(type.get(Placement.ORDER_PROPERTY, String.class)).orElse(order));
            })
            .toList();
    }

    /**
     * The types listed that a parent's types declare it holds, in the listed order.
     *
     * @param listed the types an activity lists
     * @param parent the node new content would be created under
     * @return the types that may be created there
     * @throws RepositoryException when the parent's types cannot be read
     */
    public static List<Type> accepted(final List<Type> listed, final Node parent) throws RepositoryException
    {
        final NodeTypeManager nodeTypes = parent.getSession().getWorkspace().getNodeTypeManager();
        final List<NodeType> parentTypes = typesOf(parent);
        final List<Type> accepted = new ArrayList<>();
        for (final Type type : listed) {
            if (nodeTypes.hasNodeType(type.nodeType()) && holds(parentTypes, nodeTypes.getNodeType(type.nodeType()))) {
                accepted.add(type);
            }
        }
        return accepted;
    }

    /**
     * Whether a node's types declare it holds children of a type.
     *
     * @param parent the node
     * @param child the type of a child
     * @return whether a child definition of its content types names a type the child is, other than
     *     {@code nt:base}
     * @throws RepositoryException when the node's types cannot be read
     */
    static boolean holds(final Node parent, final NodeType child) throws RepositoryException
    {
        return holds(typesOf(parent), child);
    }

    /**
     * Whether some node types let a child of a type go under a name, which is how content written whole is placed
     * (see {@link ContentTree}): as the definitions naming it declare, when any does, or else as they allow a child of
     * any name. Unlike {@link #holds(Node, NodeType)}, which says what a node is meant to hold of any name, this is
     * about one name, so a catch-all accepting anything does allow a name no definition claims.
     *
     * @param parentTypes the types of the node the child goes in
     * @param name the child's name
     * @param child the child's type
     * @return whether the child can go there
     */
    static boolean holdsAt(final List<NodeType> parentTypes, final String name, final NodeType child)
    {
        final List<NodeDefinition> named = parentTypes.stream()
            .flatMap(parent -> Arrays.stream(parent.getChildNodeDefinitions()))
            .filter(definition -> definition.getName().equals(name))
            .toList();
        if (named.isEmpty()) {
            return parentTypes.stream().anyMatch(parent -> parent.canAddChildNode(name, child.getName()));
        }
        return concrete(child) && named.stream().anyMatch(definition -> !definition.isProtected()
            && Arrays.stream(definition.getRequiredPrimaryTypeNames()).allMatch(child::isNodeType));
    }

    /**
     * Whether nodes can be of a type: neither abstract nor a mixin.
     *
     * @param type a node type
     * @return whether it can be a node's primary type
     */
    private static boolean concrete(final NodeType type)
    {
        return !type.isAbstract() && !type.isMixin();
    }

    /**
     * A node's types, the primary one first.
     *
     * @param node a node
     * @return its primary type and its mixins
     * @throws RepositoryException when they cannot be read
     */
    static List<NodeType> typesOf(final Node node) throws RepositoryException
    {
        final List<NodeType> types = new ArrayList<>(List.of(node.getMixinNodeTypes()));
        types.add(0, node.getPrimaryNodeType());
        return types;
    }

    /**
     * Whether some node types declare they hold children of a type.
     *
     * @param parentTypes the parent's types
     * @param child the type of a child
     * @return whether a child definition of their content types names a type the child is, other than
     *     {@code nt:base}
     */
    private static boolean holds(final List<NodeType> parentTypes, final NodeType child)
    {
        if (!concrete(child)) {
            return false;
        }
        return parentTypes.stream()
            .flatMap(parentType -> Arrays.stream(parentType.getChildNodeDefinitions()))
            .filter(definition -> RESIDUAL.equals(definition.getName()) && !definition.isProtected()
                && !BUILT_IN.contains(prefix(definition.getDeclaringNodeType().getName())))
            .flatMap(definition -> Arrays.stream(definition.getRequiredPrimaryTypeNames()))
            .anyMatch(required -> !CATCH_ALL.equals(required) && child.isNodeType(required));
    }

    /**
     * The namespace prefix of a name.
     *
     * @param name a qualified name, such as {@code sch:Question}
     * @return its prefix, empty for a name without one
     */
    private static String prefix(final String name)
    {
        return name.substring(0, Math.max(0, name.indexOf(':')));
    }
}

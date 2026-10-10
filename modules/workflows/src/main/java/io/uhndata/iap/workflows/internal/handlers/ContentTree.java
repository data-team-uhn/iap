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
import java.util.Map;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.ValueFactory;
import javax.jcr.nodetype.ItemDefinition;
import javax.jcr.nodetype.NodeDefinition;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.NodeTypeManager;
import javax.jcr.nodetype.PropertyDefinition;

import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import io.uhndata.iap.workflows.api.InvalidPayloadException;

/**
 * Content to write whole, as a JSON object gives it: its properties as keys, its children as objects named by their
 * keys, and each node's type as {@code jcr:primaryType}, checked against the declarations of those types before any
 * of it is written.
 *
 * @param name its name
 * @param type its node type
 * @param properties its properties
 * @param children its children, in order
 * @version $Id$
 * @since 0.1.0
 */
record ContentTree(String name, String type, List<TreeProperty> properties, List<ContentTree> children)
{
    private static final String PRIMARY_TYPE = "jcr:primaryType";

    /**
     * What a tree is written with.
     *
     * @param types the repository's node types
     * @param values makes the values written
     * @param listed the node types the tree may hold
     * @param prefixes the namespace prefixes names can have
     * @version $Id$
     * @since 0.1.0
     */
    private record Planning(NodeTypeManager types, ValueFactory values, Set<String> listed, Set<String> prefixes)
    {
    }

    /**
     * A tree to write as a child of a node.
     *
     * @param parent the node it goes in
     * @param name the child's name
     * @param json what it is
     * @param listed the node types it may hold
     * @return what to write
     * @throws InvalidPayloadException when the tree, or anything in it, cannot be written as given
     * @throws RepositoryException when the node types cannot be read
     */
    static ContentTree of(final Node parent, final String name, final JsonObject json, final Set<String> listed)
        throws InvalidPayloadException, RepositoryException
    {
        final Planning planning = new Planning(parent.getSession().getWorkspace().getNodeTypeManager(),
            parent.getSession().getValueFactory(), listed, Set.of(parent.getSession().getNamespacePrefixes()));
        return plan(planning, name, name, json, ContentTypes.typesOf(parent));
    }

    /**
     * Writes the tree, in place of what its parent holds under its name, whether it was there before or its parent
     * created it of itself.
     *
     * @param parent the node it goes in
     * @throws RepositoryException when it cannot be written
     */
    void writeInto(final Node parent) throws RepositoryException
    {
        if (parent.hasNode(this.name)) {
            parent.getNode(this.name).remove();
        }
        final Node node = parent.addNode(this.name, this.type);
        for (final TreeProperty property : this.properties) {
            property.writeTo(node);
        }
        for (final ContentTree child : this.children) {
            child.writeInto(node);
        }
    }

    /**
     * Checks a node of the tree, with everything under it, against the declarations of its types.
     *
     * @param planning what the tree is written with
     * @param where the node's place in the tree, to say what is wrong where
     * @param name its name
     * @param json what it is
     * @param parentTypes the types of the node it goes in
     * @return what to write
     * @throws InvalidPayloadException when the node, or anything under it, cannot be written as given
     * @throws RepositoryException when the node types cannot be read
     */
    private static ContentTree plan(final Planning planning, final String where, final String name,
        final JsonObject json, final List<NodeType> parentTypes) throws InvalidPayloadException, RepositoryException
    {
        final NodeType type = typeOf(planning, where, json);
        if (!ContentTypes.holdsAt(parentTypes, name, type)) {
            throw new InvalidPayloadException(where + " cannot be a " + type.getName() + " there");
        }
        checkComplete(where, type, json);
        final List<TreeProperty> properties = new ArrayList<>();
        final List<ContentTree> children = new ArrayList<>();
        for (final Map.Entry<String, JsonValue> entry : json.entrySet()) {
            final String key = entry.getKey();
            final String at = where + "/" + key;
            if (PRIMARY_TYPE.equals(key) || entry.getValue() == JsonValue.NULL) {
                continue;
            }
            if (!legal(key, planning.prefixes())) {
                throw new InvalidPayloadException(at + " is not a name content can take");
            }
            if (entry.getValue() instanceof JsonObject object) {
                children.add(plan(planning, at, key, object, List.of(type)));
            } else {
                properties.add(TreeProperty.of(planning.values(), at, key, entry.getValue(), type));
            }
        }
        return new ContentTree(name, type.getName(), properties, children);
    }

    /**
     * The type a node of the tree says it is, which must be one the activity lists.
     *
     * @param planning what the tree is written with
     * @param where the node's place in the tree
     * @param json what it is
     * @return its type
     * @throws InvalidPayloadException when it says none, or one the tree may not hold
     * @throws RepositoryException when the node types cannot be read
     */
    private static NodeType typeOf(final Planning planning, final String where, final JsonObject json)
        throws InvalidPayloadException, RepositoryException
    {
        if (!(json.get(PRIMARY_TYPE) instanceof JsonString declared)) {
            throw new InvalidPayloadException(where + " needs a " + PRIMARY_TYPE);
        }
        final String type = declared.getString();
        if (!planning.listed().contains(type) || !planning.types().hasNodeType(type)) {
            throw new InvalidPayloadException(where + " cannot be a " + type);
        }
        return planning.types().getNodeType(type);
    }

    /**
     * Refuses a node of the tree that leaves out something its type requires and does not create of itself.
     *
     * @param where the node's place in the tree
     * @param type its type
     * @param json what it is
     * @throws InvalidPayloadException when it leaves out a mandatory child or property
     */
    private static void checkComplete(final String where, final NodeType type, final JsonObject json)
        throws InvalidPayloadException
    {
        for (final NodeDefinition child : type.getChildNodeDefinitions()) {
            if (required(child) && !(json.get(child.getName()) instanceof JsonObject)) {
                throw new InvalidPayloadException(where + " needs " + child.getName());
            }
        }
        for (final PropertyDefinition property : type.getPropertyDefinitions()) {
            final JsonValue value = json.get(property.getName());
            if (required(property) && (value == null || value == JsonValue.NULL || value instanceof JsonObject)) {
                throw new InvalidPayloadException(where + " needs " + property.getName());
            }
        }
    }

    /**
     * Whether a declaration requires what it declares to be given.
     *
     * @param definition a child or property declaration
     * @return whether it is mandatory, named, and not created of itself
     */
    private static boolean required(final ItemDefinition definition)
    {
        return definition.isMandatory() && !definition.isAutoCreated()
            && !ContentTypes.RESIDUAL.equals(definition.getName());
    }

    /**
     * Whether a key is a name an item can have: one local name, maybe after a known namespace prefix.
     *
     * @param key a key of the tree
     * @param prefixes the namespace prefixes names can have
     * @return whether it can name a node or a property
     */
    private static boolean legal(final String key, final Set<String> prefixes)
    {
        final String[] parts = key.split(":", -1);
        return parts.length <= 2 && (parts.length == 1 || prefixes.contains(parts[0]))
            && Arrays.stream(parts).allMatch(part -> !part.isEmpty() && ContentNames.allowed(part, null));
    }
}

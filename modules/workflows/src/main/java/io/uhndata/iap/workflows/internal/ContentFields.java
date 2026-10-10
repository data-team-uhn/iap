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
package io.uhndata.iap.workflows.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.jcr.Node;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.PropertyDefinition;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.workflows.models.Activity;

/**
 * The fields an update activity lets change on a node. The activity lists them, in the order they are edited, as
 * the children of its {@code fields} node, each with how it is presented: a {@code label}, whether it runs over
 * several lines ({@code multiline}), and for a reference the resource type it must point at
 * ({@code referenceType}). The node's own type decides the rest: a field applies only if the type declares it by
 * name, not through a residual definition, and the declaration says whether it is mandatory and whether it holds
 * text or a reference. So one activity may serve several types of content, each keeping to its own fields.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ContentFields
{
    /** The activity's child listing the fields. */
    static final String FIELDS = "fields";

    /**
     * One field an update may change on a node.
     *
     * @param name the property name
     * @param label what it is called where it is edited
     * @param mandatory whether it may not be removed or left blank
     * @param multiline whether its text runs over several lines
     * @param reference whether it holds a reference to another node, given as that node's path
     * @param referenceType for a reference, the resource type the referenced node must have, if any
     * @version $Id$
     * @since 0.1.0
     */
    record Field(String name, String label, boolean mandatory, boolean multiline, boolean reference,
        String referenceType)
    {
    }

    private ContentFields()
    {
        // Utility class
    }

    /**
     * How an activity presents one field it lists.
     *
     * @param name the property name
     * @param label what it is called where it is edited
     * @param multiline whether its text runs over several lines
     * @param referenceType for a reference, the resource type the referenced node must have, if any
     * @version $Id$
     * @since 0.1.0
     */
    record Description(String name, String label, boolean multiline, String referenceType)
    {
    }

    /**
     * The fields an activity lists, in order, as it presents them.
     *
     * @param activity an update activity
     * @return the descriptions, none when it lists none
     */
    static List<Description> describedBy(final Activity activity)
    {
        final Content fields = activity.getChild(FIELDS, Content.class);
        if (fields == null) {
            return List.of();
        }
        return fields.getChildren(Content.class).stream()
            .map(field -> new Description(field.getName(),
                Objects.requireNonNullElse(field.get("label", String.class), field.getName()),
                Boolean.TRUE.equals(field.get("multiline", Boolean.class)), field.get("referenceType", String.class)))
            .toList();
    }

    /**
     * The fields listed that a node's type declares, in the listed order.
     *
     * @param described the fields an activity lists
     * @param node the node an update would change
     * @return the fields that apply to it
     * @throws RepositoryException when the node's type cannot be read
     */
    static List<Field> editable(final List<Description> described, final Node node) throws RepositoryException
    {
        final List<Field> editable = new ArrayList<>();
        for (final Description field : described) {
            final Optional<PropertyDefinition> declared = declaration(node, field.name());
            if (declared.isPresent()) {
                final int type = declared.get().getRequiredType();
                editable.add(new Field(field.name(), field.label(), declared.get().isMandatory(), field.multiline(),
                    type == PropertyType.REFERENCE || type == PropertyType.WEAKREFERENCE, field.referenceType()));
            }
        }
        return editable;
    }

    /**
     * How a node's type declares a property by name, if it does.
     *
     * @param node the node
     * @param name the property name
     * @return the declaration, empty when the property is only allowed by a residual definition or not at all
     * @throws RepositoryException when the node's type cannot be read
     */
    private static Optional<PropertyDefinition> declaration(final Node node, final String name)
        throws RepositoryException
    {
        final List<NodeType> types = new ArrayList<>(List.of(node.getMixinNodeTypes()));
        types.add(0, node.getPrimaryNodeType());
        for (final NodeType type : types) {
            for (final PropertyDefinition definition : type.getPropertyDefinitions()) {
                if (definition.getName().equals(name) && !definition.isMultiple() && !definition.isProtected()) {
                    return Optional.of(definition);
                }
            }
        }
        return Optional.empty();
    }
}

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
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.jcr.Node;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.PropertyDefinition;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.workflows.models.Activity;

/**
 * The fields an update activity lets change on a node. The activity lists them, in the order they are edited, as
 * the children of its {@code fields} node, each with how it is presented: a {@code label}, a short {@code help}
 * text, whether it runs over several lines ({@code multiline}), for a reference the resource type it must point at
 * ({@code referenceType}), the values it may take ({@code choices}), the value of another property it depends
 * on ({@code appliesWhen}), and whether no two contents of the same type side by side may hold the same value in it
 * ({@code unique}). The node's own type decides the rest: a field applies only if the type declares it by
 * name, not through a residual definition, and the declaration says whether it is mandatory, whether it holds one
 * value or several, and of which kind. So one activity may serve several types of content, each keeping to its own
 * fields.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class ContentFields
{
    /** The activity's child listing the fields. */
    static final String FIELDS = "fields";

    /**
     * What a field holds, as told by the type of its declaration.
     *
     * @version $Id$
     * @since 0.1.0
     */
    public enum Kind
    {
        /** Text. */
        TEXT("text"),
        /** A whole number. */
        LONG("long"),
        /** A number. */
        DOUBLE("double"),
        /** True or false. */
        BOOLEAN("boolean"),
        /** Another node, given as its path. */
        REFERENCE("reference");

        private final String name;

        Kind(final String name)
        {
            this.name = name;
        }

        /**
         * What the kind is called where fields are described.
         *
         * @return the name
         */
        public String getName()
        {
            return this.name;
        }

        /**
         * The kind of a property type.
         *
         * @param type a {@link PropertyType}
         * @return the kind, empty for a type no field can be edited as
         */
        static Optional<Kind> of(final int type)
        {
            return Optional.ofNullable(switch (type) {
                case PropertyType.STRING -> TEXT;
                case PropertyType.LONG -> LONG;
                case PropertyType.DOUBLE -> DOUBLE;
                case PropertyType.BOOLEAN -> BOOLEAN;
                case PropertyType.REFERENCE, PropertyType.WEAKREFERENCE -> REFERENCE;
                default -> null;
            });
        }
    }

    /**
     * One value a field may take.
     *
     * @param value the value, the choice's name unless it says otherwise
     * @param label what it is called where it is picked, the value itself unless it says otherwise
     * @version $Id$
     * @since 0.1.0
     */
    public record Choice(String value, String label)
    {
    }

    /**
     * When a field applies: while another property of the same node holds one of some values.
     *
     * @param property the property it depends on; without one, the field applies nowhere
     * @param values the values of that property it applies with
     * @version $Id$
     * @since 0.1.0
     */
    public record Applicability(String property, List<String> values)
    {
        /**
         * Keeps its own copy of the values, so that nothing holding the list given changes when a field applies.
         *
         * @param property the property it depends on; without one, the field applies nowhere
         * @param values the values of that property it applies with
         */
        public Applicability
        {
            values = List.copyOf(values);
        }

        @Override
        public List<String> values()
        {
            // The list is already immutable. copyOf returns the same instance, and satisfies the static analysis.
            return List.copyOf(this.values);
        }
    }

    /**
     * Where a reference may point.
     *
     * @param type the resource type the referenced node must have, if any
     * @param root the path the referenced node must be under, if any
     * @version $Id$
     * @since 0.1.0
     */
    public record Target(String type, String root)
    {
    }

    /**
     * How an activity presents one field it lists.
     *
     * @param name the property name
     * @param label what it is called where it is edited
     * @param help a short explanation shown where it is edited, if any
     * @param multiline whether its text runs over several lines
     * @param target for a reference, where it may point
     * @param choices the values it may take, or none when it may take any
     * @param appliesWhen when it applies, or {@code null} when it always does
     * @param unique whether no sibling of the same type may hold the same value in it
     * @version $Id$
     * @since 0.1.0
     */
    public record Description(String name, String label, String help, boolean multiline, Target target,
        List<Choice> choices, Applicability appliesWhen, boolean unique)
    {
        /**
         * Keeps its own copy of the choices, so that nothing holding the list given changes what a field may take.
         *
         * @param name the property name
         * @param label what it is called where it is edited
         * @param help a short explanation shown where it is edited, if any
         * @param multiline whether its text runs over several lines
         * @param target for a reference, where it may point
         * @param choices the values it may take, or none when it may take any
         * @param appliesWhen when it applies, or {@code null} when it always does
         * @param unique whether no sibling of the same type may hold the same value in it
         */
        public Description
        {
            choices = List.copyOf(choices);
        }

        @Override
        public List<Choice> choices()
        {
            // The list is already immutable. copyOf returns the same instance, and satisfies the static analysis.
            return List.copyOf(this.choices);
        }
    }

    /**
     * One field an update may change on a node.
     *
     * @param description how the activity presents it
     * @param kind what it holds
     * @param weak for a reference, whether it is a weak one
     * @param multiple whether it holds several values
     * @param mandatory whether it may not be removed or left blank
     * @param defaults the values new content starts with, none when it starts without
     * @version $Id$
     * @since 0.1.0
     */
    public record Field(Description description, Kind kind, boolean weak, boolean multiple, boolean mandatory,
        List<Value> defaults)
    {
        /**
         * Keeps its own copy of the defaults, so that nothing holding the list given changes what new content gets.
         *
         * @param description how the activity presents it
         * @param kind what it holds
         * @param weak for a reference, whether it is a weak one
         * @param multiple whether it holds several values
         * @param mandatory whether it may not be removed or left blank
         * @param defaults the values new content starts with, none when it starts without
         */
        public Field
        {
            defaults = List.copyOf(defaults);
        }

        @Override
        public List<Value> defaults()
        {
            // The list is already immutable. copyOf returns the same instance, and satisfies the static analysis.
            return List.copyOf(this.defaults);
        }

        /**
         * The property name.
         *
         * @return the name
         */
        public String name()
        {
            return this.description.name();
        }
    }

    private ContentFields()
    {
        // Utility class
    }

    /**
     * The fields an activity lists, in order, as it presents them.
     *
     * @param activity an update activity
     * @return the descriptions, none when it lists none
     */
    public static List<Description> describedBy(final Activity activity)
    {
        final Content fields = activity.getChild(FIELDS, Content.class);
        if (fields == null) {
            return List.of();
        }
        return fields.getChildren(Content.class).stream()
            .map(field -> new Description(field.getName(),
                Objects.requireNonNullElse(field.get("label", String.class), field.getName()),
                field.get("help", String.class), Boolean.TRUE.equals(field.get("multiline", Boolean.class)),
                new Target(field.get("referenceType", String.class), field.get("referenceRoot", String.class)),
                choices(field),
                applicability(field), Boolean.TRUE.equals(field.get("unique", Boolean.class))))
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
    public static List<Field> editable(final List<Description> described, final Node node) throws RepositoryException
    {
        final List<NodeType> types = new ArrayList<>(List.of(node.getMixinNodeTypes()));
        types.add(0, node.getPrimaryNodeType());
        return editable(described, types);
    }

    /**
     * The fields listed that some node types declare, in the listed order.
     *
     * @param described the fields an activity lists
     * @param types the types of a node an update would change, the primary one first
     * @return the fields that apply to such a node
     */
    public static List<Field> editable(final List<Description> described, final List<NodeType> types)
    {
        final List<Field> editable = new ArrayList<>();
        for (final Description field : described) {
            final Optional<PropertyDefinition> declared = declaration(types, field.name());
            final Optional<Kind> kind = declared.flatMap(definition -> Kind.of(definition.getRequiredType()));
            if (kind.isPresent()) {
                final PropertyDefinition definition = declared.get();
                editable.add(new Field(field, kind.get(), definition.getRequiredType() == PropertyType.WEAKREFERENCE,
                    definition.isMultiple(), definition.isMandatory(), defaults(definition)));
            }
        }
        return editable;
    }

    /**
     * Whether a field applies, given the values of the property it depends on.
     *
     * @param field the field
     * @param values the values that property holds, as text
     * @return whether it applies
     */
    static boolean applies(final Field field, final List<String> values)
    {
        final Applicability applicability = field.description().appliesWhen();
        return applicability == null || applicability.property() != null
            && values.stream().anyMatch(applicability.values()::contains);
    }

    /**
     * The values a field description allows, from its {@code choices} children.
     *
     * @param field a field description
     * @return the choices, in order, none when it allows any value
     */
    private static List<Choice> choices(final Content field)
    {
        final Content choices = field.getChild("choices", Content.class);
        if (choices == null) {
            return List.of();
        }
        return choices.getChildren(Content.class).stream()
            .map(choice -> {
                final String value = Objects.requireNonNullElse(choice.get("value", String.class), choice.getName());
                return new Choice(value, Objects.requireNonNullElse(choice.get("label", String.class), value));
            })
            .toList();
    }

    /**
     * When a field description says it applies, from its {@code appliesWhen} child.
     *
     * @param field a field description
     * @return when it applies, or {@code null} when it always does
     */
    private static Applicability applicability(final Content field)
    {
        final Content applicability = field.getChild("appliesWhen", Content.class);
        if (applicability == null) {
            return null;
        }
        return new Applicability(applicability.get("property", String.class),
            List.of(Objects.requireNonNullElse(applicability.get("values", String[].class), new String[0])));
    }

    /**
     * The values a declaration gives new content.
     *
     * @param definition a property declaration
     * @return its default values when it is autocreated, else none
     */
    private static List<Value> defaults(final PropertyDefinition definition)
    {
        final Value[] defaults = definition.isAutoCreated() ? definition.getDefaultValues() : null;
        return defaults == null ? List.of() : List.of(defaults);
    }

    /**
     * How some node types declare a property by name, if one does.
     *
     * @param types the node types, in the order they are consulted
     * @param name the property name
     * @return the declaration, empty when the property is only allowed by a residual definition or not at all
     */
    private static Optional<PropertyDefinition> declaration(final List<NodeType> types, final String name)
    {
        for (final NodeType type : types) {
            for (final PropertyDefinition definition : type.getPropertyDefinitions()) {
                if (definition.getName().equals(name) && !definition.isProtected()) {
                    return Optional.of(definition);
                }
            }
        }
        return Optional.empty();
    }
}

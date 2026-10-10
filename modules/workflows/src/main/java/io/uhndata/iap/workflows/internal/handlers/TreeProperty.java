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
import java.util.stream.Collectors;

import javax.jcr.Node;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.PropertyDefinition;

import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import io.uhndata.iap.workflows.api.InvalidPayloadException;

/**
 * A property of a {@link ContentTree}: its values stored as JSON gives them, text, whole numbers, decimals, true or
 * false, or a list of one kind of those, unless the declaration naming it requires another type.
 *
 * @param name its name
 * @param values its values
 * @param multiple whether it holds several, rather than the one value given
 * @version $Id$
 * @since 0.1.0
 */
record TreeProperty(String name, Value[] values, boolean multiple)
{
    /**
     * A property of the tree, as its node's type lets it be written.
     *
     * @param factory makes the values
     * @param at the property's place in the tree
     * @param name its name
     * @param json its value, or its values as an array
     * @param type the type of its node
     * @return what to write
     * @throws InvalidPayloadException when the type does not let it hold what is given
     */
    static TreeProperty of(final ValueFactory factory, final String at, final String name,
        final JsonValue json, final NodeType type) throws InvalidPayloadException
    {
        final boolean multiple = json instanceof JsonArray;
        final Value[] given = json instanceof JsonArray array ? values(factory, at, array)
            : new Value[] { value(factory, at, json) };
        final TreeProperty written =
            new TreeProperty(name, converted(factory, at, given, declaredType(type, name)), multiple);
        if (multiple ? !type.canSetProperty(name, written.values()) : !type.canSetProperty(name, written.values()[0])) {
            throw new InvalidPayloadException(at + " cannot hold " + json);
        }
        return written;
    }

    /**
     * Writes the property.
     *
     * @param node the node it is on
     * @throws RepositoryException when it cannot be written
     */
    void writeTo(final Node node) throws RepositoryException
    {
        if (this.multiple) {
            node.setProperty(this.name, this.values);
        } else {
            node.setProperty(this.name, this.values[0]);
        }
    }

    /**
     * The type a property's own declaration requires, if a declaration names it.
     *
     * @param type the type of its node
     * @param name its name
     * @return the type required, {@code PropertyType.UNDEFINED} when none is
     */
    private static int declaredType(final NodeType type, final String name)
    {
        return Arrays.stream(type.getPropertyDefinitions())
            .filter(definition -> definition.getName().equals(name))
            .mapToInt(PropertyDefinition::getRequiredType)
            .findFirst()
            .orElse(PropertyType.UNDEFINED);
    }

    /**
     * Values as the type a declaration requires, e.g. a date given as text.
     *
     * @param factory makes the values
     * @param at the property's place in the tree
     * @param values the values given
     * @param declared the type required, or {@code PropertyType.UNDEFINED}
     * @return the values, of that type
     * @throws InvalidPayloadException when one cannot be read as that type
     */
    private static Value[] converted(final ValueFactory factory, final String at, final Value[] values,
        final int declared) throws InvalidPayloadException
    {
        final Value[] converted = values.clone();
        for (int i = 0; i < converted.length; ++i) {
            if (declared != PropertyType.UNDEFINED && converted[i].getType() != declared) {
                try {
                    converted[i] = factory.createValue(converted[i].getString(), declared);
                } catch (final RepositoryException e) {
                    throw new InvalidPayloadException(at + " cannot hold " + converted[i], e);
                }
            }
        }
        return converted;
    }

    /**
     * The values of an array, all of one kind: numbers are all whole, or all taken as decimals.
     *
     * @param factory makes the values
     * @param at the property's place in the tree
     * @param array the values
     * @return them, stored typed
     * @throws InvalidPayloadException when one is not a plain value, or they are of different kinds
     */
    private static Value[] values(final ValueFactory factory, final String at, final JsonArray array)
        throws InvalidPayloadException
    {
        final boolean decimals = array.stream().anyMatch(value -> value instanceof JsonNumber number
            && !number.isIntegral());
        final List<Value> values = new ArrayList<>();
        for (final JsonValue element : array) {
            values.add(decimals && element instanceof JsonNumber number ? factory.createValue(number.doubleValue())
                : value(factory, at, element));
        }
        if (values.stream().map(Value::getType).collect(Collectors.toSet()).size() > 1) {
            throw new InvalidPayloadException(at + " mixes values of different kinds");
        }
        return values.toArray(Value[]::new);
    }

    /**
     * One plain value, stored as the kind JSON says it is: text, a whole number, a decimal, or true or false.
     *
     * @param factory makes the value
     * @param at the property's place in the tree
     * @param json the value
     * @return it, stored typed
     * @throws InvalidPayloadException when it is not a plain value, or a whole number too large to store
     */
    private static Value value(final ValueFactory factory, final String at, final JsonValue json)
        throws InvalidPayloadException
    {
        return switch (json.getValueType()) {
            case STRING -> factory.createValue(((JsonString) json).getString());
            case NUMBER -> number(factory, at, (JsonNumber) json);
            case TRUE -> factory.createValue(true);
            case FALSE -> factory.createValue(false);
            default -> throw new InvalidPayloadException(at + " can only hold plain values");
        };
    }

    /**
     * A number, stored as a whole number when it is one.
     *
     * @param factory makes the value
     * @param at the property's place in the tree
     * @param number the number
     * @return it, stored typed
     * @throws InvalidPayloadException when it is a whole number too large to store
     */
    private static Value number(final ValueFactory factory, final String at, final JsonNumber number)
        throws InvalidPayloadException
    {
        if (!number.isIntegral()) {
            return factory.createValue(number.doubleValue());
        }
        try {
            return factory.createValue(number.longValueExact());
        } catch (final ArithmeticException e) {
            throw new InvalidPayloadException(at + " is too large a number", e);
        }
    }
}

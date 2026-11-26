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
package io.uhndata.iap.workflows;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.uhndata.iap.workflows.FlowNodeTypeInfo.XmlLocation;
import io.uhndata.iap.workflows.internal.types.ParameterType;
import io.uhndata.iap.workflows.models.Parameter;

/**
 * One value a kind of node takes, as the vocabulary declares it. A value parameter is stored as a property of the
 * node holding it. A structure is stored as a node of its own, holding the values of its fields.
 *
 * @version $Id$
 * @since 0.1.0
 */
sealed interface ParameterInfo permits ParameterInfo.ValueParameter, ParameterInfo.StructureParameter
{
    /**
     * The parameter's name, which is its vocabulary node's name. It names the JCR item the value is stored as, and
     * the XML attribute or element it is written as.
     *
     * @return a name such as {@code toState}
     */
    String name();

    /**
     * How many values must be given.
     *
     * @return one or more for a required parameter, {@code 0} for an optional one
     */
    long minValues();

    /**
     * How many values may be given.
     *
     * @return the maximum, {@code 0} meaning unbounded
     */
    long maxValues();

    /**
     * Where in the diagram the value is written.
     *
     * @return the kind of markup holding the value
     */
    XmlLocation location();

    /**
     * Why the node holding this parameter would not store it as declared. The vocabulary entry is what is wrong,
     * so the value is never written.
     *
     * @return a problem phrase, or {@code null} when the value can be stored
     */
    String refusal();

    /**
     * Whether the value is stored as a list. A maximum of exactly one value is the only thing that makes it
     * single: unbounded is a list, and so is "up to three".
     *
     * @return {@code true} if the stored property is multi-valued, or a structure is stored as one node per element
     */
    default boolean isMultiValued()
    {
        return maxValues() != 1;
    }

    /**
     * Whether a diagram has to give this parameter a value.
     *
     * @return {@code true} if at least one value is expected
     */
    default boolean isRequired()
    {
        return minValues() >= 1;
    }

    /**
     * A parameter stored as a property of the node holding it.
     *
     * @param name the parameter's name
     * @param type what a value is typed by, made from the declaration's {@code dataType} and its settings
     * @param minValues how many values must be given
     * @param maxValues how many values may be given, {@code 0} meaning unbounded
     * @param location where in the diagram the value is written
     * @param path the BPMN child elements leading from the flow node's element to the one holding the value, as
     *            the local names of the {@link Parameter#getXmlPath() xmlPath} steps, empty for none
     * @param options the values this parameter offers, empty when it takes anything its type accepts
     * @param refusal why the node holding it would not store it, or {@code null} when it would
     * @since 0.1.0
     */
    record ValueParameter(String name, ParameterType type, long minValues, long maxValues, XmlLocation location,
        List<String> path, Set<String> options, String refusal) implements ParameterInfo
    {
        /** Keeps copies, in the order given, which nothing can change. */
        public ValueParameter
        {
            path = List.copyOf(path);
            options = Collections.unmodifiableSet(new LinkedHashSet<>(options));
        }
    }

    /**
     * A parameter stored as a node holding parameters of its own. A single structure is one child node of the
     * parameter's name. A repeated one is a container of that name holding one node per element.
     *
     * @param name the parameter's name
     * @param minValues how many elements must be given
     * @param maxValues how many elements may be given, {@code 0} meaning unbounded
     * @param location where in the diagram the elements are written, which only an extension element can hold
     * @param keyParameter for a repeated structure, the field whose value names each element's node, or
     *            {@code null} when the declaration names none
     * @param nodeType the node type each element is stored as
     * @param fields the parameters each element holds, in the order they are declared
     * @param refusal why the node holding it would not store it, or {@code null} when it would
     * @since 0.1.0
     */
    record StructureParameter(String name, long minValues, long maxValues, XmlLocation location,
        String keyParameter, String nodeType, List<ParameterInfo> fields, String refusal) implements ParameterInfo
    {
        /** Keeps a copy of the fields, which nothing can change. */
        public StructureParameter
        {
            fields = List.copyOf(fields);
        }

        /**
         * Whether the declaration names a key that can name an element's node: a field holding a single value.
         *
         * @return {@code true} if the key parameter names a single-valued field that is not a structure
         */
        boolean hasSingleValuedKey()
        {
            return this.fields.stream().anyMatch(field -> field.name().equals(this.keyParameter)
                && field instanceof ValueParameter && !field.isMultiValued());
        }
    }
}

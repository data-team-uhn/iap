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

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry;
import org.apache.jackrabbit.oak.spi.state.NodeState;

import io.uhndata.iap.utils.NodeTypeUtils;
import io.uhndata.iap.workflows.models.Parameter;

/**
 * A snapshot of one {@code wf:FlowNodeType} vocabulary entry: everything needed to match a BPMN element against it
 * and to write the node it becomes. It is read once per parse, so the rest of the parser never touches
 * {@link NodeState} again.
 *
 * @param name the entry's node name, which breaks the tie between two entries of equal priority
 * @param identifier the entry's {@code jcr:uuid}, which the stored node points back at
 * @param jcrNodeType the node type a matching element is stored as
 * @param xmlChildElement the local name of a BPMN child element a matching element must carry, or {@code null}
 * @param xmlExtensionElement the local name of the IAP extension element a matching element must carry, and the
 *            element every parameter located in an extension element is read from, or {@code null}
 * @param jcrProperties the fixed properties to set on the stored node, empty when the entry declares none, and
 *            {@code null} when what it declares is not a JSON object
 * @param priority how specific this entry is, the highest matching one winning
 * @param parameters the values a node of this type carries beyond the ones every flow node has
 * @version $Id$
 * @since 0.1.0
 */
record FlowNodeTypeInfo(String name, String identifier, String jcrNodeType, String xmlChildElement,
    String xmlExtensionElement, JsonObject jcrProperties, long priority, Parameters parameters)
{
    private static final String PARAMETER_NODETYPE = "wf:Parameter";

    private static final String JCR_PRIMARY_TYPE = "jcr:primaryType";

    private static final String SLING_RESOURCE_TYPE = "sling:resourceType";

    private static final String PARAMETER_OPTION_NODETYPE = "wf:ParameterOption";

    private static final String JCR_UUID = "jcr:uuid";

    private static final String JCR_NODE_TYPE_PROPERTY = "jcrNodeType";

    private static final String JCR_PROPERTIES_PROPERTY = "jcrProperties";

    private static final String XML_CHILD_ELEMENT_PROPERTY = "xmlChildElement";

    private static final String XML_EXTENSION_ELEMENT_PROPERTY = "xmlExtensionElement";

    private static final String PRIORITY_PROPERTY = "priority";

    private static final String DATA_TYPE_PROPERTY = "dataType";

    private static final String MIN_VALUES_PROPERTY = "minValues";

    private static final String MAX_VALUES_PROPERTY = "maxValues";

    private static final String XML_LOCATION_PROPERTY = "xmlLocation";

    private static final String XML_PATH_PROPERTY = "xmlPath";

    private static final String VALUE_PROPERTY = "value";

    /**
     * What an entry declares about the values a node of its kind takes.
     *
     * @param declared the parameters, in the order they are declared
     * @param unread the names of the children that say they are parameters by their resource type alone, which
     *            this parser cannot read
     * @since 0.1.0
     */
    record Parameters(List<ParameterInfo> declared, List<String> unread)
    {
    }

    /**
     * Where in a diagram a parameter's value is written.
     *
     * @since 0.1.0
     */
    enum XmlLocation
    {
        /** Inside the entry's extension element: an attribute for one value, a child per value for a list. */
        EXTENSION_ELEMENT(Parameter.LOCATION_EXTENSION_ELEMENT),
        /** An IAP-namespaced attribute of the flow node's own element. */
        ATTRIBUTE(Parameter.LOCATION_ATTRIBUTE),
        /** One of BPMN's own attributes, unprefixed. */
        BPMN_ATTRIBUTE(Parameter.LOCATION_BPMN_ATTRIBUTE),
        /** The text of one of BPMN's own child elements, one element per value. */
        BPMN_ELEMENT(Parameter.LOCATION_BPMN_ELEMENT);

        private final String value;

        XmlLocation(final String value)
        {
            this.value = value;
        }

        /**
         * The location a vocabulary names. Only a parameter stored as a node of another type can name none or
         * another, and it is read at the node type's default.
         *
         * @param value the stored {@code xmlLocation}, may be {@code null}
         * @return the location it names, {@link #EXTENSION_ELEMENT} when it names none
         */
        static XmlLocation of(final String value)
        {
            return Arrays.stream(values()).filter(location -> location.value.equals(value)).findFirst()
                .orElse(EXTENSION_ELEMENT);
        }

        /**
         * Whether a value in this location is BPMN's own markup rather than IAP's.
         *
         * @return {@code true} for a BPMN attribute or element
         */
        boolean isBpmn()
        {
            return this == BPMN_ATTRIBUTE || this == BPMN_ELEMENT;
        }
    }

    /**
     * The location and path of a parameter's value.
     *
     * @param location the kind of markup holding it
     * @param path for a value BPMN holds, the local names of the BPMN child elements leading from the flow node's
     *            element to the one holding it, empty when the flow node's element holds it directly
     * @since 0.1.0
     */
    record Placement(XmlLocation location, List<String> path)
    {
    }

    /**
     * One value a kind of node takes: what it is called, what may be put in it, and where in the diagram it is
     * written. The name is the JCR property it is stored in as well as the XML attribute or element it is read
     * from, so that the two cannot disagree.
     *
     * @param name the parameter's name, which is its vocabulary node's name
     * @param dataType the name of the parameter type a value is typed by
     * @param minValues how many values must be given, one or more meaning the parameter is required
     * @param maxValues how many values may be given, {@code 0} meaning unbounded and anything but {@code 1}
     *            meaning the stored property is multi-valued
     * @param placement where in the diagram the value is written
     * @param options the values this parameter offers, empty when it takes anything its type accepts
     * @param declaration the {@code wf:Parameter} node, which a parameter type reads its own settings from
     * @since 0.1.0
     */
    record ParameterInfo(String name, String dataType, long minValues, long maxValues, Placement placement,
        Set<String> options, NodeState declaration)
    {
        /**
         * Where in the diagram the value is written.
         *
         * @return the parameter's location
         */
        XmlLocation location()
        {
            return this.placement.location();
        }

        /**
         * For a value BPMN holds, the local names of the BPMN child elements leading from the flow node's element
         * to the one holding it.
         *
         * @return the path, empty when the flow node's element holds the value directly
         */
        List<String> path()
        {
            return this.placement.path();
        }

        /**
         * Whether the property this parameter is stored in holds a list. A maximum of exactly one value is the only
         * thing that makes a property single-valued: unbounded is a list, and so is "up to three".
         *
         * @return {@code true} if the stored property is multi-valued
         */
        boolean isMultiValued()
        {
            return this.maxValues != 1;
        }

        /**
         * Whether a diagram has to give this parameter a value.
         *
         * @return {@code true} if at least one value is expected
         */
        boolean isRequired()
        {
            return this.minValues >= 1;
        }
    }

    /**
     * Reads one vocabulary entry.
     *
     * @param name the entry's node name
     * @param node the {@code wf:FlowNodeType} node to read
     * @param nodeTypesRoot the {@code /jcr:system/jcr:nodeTypes} node state, which recognizes a parameter or an
     *            option by its node type, subtypes included
     * @return the entry as the parser sees it
     */
    static FlowNodeTypeInfo from(final String name, final NodeState node, final NodeState nodeTypesRoot)
    {
        final String childElement = node.getString(XML_CHILD_ELEMENT_PROPERTY);
        final String extensionElement = node.getString(XML_EXTENSION_ELEMENT_PROPERTY);
        return new FlowNodeTypeInfo(name, node.getString(JCR_UUID), node.getString(JCR_NODE_TYPE_PROPERTY),
            childElement == null ? null : WorkflowDefinitionUtils.localName(childElement),
            extensionElement == null ? null : WorkflowDefinitionUtils.localName(extensionElement),
            jcrProperties(node.getString(JCR_PROPERTIES_PROPERTY)), node.getLong(PRIORITY_PROPERTY),
            parameters(node, nodeTypesRoot));
    }

    /**
     * The parameters declared as children of an entry, in the order they are declared. Anything else a vocabulary
     * entry may hold — the residual child definitions let it hold anything — is walked past.
     */
    private static Parameters parameters(final NodeState entry, final NodeState nodeTypesRoot)
    {
        final List<ParameterInfo> declared = new ArrayList<>();
        final List<String> unread = new ArrayList<>();
        for (final ChildNodeEntry child : entry.getChildNodeEntries()) {
            final NodeState state = child.getNodeState();
            if (isA(state, PARAMETER_NODETYPE, nodeTypesRoot)) {
                declared.add(parameter(child.getName(), state, nodeTypesRoot));
            } else if (Parameter.RESOURCE_TYPE.equals(state.getString(SLING_RESOURCE_TYPE))) {
                unread.add(child.getName());
            }
        }
        return new Parameters(declared, unread);
    }

    private static JsonObject jcrProperties(final String raw)
    {
        if (StringUtils.isBlank(raw)) {
            return JsonValue.EMPTY_JSON_OBJECT;
        }
        try (JsonReader reader = Json.createReader(new StringReader(raw))) {
            return reader.readObject();
        } catch (final RuntimeException e) {
            // Reported for each element the entry matches. A malformed entry must not take the parse down with it.
            return null;
        }
    }

    private static boolean isA(final NodeState node, final String type, final NodeState nodeTypesRoot)
    {
        return NodeTypeUtils.isOrExtends(node.getName(JCR_PRIMARY_TYPE), type, nodeTypesRoot);
    }

    private static ParameterInfo parameter(final String name, final NodeState node, final NodeState nodeTypesRoot)
    {
        final String dataType = node.getString(DATA_TYPE_PROPERTY);
        // Autocreated in the node type, so absent only for an entry stored as a node of another type: the maximum
        // has to be told from an absent one, since 0 there means unbounded rather than "one".
        final long maxValues = node.hasProperty(MAX_VALUES_PROPERTY) ? node.getLong(MAX_VALUES_PROPERTY) : 1;
        return new ParameterInfo(name, dataType == null ? Parameter.TYPE_TEXT : dataType,
            node.getLong(MIN_VALUES_PROPERTY), maxValues,
            new Placement(XmlLocation.of(node.getString(XML_LOCATION_PROPERTY)),
                path(node.getString(XML_PATH_PROPERTY))),
            options(node, nodeTypesRoot), node);
    }

    /** The steps of an {@code xmlPath}, each reduced to its local name, since a prefix is the document's choice. */
    private static List<String> path(final String raw)
    {
        return raw == null ? List.of()
            : Arrays.stream(raw.split("/")).filter(StringUtils::isNotBlank)
                .map(step -> WorkflowDefinitionUtils.localName(step.trim())).toList();
    }

    /**
     * The values a parameter offers, in the order they are declared. A set: the parser only ever asks whether a
     * value is one of them, and an option repeated in the vocabulary is still one offer.
     */
    private static Set<String> options(final NodeState node, final NodeState nodeTypesRoot)
    {
        final Set<String> result = new LinkedHashSet<>();
        for (final ChildNodeEntry child : node.getChildNodeEntries()) {
            if (isA(child.getNodeState(), PARAMETER_OPTION_NODETYPE, nodeTypesRoot)) {
                final String value = child.getNodeState().getString(VALUE_PROPERTY);
                if (value != null) {
                    result.add(value);
                }
            }
        }
        return result;
    }
}

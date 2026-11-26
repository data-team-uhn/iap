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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.oak.spi.state.NodeState;

import io.uhndata.iap.utils.NodeTypeUtils;
import io.uhndata.iap.workflows.ParameterInfo.StructureParameter;
import io.uhndata.iap.workflows.ParameterInfo.ValueParameter;
import io.uhndata.iap.workflows.internal.types.ParameterType;
import io.uhndata.iap.workflows.models.Parameter;

/**
 * One {@code wf:FlowNodeType} vocabulary entry: what a BPMN element must look like to match it, and the node the
 * element is stored as.
 *
 * @param name the entry's node name, which breaks the tie between two entries of equal priority
 * @param identifier the entry's {@code jcr:uuid}, which the stored node points back at
 * @param jcrNodeType the node type a matching element is stored as
 * @param xmlChildElement the local name of a BPMN child element a matching element must carry, or {@code null} if
 *            the entry requires none
 * @param xmlExtensionElement the local name of the IAP extension element a matching element must carry, and the
 *            element every parameter located in an extension element is read from, or {@code null} if the entry
 *            names none
 * @param jcrProperties the fixed properties to set on the stored node, as the JSON object text the entry declares,
 *            or {@code null} when it declares none
 * @param priority how specific this entry is; the highest matching entry wins
 * @param parameters the values a node of this type carries beyond the ones every flow node has
 * @version $Id$
 * @since 0.1.0
 */
record FlowNodeTypeInfo(String name, String identifier, String jcrNodeType, String xmlChildElement,
    String xmlExtensionElement, String jcrProperties, long priority, Parameters parameters)
{
    private static final String SLING_RESOURCE_TYPE_PROPERTY = "sling:resourceType";

    private static final String JCR_UUID_PROPERTY = "jcr:uuid";

    private static final String JCR_NODE_TYPE_PROPERTY = "jcrNodeType";

    private static final String JCR_PROPERTIES_PROPERTY = "jcrProperties";

    private static final String XML_CHILD_ELEMENT_PROPERTY = "xmlChildElement";

    private static final String XML_EXTENSION_ELEMENT_PROPERTY = "xmlExtensionElement";

    private static final String PRIORITY_PROPERTY = "priority";

    /**
     * What an entry declares about the values a node of its kind takes.
     *
     * @param declared the parameters, in the order they are declared
     * @param unread the paths, relative to the entry, of nodes that say they are parameters by their resource type
     *            alone, which this parser cannot read
     * @param misplaced the paths, relative to the entry, of parameters declared directly under another parameter
     *            rather than under its {@link Parameter#FIELDS_CHILD} node, which this parser does not read
     * @since 0.1.0
     */
    record Parameters(List<ParameterInfo> declared, List<String> unread, List<String> misplaced)
    {
    }

    /**
     * Where in a diagram a parameter's value is written.
     *
     * @since 0.1.0
     */
    enum XmlLocation
    {
        /** Inside the entry's extension element. */
        EXTENSION_ELEMENT(Parameter.EXTENSION_ELEMENT_LOCATION),
        /** An attribute of the element itself. */
        ATTRIBUTE(Parameter.ATTRIBUTE_LOCATION),
        /** One of BPMN's own attributes, unprefixed. */
        BPMN_ATTRIBUTE(Parameter.BPMN_ATTRIBUTE_LOCATION),
        /** The text of one of BPMN's own child elements. */
        BPMN_ELEMENT(Parameter.BPMN_ELEMENT_LOCATION);

        private final String value;

        XmlLocation(final String value)
        {
            this.value = value;
        }

        /**
         * The location a parameter declaration names. The node type requires a known one, so only a declaration
         * written past its checks, as straight into a NodeBuilder, can name none or an unknown one. It is read as the
         * default.
         *
         * @param value the stored {@code xmlLocation}, or {@code null} if none is stored
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
     * Reads one vocabulary entry.
     *
     * @param name the entry's node name
     * @param node the {@code wf:FlowNodeType} node to read
     * @param nodeTypesRoot the {@code /jcr:system/jcr:nodeTypes} node state, which recognizes a parameter or an
     *            option by its node type, subtypes included, and says whether each parameter can be stored
     * @return the entry as the parser sees it
     */
    static FlowNodeTypeInfo from(final String name, final NodeState node, final NodeState nodeTypesRoot)
    {
        final String childElement = node.getString(XML_CHILD_ELEMENT_PROPERTY);
        final String extensionElement = node.getString(XML_EXTENSION_ELEMENT_PROPERTY);
        final String jcrNodeType = node.getString(JCR_NODE_TYPE_PROPERTY);
        final Declarations declarations = new Declarations(nodeTypesRoot, new StorageRefusals(nodeTypesRoot),
            new ArrayList<>(), new ArrayList<>());
        final List<ParameterInfo> parameters = declarations.read(node, "", jcrNodeType);
        return new FlowNodeTypeInfo(name, node.getString(JCR_UUID_PROPERTY), jcrNodeType,
            childElement == null ? null : WorkflowDefinitionUtils.localName(childElement),
            extensionElement == null ? null : WorkflowDefinitionUtils.localName(extensionElement),
            node.getString(JCR_PROPERTIES_PROPERTY), node.getLong(PRIORITY_PROPERTY),
            new Parameters(parameters, declarations.unread(), declarations.misplaced()));
    }

    /**
     * The walk over an entry's declarations: what it reads them against, and where it notes the nodes it cannot
     * read, by path relative to the entry.
     *
     * @param nodeTypesRoot the node type registry, which recognizes a declaration by its node type
     * @param refusals what decides whether the node holding each parameter can store it
     * @param unread the nodes that say they are parameters by their resource type alone
     * @param misplaced the parameters declared directly under another parameter
     * @since 0.1.0
     */
    private record Declarations(NodeState nodeTypesRoot, StorageRefusals refusals, List<String> unread,
        List<String> misplaced)
    {
        /**
         * The parameters declared as children of an entry or of a structure's fields node, in the order they are
         * declared. A node that says it is a parameter by its resource type alone is noted as unread. Anything else
         * is walked past: the residual child definitions let a vocabulary entry hold anything.
         *
         * @param parent the entry or fields node
         * @param prefix the path of the parent relative to the entry, slash-terminated, or empty for the entry
         * @param holderType the node type of the node the parameters are stored on
         */
        List<ParameterInfo> read(final NodeState parent, final String prefix, final String holderType)
        {
            final List<ParameterInfo> result = new ArrayList<>();
            for (final String name : OrderedChildren.names(parent)) {
                final NodeState state = parent.getChildNode(name);
                if (isParameter(state)) {
                    result.add(parameter(name, prefix + name, state, holderType));
                } else if (isUnreadParameter(state)) {
                    this.unread.add(prefix + name);
                }
            }
            return result;
        }

        /**
         * Reads one parameter declaration.
         *
         * @param name the parameter's name
         * @param path the declaration's path relative to the entry, which problems name it by
         * @param node the {@code wf:Parameter} node
         * @param holderType the node type of the node the parameter is stored on
         */
        private ParameterInfo parameter(final String name, final String path, final NodeState node,
            final String holderType)
        {
            // Autocreated in the node type, so absent only for a declaration written past it, as straight into a
            // NodeBuilder. An absent maximum must not read as 0, which means unbounded rather than one.
            final long maxValues = node.hasProperty(Parameter.MAX_VALUES_PROPERTY)
                ? node.getLong(Parameter.MAX_VALUES_PROPERTY) : Parameter.DEFAULT_MAX_VALUES;
            final Set<String> options = classifyChildren(node, path);
            final String dataType = node.getString(Parameter.DATA_TYPE_PROPERTY);
            return Parameter.STRUCTURE_DATA_TYPE.equals(dataType)
                ? structure(name, path, node, maxValues, holderType)
                : value(name, node, maxValues, options, holderType);
        }

        private StructureParameter structure(final String name, final String path, final NodeState node,
            final long maxValues, final String holderType)
        {
            final String declared = node.getString(JCR_NODE_TYPE_PROPERTY);
            final String recordType = declared == null ? OrderedChildren.NT_UNSTRUCTURED_TYPE : declared;
            final List<ParameterInfo> fields = read(node.getChildNode(Parameter.FIELDS_CHILD),
                path + "/" + Parameter.FIELDS_CHILD + "/", recordType);
            return new StructureParameter(name, node.getLong(Parameter.MIN_VALUES_PROPERTY), maxValues,
                XmlLocation.of(node.getString(Parameter.XML_LOCATION_PROPERTY)),
                node.getString(Parameter.KEY_PARAMETER_PROPERTY), recordType, fields,
                this.refusals.ofStructure(holderType, name, recordType, maxValues != 1));
        }

        private ValueParameter value(final String name, final NodeState node, final long maxValues,
            final Set<String> options, final String holderType)
        {
            final ParameterType type = ParameterType.of(node.getString(Parameter.DATA_TYPE_PROPERTY),
                node.getString(Parameter.REFERENCE_TYPE_PROPERTY));
            return new ValueParameter(name, type, node.getLong(Parameter.MIN_VALUES_PROPERTY), maxValues,
                XmlLocation.of(node.getString(Parameter.XML_LOCATION_PROPERTY)),
                xmlPath(node.getString(Parameter.XML_PATH_PROPERTY)), options,
                this.refusals.ofValue(holderType, name, type.storageType(), maxValues != 1));
        }

        /**
         * Classifies a declaration's children in the order they are declared. An option is one of the values the
         * parameter offers. A parameter is a field declared beside the fields node rather than inside it, an easy
         * mistake to make, and is noted as misplaced.
         *
         * @return the offered values. A set: the parser only ever asks whether a value is one of them, and an
         *         option repeated in the vocabulary is still one offer.
         */
        private Set<String> classifyChildren(final NodeState node, final String path)
        {
            final Set<String> result = new LinkedHashSet<>();
            for (final String name : OrderedChildren.names(node)) {
                final NodeState child = node.getChildNode(name);
                if (isParameter(child) || isUnreadParameter(child)) {
                    this.misplaced.add(path + "/" + name);
                } else if (NodeTypeUtils.isOfType(child, Parameter.OPTION_NODE_TYPE, this.nodeTypesRoot)
                    && child.hasProperty(Parameter.OPTION_VALUE_PROPERTY)) {
                    result.add(child.getString(Parameter.OPTION_VALUE_PROPERTY));
                }
            }
            return result;
        }

        private boolean isParameter(final NodeState node)
        {
            return NodeTypeUtils.isOfType(node, Parameter.NODE_TYPE, this.nodeTypesRoot);
        }
    }

    private static boolean isUnreadParameter(final NodeState node)
    {
        return Parameter.RESOURCE_TYPE.equals(node.getString(SLING_RESOURCE_TYPE_PROPERTY));
    }

    /** The steps of an {@code xmlPath}, each reduced to its local name. */
    private static List<String> xmlPath(final String raw)
    {
        return raw == null ? List.of()
            : Arrays.stream(raw.split("/")).filter(StringUtils::isNotBlank)
                .map(step -> WorkflowDefinitionUtils.localName(step.trim())).toList();
    }
}

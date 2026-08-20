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

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.util.ISO8601;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Turns the BPMN 2.0 diagram stored on a {@code wf:WorkflowVersion} into the {@code wf:FlowNode} graph the workflow
 * engine executes, so that the engine reads plain JCR nodes rather than re-reading XML.
 *
 * <p>The mapping from BPMN elements to JCR node types is fully data-driven: every {@code wf:FlowNodeType} node
 * configured under {@code /WorkflowTypes} (see {@code workflowTypes.cnd}) declares which XML element it matches
 * ({@code xmlElement}, optionally narrowed down by a required {@code xmlChildElement}), which JCR node type to
 * create for it ({@code jcrNodeType}), literal properties to set on that node ({@code jcrProperties}), and which
 * XML attributes to copy over ({@code properties}). Adding support for a new BPMN element, or a more specific
 * variant of an existing one, only requires adding a new {@code wf:FlowNodeType} node, no code changes. Candidates
 * are discovered by walking the {@code /WorkflowTypes} subtree for any node carrying an {@code xmlElement}
 * property, which only {@code wf:FlowNodeType} instances do.</p>
 *
 * <p>When several configured types match the same XML element (e.g. a plain start event versus a message start
 * event), the one with the highest {@code priority} whose {@code xmlChildElement} requirement (if any) is
 * satisfied by the element is used.</p>
 *
 * <p>{@code bpmn:sequenceFlow} elements are handled separately, in a second pass after every other flow node has
 * been created, and are stored as {@code wf:SequenceFlow} children of their source flow node. Diagram-only
 * elements ({@code bpmndi:*}) are never visited, since only the children of the {@code bpmn:process} element are
 * traversed.</p>
 *
 * <p>Written against the Oak {@link NodeBuilder} of an in-progress commit, which is why the properties the JCR
 * layer would autocreate are set explicitly here; see {@code applySystemProperties}.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class WorkflowDefinitionUtils
{
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowDefinitionUtils.class);

    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    private static final String PROCESS_ELEMENT = "process";

    private static final String SEQUENCE_FLOW_ELEMENT = "sequenceFlow";

    private static final String SEQUENCE_FLOW_NODETYPE = "wf:SequenceFlow";

    private static final String FLOW_NODE_NODETYPE = "wf:FlowNode";

    private static final String SUPERTYPES_PROPERTY = "rep:supertypes";

    private static final String NAME_ATTRIBUTE = "name";

    private static final String LABEL_PROPERTY = "label";

    private static final String XML_CHILD_ELEMENT_PROPERTY = "xmlChildElement";

    private static final String JCR_NODE_TYPE_PROPERTY = "jcrNodeType";

    private static final String JCR_PROPERTIES_PROPERTY = "jcrProperties";

    private static final String PROPERTIES_PROPERTY = "properties";

    private static final String TARGET_REF_PROPERTY = "targetRef";

    private static final String CONDITION_EXPRESSION_PROPERTY = "conditionExpression";

    private static final String ID_ATTRIBUTE = "id";

    private static final String XML_ELEMENT_PROPERTY = "xmlElement";

    private static final String PRIORITY_PROPERTY = "priority";

    private static final String ELEMENT_ID_PROPERTY = "elementId";

    private static final String FLOW_NODE_TYPE_PROPERTY = "flowNodeType";

    private static final String JCR_PRIMARY_TYPE = "jcr:primaryType";

    private static final String JCR_UUID = "jcr:uuid";

    private static final String JCR_CREATED_PROPERTY = "jcr:created";

    private static final String JCR_CREATED_BY_PROPERTY = "jcr:createdBy";

    private static final String SLING_RESOURCE_TYPE_PROPERTY = "sling:resourceType";

    private static final String SLING_RESOURCE_SUPER_TYPE_PROPERTY = "sling:resourceSuperType";

    private static final String JCR_SUPERTYPES_PROPERTY = "jcr:supertypes";

    private WorkflowDefinitionUtils()
    {
        // Utility class, no instances allowed
    }

    /**
     * Parses the given BPMN 2.0 XML and (re)creates the {@code wf:FlowNode} children of {@code workflowVersion} to
     * match it. Any previously parsed flow nodes are removed first, making this safe to call repeatedly for the
     * same node as its {@code bpmnXml} changes.
     *
     * @param bpmnXml the BPMN 2.0 XML source to parse
     * @param workflowVersion the {@code wf:WorkflowVersion} node builder to write the parsed flow nodes into
     * @param workflowTypesRoot the {@code /WorkflowTypes} node state to discover {@code wf:FlowNodeType} candidates
     *            from
     * @param nodeTypesRoot the {@code /jcr:system/jcr:nodeTypes} node state, used to recognize previously parsed
     *            {@code wf:FlowNode}/{@code wf:SequenceFlow} children (including subtypes) when clearing them, and
     *            to look up the immediate supertype of a created node's type for {@code sling:resourceSuperType}
     * @param author the user id to record as {@code jcr:createdBy} on newly created flow nodes/sequence flows
     * @param workflowVersionPath the path of {@code workflowVersion}, for diagnostic messages only
     * @throws ParserConfigurationException if a compliant XML parser could not be created
     * @throws SAXException if the BPMN XML is not well-formed
     * @throws IOException if the BPMN XML could not be read
     */
    public static void parse(final String bpmnXml, final NodeBuilder workflowVersion,
        final NodeState workflowTypesRoot, final NodeState nodeTypesRoot, final String author,
        final String workflowVersionPath)
        throws ParserConfigurationException, SAXException, IOException
    {
        final Element process = getProcessElement(bpmnXml, workflowVersionPath);
        clear(workflowVersion, nodeTypesRoot);
        if (process == null) {
            return;
        }
        final Map<String, List<FlowNodeTypeInfo>> flowNodeTypes = loadFlowNodeTypes(workflowTypesRoot);
        final Map<String, Element> elementsById = new LinkedHashMap<>();
        final List<Element> sequenceFlows = new ArrayList<>();

        final NodeList children = process.getChildNodes();
        for (int i = 0; i < children.getLength(); ++i) {
            if (!(children.item(i) instanceof Element element) || !BPMN_NS.equals(element.getNamespaceURI())) {
                continue;
            }
            final String id = element.getAttribute(ID_ATTRIBUTE);
            if (StringUtils.isBlank(id)) {
                continue;
            }
            final String localName = element.getLocalName();
            if (SEQUENCE_FLOW_ELEMENT.equals(localName)) {
                sequenceFlows.add(element);
                continue;
            }
            elementsById.put(id, element);
            createFlowNode(element, localName, id, flowNodeTypes, workflowVersion, nodeTypesRoot, author);
        }

        for (final Element sequenceFlow : sequenceFlows) {
            createSequenceFlow(sequenceFlow, elementsById, workflowVersion, nodeTypesRoot, author);
        }
    }

    /**
     * Removes the {@code wf:FlowNode}/{@code wf:SequenceFlow} children of {@code workflowVersion} without parsing
     * any replacement, for when the {@code bpmn.xml} they were derived from is deleted outright. {@code bpmn.xml}
     * and any other kind of child a {@code wf:WorkflowVersion} may be extended with are left untouched.
     *
     * @param workflowVersion the {@code wf:WorkflowVersion} node builder to remove the parsed flow nodes from
     * @param nodeTypesRoot the {@code /jcr:system/jcr:nodeTypes} node state, used to recognize previously parsed
     *            {@code wf:FlowNode}/{@code wf:SequenceFlow} children (including subtypes)
     */
    public static void clear(@NotNull final NodeBuilder workflowVersion, @NotNull final NodeState nodeTypesRoot)
    {
        StreamSupport.stream(workflowVersion.getChildNodeNames().spliterator(), false)
            .map(workflowVersion::getChildNode)
            .filter(child -> isFlowNodeOrSequenceFlow(child, nodeTypesRoot))
            // Collected before removing: the child name view above is live.
            .toList()
            .forEach(NodeBuilder::remove);
    }

    private static Element getProcessElement(final String bpmnXml, final String workflowVersionPath)
        throws ParserConfigurationException, SAXException, IOException
    {
        final Document document = parseXml(bpmnXml);
        final NodeList processes = document.getElementsByTagNameNS(BPMN_NS, PROCESS_ELEMENT);
        if (processes.getLength() == 0) {
            LOGGER.warn("No <bpmn:process> element found in bpmnXml at {}", workflowVersionPath);
            return null;
        }
        return (Element) processes.item(0);
    }

    private static Document parseXml(final String bpmnXml)
        throws ParserConfigurationException, SAXException, IOException
    {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(bpmnXml)));
    }

    private static boolean isFlowNodeOrSequenceFlow(final NodeBuilder node, final NodeState nodeTypesRoot)
    {
        final String type = node.getName(JCR_PRIMARY_TYPE);
        if (type == null) {
            return false;
        }
        if (FLOW_NODE_NODETYPE.equals(type) || SEQUENCE_FLOW_NODETYPE.equals(type)) {
            return true;
        }
        // One registry lookup covers both questions: rep:supertypes is the transitively-expanded supertype list Oak
        // materializes for each registered node type under /jcr:system/jcr:nodeTypes.
        return StreamSupport.stream(nodeTypesRoot.getChildNode(type).getNames(SUPERTYPES_PROPERTY).spliterator(), false)
            .anyMatch(supertype -> FLOW_NODE_NODETYPE.equals(supertype) || SEQUENCE_FLOW_NODETYPE.equals(supertype));
    }

    /**
     * Loads all configured {@code wf:FlowNodeType} nodes under {@code workflowTypesRoot}, grouped by the local name
     * of the XML element they match (their {@code xmlElement} property, with any namespace prefix stripped), each
     * group ordered from highest to lowest {@code priority} so that the most specific match can be picked first.
     */
    private static Map<String, List<FlowNodeTypeInfo>> loadFlowNodeTypes(final NodeState workflowTypesRoot)
    {
        final Map<String, List<FlowNodeTypeInfo>> result = new LinkedHashMap<>();
        collectFlowNodeTypes(workflowTypesRoot, result);
        for (final List<FlowNodeTypeInfo> candidates : result.values()) {
            candidates.sort(Comparator.comparingLong(FlowNodeTypeInfo::priority).reversed());
        }
        return result;
    }

    private static void collectFlowNodeTypes(final NodeState node, final Map<String, List<FlowNodeTypeInfo>> result)
    {
        final String xmlElement = node.getString(XML_ELEMENT_PROPERTY);
        if (xmlElement != null) {
            result.computeIfAbsent(localName(xmlElement), k -> new ArrayList<>()).add(FlowNodeTypeInfo.from(node));
        }
        for (final ChildNodeEntry child : node.getChildNodeEntries()) {
            collectFlowNodeTypes(child.getNodeState(), result);
        }
    }

    private static String localName(final String qualifiedName)
    {
        final int colon = qualifiedName.indexOf(':');
        return colon < 0 ? qualifiedName : qualifiedName.substring(colon + 1);
    }

    private static void createFlowNode(final Element element, final String localName, final String id,
        final Map<String, List<FlowNodeTypeInfo>> flowNodeTypes, final NodeBuilder workflowVersion,
        final NodeState nodeTypesRoot, final String author)
    {
        final FlowNodeTypeInfo flowNodeType = matchFlowNodeType(element, flowNodeTypes.get(localName));
        if (flowNodeType == null) {
            LOGGER.warn("No FlowNodeType configured for BPMN element <{}> (id={}), skipping", localName, id);
            return;
        }
        final NodeBuilder node = workflowVersion.child(id);
        node.setProperty(JCR_PRIMARY_TYPE, flowNodeType.jcrNodeType(), Type.NAME);
        node.setProperty(FLOW_NODE_TYPE_PROPERTY, flowNodeType.identifier(), Type.REFERENCE);
        node.setProperty(ELEMENT_ID_PROPERTY, id);
        final String name = element.getAttribute(NAME_ATTRIBUTE);
        if (StringUtils.isNotBlank(name)) {
            node.setProperty(LABEL_PROPERTY, name);
        }
        applyJcrProperties(flowNodeType, node);
        applyCopiedProperties(flowNodeType, element, node);
        applySystemProperties(node, flowNodeType.jcrNodeType(), nodeTypesRoot, author);
    }

    /**
     * Sets the system properties a {@code NodeBuilder}-created node does not get for free the way a node created
     * through the JCR API would: {@code jcr:created}/{@code jcr:createdBy}, and the {@code sling:resourceType}/
     * {@code sling:resourceSuperType} pair that mirrors this node's JCR type/immediate supertype by naming
     * convention (e.g. {@code wf:StartEvent} extending {@code wf:Event} becomes {@code wf/StartEvent} extending
     * {@code wf/Event}), matching the fixed defaults every concrete flow node type declares in
     * {@code workflowDefinitions.cnd}.
     */
    private static void applySystemProperties(final NodeBuilder node, final String primaryType,
        final NodeState nodeTypesRoot, final String author)
    {
        node.setProperty(JCR_CREATED_PROPERTY, ISO8601.format(Calendar.getInstance()), Type.DATE);
        node.setProperty(JCR_CREATED_BY_PROPERTY, author);
        node.setProperty(SLING_RESOURCE_TYPE_PROPERTY, toResourceType(primaryType));
        final String supertype = directSupertype(primaryType, nodeTypesRoot);
        if (supertype != null) {
            node.setProperty(SLING_RESOURCE_SUPER_TYPE_PROPERTY, toResourceType(supertype));
        }
    }

    private static String toResourceType(final String jcrNodeType)
    {
        return jcrNodeType.replace(':', '/');
    }

    /**
     * Looks up the immediate (directly declared, not transitively expanded) supertype of {@code type} from
     * {@code jcr:supertypes}, the property Oak materializes for each registered node type under
     * {@code /jcr:system/jcr:nodeTypes}. Every concrete {@code wf:FlowNode}/{@code wf:SequenceFlow} subtype in
     * {@code workflowDefinitions.cnd} declares exactly one supertype, so the first value is unambiguous.
     */
    private static String directSupertype(final String type, final NodeState nodeTypesRoot)
    {
        final Iterator<String> values =
            nodeTypesRoot.getChildNode(type).getNames(JCR_SUPERTYPES_PROPERTY).iterator();
        return values.hasNext() ? values.next() : null;
    }

    /**
     * Picks the most specific {@code wf:FlowNodeType} matching the given element out of the candidates that match
     * its {@code xmlElement}, already sorted from highest to lowest priority. A candidate with an
     * {@code xmlChildElement} only matches when the element has a direct child of that name; a candidate without
     * one always matches.
     */
    private static FlowNodeTypeInfo matchFlowNodeType(final Element element, final List<FlowNodeTypeInfo> candidates)
    {
        if (candidates == null) {
            return null;
        }
        for (final FlowNodeTypeInfo candidate : candidates) {
            final String requiredChild = candidate.xmlChildElement();
            if (requiredChild == null || hasChildElement(element, localName(requiredChild))) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean hasChildElement(final Element element, final String localName)
    {
        final NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); ++i) {
            if (children.item(i) instanceof Element child && localName.equals(child.getLocalName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sets the literal property values configured in the FlowNodeType's {@code jcrProperties}, a lightweight
     * {@code {name: value, ...}} map (unquoted keys, boolean/numeric/quoted-or-bare-string values).
     */
    private static void applyJcrProperties(final FlowNodeTypeInfo flowNodeType, final NodeBuilder node)
    {
        final String raw = flowNodeType.jcrProperties();
        if (raw == null) {
            return;
        }
        for (final Map.Entry<String, String> property : parseLiteralMap(raw).entrySet()) {
            setTypedProperty(node, property.getKey(), property.getValue());
        }
    }

    private static Map<String, String> parseLiteralMap(final String raw)
    {
        final Map<String, String> result = new LinkedHashMap<>();
        final String trimmed = StringUtils.strip(raw.trim(), "{}");
        if (StringUtils.isBlank(trimmed)) {
            return result;
        }
        for (final String pair : trimmed.split(",")) {
            final int colon = pair.indexOf(':');
            if (colon < 0) {
                continue;
            }
            final String key = pair.substring(0, colon).trim();
            final String value = StringUtils.strip(pair.substring(colon + 1).trim(), "\"'");
            if (StringUtils.isNotBlank(key)) {
                result.put(key, value);
            }
        }
        return result;
    }

    private static void setTypedProperty(final NodeBuilder node, final String name, final String value)
    {
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            node.setProperty(name, Boolean.parseBoolean(value));
        } else if (NumberUtils.isParsable(value)) {
            node.setProperty(name, value.contains(".") ? Double.parseDouble(value) : Long.parseLong(value));
        } else {
            node.setProperty(name, value);
        }
    }

    /**
     * Copies XML attributes onto JCR properties as configured in the FlowNodeType's {@code properties} list. Each
     * entry is either a plain name copied as-is ({@code priority}), or an {@code xmlAttribute=jcrProperty} pair to
     * rename it in the process ({@code assigneeExpression=assignee}).
     */
    private static void applyCopiedProperties(final FlowNodeTypeInfo flowNodeType, final Element element,
        final NodeBuilder node)
    {
        for (final String rule : flowNodeType.properties()) {
            final int separator = rule.indexOf('=');
            final String xmlAttribute = separator < 0 ? rule : rule.substring(0, separator);
            final String jcrProperty = separator < 0 ? rule : rule.substring(separator + 1);
            final String value = element.getAttribute(xmlAttribute.trim());
            if (StringUtils.isNotBlank(value)) {
                node.setProperty(jcrProperty.trim(), value);
            }
        }
    }

    private static void createSequenceFlow(final Element element, final Map<String, Element> elementsById,
        final NodeBuilder workflowVersion, final NodeState nodeTypesRoot, final String author)
    {
        final String id = element.getAttribute(ID_ATTRIBUTE);
        final String sourceRef = element.getAttribute("sourceRef");
        final String targetRef = element.getAttribute(TARGET_REF_PROPERTY);
        if (StringUtils.isBlank(sourceRef) || StringUtils.isBlank(targetRef)) {
            LOGGER.warn("SequenceFlow {} is missing sourceRef or targetRef, skipping", id);
            return;
        }
        if (!workflowVersion.hasChildNode(sourceRef)) {
            LOGGER.warn("SequenceFlow {} references unknown source node {}, skipping", id, sourceRef);
            return;
        }
        final NodeBuilder sourceNode = workflowVersion.getChildNode(sourceRef);
        final NodeBuilder flow = sourceNode.child(id);
        flow.setProperty(JCR_PRIMARY_TYPE, SEQUENCE_FLOW_NODETYPE, Type.NAME);
        flow.setProperty(ELEMENT_ID_PROPERTY, id);
        flow.setProperty(TARGET_REF_PROPERTY, targetRef);
        final String name = element.getAttribute(NAME_ATTRIBUTE);
        if (StringUtils.isNotBlank(name)) {
            flow.setProperty(LABEL_PROPERTY, name);
        }
        final String condition = getConditionExpression(element);
        if (condition != null) {
            flow.setProperty(CONDITION_EXPRESSION_PROPERTY, condition);
        }
        final Element source = elementsById.get(sourceRef);
        if (source != null && id.equals(source.getAttribute("default"))) {
            flow.setProperty("isDefault", true);
        }
        applySystemProperties(flow, SEQUENCE_FLOW_NODETYPE, nodeTypesRoot, author);
    }

    private static String getConditionExpression(final Element sequenceFlow)
    {
        final NodeList conditions = sequenceFlow.getElementsByTagNameNS(BPMN_NS, CONDITION_EXPRESSION_PROPERTY);
        if (conditions.getLength() == 0) {
            return null;
        }
        final String condition = conditions.item(0).getTextContent().trim();
        return condition.isBlank() ? null : condition;
    }

    /**
     * A snapshot of the properties of one {@code wf:FlowNodeType} node needed to match and apply it, read once per
     * parse so that the rest of the parsing logic never has to touch {@link NodeState} again.
     *
     * @since 0.1.0
     */
    private record FlowNodeTypeInfo(String identifier, String jcrNodeType, String xmlChildElement,
        String jcrProperties, Iterable<String> properties, long priority)
    {
        static FlowNodeTypeInfo from(final NodeState node)
        {
            final String childElement = node.getString(XML_CHILD_ELEMENT_PROPERTY);
            return new FlowNodeTypeInfo(node.getString(JCR_UUID), node.getString(JCR_NODE_TYPE_PROPERTY),
                childElement == null ? null : localName(childElement), node.getString(JCR_PROPERTIES_PROPERTY),
                node.getStrings(PROPERTIES_PROPERTY), node.getLong(PRIORITY_PROPERTY));
        }
    }
}

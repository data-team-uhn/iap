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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.oak.api.Type;
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

import io.uhndata.iap.utils.NodeTypeUtils;

/**
 * Turns the BPMN 2.0 diagram stored on a {@code wf:WorkflowVersion} into the {@code wf:FlowNode} graph the workflow
 * engine executes, so that the engine reads plain JCR nodes rather than re-reading XML.
 *
 * <p>The mapping from BPMN elements to JCR node types is fully data-driven, by the {@code wf:FlowNodeType} nodes
 * configured under {@code /WorkflowTypes} (see {@code workflowTypes.cnd}). Each declares the XML element it matches
 * ({@code xmlElement}), optionally narrowed down by a required {@code xmlChildElement} or {@code
 * xmlExtensionElement}. It names the JCR node type to create for it ({@code jcrNodeType}) and literal properties to
 * set on that node ({@code jcrProperties}). Its typed {@code wf:Parameter} children say what else a node of that
 * kind carries. Adding support for a new BPMN element, or a more specific variant of an existing one, only
 * requires adding a new {@code wf:FlowNodeType} node, no code changes. Candidates are found by walking the
 * {@code /WorkflowTypes} subtree for nodes of that type or a subtype of it. A candidate missing the
 * {@code jcrNodeType} or the identity a stored node needs is reported and passed over. A half-authored entry then
 * cannot take an element away from the next candidate.</p>
 *
 * <p>Several configured types may match the same XML element, such as a plain and a message start event, or two
 * service task handlers. The one used has the highest {@code priority} among those whose {@code xmlChildElement}
 * and {@code xmlExtensionElement} requirements, if any, the element satisfies. A tie goes to the entry whose node
 * name sorts first.</p>
 *
 * <p>A parameter is read from where it says it lives and stored in the JCR type it declares; see
 * {@link ParameterTranslation}. The entry's {@code jcrProperties} are applied afterwards, so a value
 * the entry fixes cannot be argued with by a diagram.</p>
 *
 * <p>Two BPMN elements say where they are stored rather than being stored where they are written, so they are
 * handled in a second pass once every other flow node exists. A {@code bpmn:sequenceFlow} becomes a
 * {@code wf:SequenceFlow} child of the flow node its {@code sourceRef} names, and a {@code bpmn:boundaryEvent}
 * becomes a child of the activity its {@code attachedToRef} names — which, per
 * {@code workflowDefinitions.cnd}, is the whole of what makes it a boundary event rather than a free-standing
 * mid-process catch. Diagram-only elements ({@code bpmndi:*}) are never visited, since only the children of the
 * {@code bpmn:process} element are traversed.</p>
 *
 * <p>Written against the Oak {@link NodeBuilder} of an in-progress commit, which is why the properties the JCR
 * layer would autocreate are set explicitly here; see {@code applySystemProperties}.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class WorkflowDefinitionUtils
{
    /**
     * What every recorded translation problem is filed under. All of these are mis-authored BPMN rather than
     * anything broken here, and all of them end the same way: a piece of the definition is silently not translated,
     * so the workflow the engine runs is quietly not the workflow the author drew.
     */
    static final String TRANSLATION = "parseBpmn";

    /** The detail naming the BPMN element a translation problem was about. */
    static final String ELEMENT_DETAIL = "element";

    /**
     * Where everything BPMN itself has no word for is written, such as which code a service task runs or who may
     * pass through a node. Matched by namespace rather than by prefix, since a prefix is the document's choice and
     * a diagram editor is free to renormalize {@code iap:} to anything on save.
     */
    static final String IAP_NS = "https://iap.uhndata.io/bpmn";

    /** BPMN's own namespace, which every element a diagram is drawn with belongs to. */
    static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowDefinitionUtils.class);

    private static final String PROCESS_ELEMENT = "process";

    private static final String SEQUENCE_FLOW_ELEMENT = "sequenceFlow";

    private static final String BOUNDARY_EVENT_ELEMENT = "boundaryEvent";

    private static final String SEQUENCE_FLOW_NODETYPE = "wf:SequenceFlow";

    private static final String FLOW_NODE_NODETYPE = "wf:FlowNode";

    private static final String NAME_ATTRIBUTE = "name";

    private static final String ATTACHED_TO_REF_ATTRIBUTE = "attachedToRef";

    private static final String MESSAGE_EVENT_DEFINITION = "messageEventDefinition";

    private static final String MESSAGE_ELEMENT = "message";

    private static final String MESSAGE_REF_ATTRIBUTE = "messageRef";

    private static final String MESSAGE_NAME_PROPERTY = "messageName";

    private static final String TIMER_EVENT_DEFINITION = "timerEventDefinition";

    private static final String TIME_DURATION_ELEMENT = "timeDuration";

    private static final String TIMER_DURATION_PROPERTY = "timerDuration";

    private static final String CANCEL_ACTIVITY_ATTRIBUTE = "cancelActivity";

    private static final String INTERRUPTING_PROPERTY = "interrupting";

    /** JCR's own System View namespace, used verbatim rather than reinvented. */
    private static final String SV_NS = "http://www.jcp.org/jcr/sv/1.0";

    private static final String EXTENSION_ELEMENTS = "extensionElements";

    private static final String SV_NODE = "node";

    private static final String SV_PROPERTY = "property";

    private static final String SV_VALUE = "value";

    private static final String SV_NAME_ATTRIBUTE = "name";

    private static final String SV_TYPE_ATTRIBUTE = "type";

    private static final String SV_MULTIPLE_ATTRIBUTE = "multiple";

    private static final String LABEL_PROPERTY = "label";

    private static final String TARGET_REF_PROPERTY = "targetRef";

    private static final String CONDITION_EXPRESSION_PROPERTY = "conditionExpression";

    private static final String ID_ATTRIBUTE = "id";

    private static final String XML_ELEMENT_PROPERTY = "xmlElement";

    private static final String ELEMENT_ID_PROPERTY = "elementId";

    private static final String FLOW_NODE_TYPE_PROPERTY = "flowNodeType";

    private static final String JCR_PRIMARY_TYPE = "jcr:primaryType";

    private static final String FLOW_NODE_TYPE_NODETYPE = "wf:FlowNodeType";

    private static final String FLOW_NODE_TYPE_DETAIL = "flowNodeType";

    private static final String WORKFLOW_TYPES_ROOT_NAME = "WorkflowTypes";

    private static final String JCR_CREATED_PROPERTY = "jcr:created";

    private static final String JCR_CREATED_BY_PROPERTY = "jcr:createdBy";

    private static final String SLING_RESOURCE_TYPE_PROPERTY = "sling:resourceType";

    private static final String SLING_RESOURCE_SUPER_TYPE_PROPERTY = "sling:resourceSuperType";

    private static final String JCR_SUPERTYPES_PROPERTY = "jcr:supertypes";

    /** The characters JCR reserves, and therefore forbids in a node name. */
    private static final String INVALID_NAME_CHARACTERS = "/:[]|*";

    /** Names JCR reads as path steps rather than as children. */
    private static final Set<String> RELATIVE_PATH_NAMES = Set.of(".", "..");

    /** Built on first use and reused; see {@link #createFactory()} for why that matters and what it hardens. */
    private static DocumentBuilderFactory factory;

    private WorkflowDefinitionUtils()
    {
        // Utility class, no instances allowed
    }

    /**
     * The invariants of a single parse: everything the per-element methods need that does not change from one element
     * to the next, bundled together so the individual methods stay within the checkstyle parameter limit.
     *
     * <p>{@code createdNodes} holds every node this parse has made, by the BPMN id it was made for.</p>
     *
     * @since 0.1.0
     */
    private record ParseContext(NodeBuilder workflowVersion, NodeState root, String author, String created,
        Map<String, NodeBuilder> createdNodes, Map<String, String> messageNames, ParseProblems problems)
    {
        ParseContext(final NodeBuilder workflowVersion, final NodeState root, final String author,
            final String created, final Map<String, String> messageNames, final ParseProblems problems)
        {
            this(workflowVersion, root, author, created, new LinkedHashMap<>(), messageNames, problems);
        }

        String path()
        {
            return this.problems.path();
        }

        NodeState nodeTypesRoot()
        {
            return NodeTypeUtils.registry(this.root);
        }

        Set<String> claimedNames()
        {
            return this.createdNodes.keySet();
        }
    }

    /**
     * Parses the given BPMN 2.0 XML and (re)creates the {@code wf:FlowNode} children of {@code workflowVersion} to
     * match it. Any previously parsed flow nodes are removed first, making this safe to call repeatedly for the
     * same node as its {@code bpmnXml} changes.
     *
     * @param bpmnXml the BPMN 2.0 XML source to parse
     * @param workflowVersion the {@code wf:WorkflowVersion} node builder to write the parsed flow nodes into
     * @param root the committed repository root. {@code /WorkflowTypes} is where the {@code wf:FlowNodeType}
     *            candidates are found. {@code /jcr:system/jcr:nodeTypes} recognizes previously parsed
     *            {@code wf:FlowNode}/{@code wf:SequenceFlow} children, subtypes included, when clearing them. It
     *            also supplies a created node's immediate supertype. A reference parameter's value is resolved
     *            against the tree as a whole.
     * @param author the user id to record as {@code jcr:createdBy} on newly created flow nodes/sequence flows
     * @param workflowVersionPath the path of {@code workflowVersion}, for diagnostic messages only
     * @throws ParserConfigurationException if a compliant XML parser could not be created
     * @throws SAXException if the BPMN XML is not well-formed
     * @throws IOException if the BPMN XML could not be read
     */
    public static void parse(@NotNull final InputStream bpmnXml, @NotNull final NodeBuilder workflowVersion,
        @NotNull final NodeState root, @NotNull final String author, @NotNull final String workflowVersionPath)
        throws ParserConfigurationException, SAXException, IOException
    {
        final ParseProblems problems = new ParseProblems(workflowVersionPath);
        final Element process = getProcessElement(bpmnXml, problems);
        clear(workflowVersion, NodeTypeUtils.registry(root));
        if (process == null) {
            return;
        }
        // One timestamp for the whole batch: every node in it was created by the same parse, and differing
        // millisecond values would suggest otherwise.
        final ParseContext context = new ParseContext(workflowVersion, root, author,
            ISO8601.format(Calendar.getInstance()), messageNames(process), problems);
        final Map<String, List<FlowNodeTypeInfo>> flowNodeTypes =
            loadFlowNodeTypes(root.getChildNode(WORKFLOW_TYPES_ROOT_NAME), context.nodeTypesRoot());
        final Map<String, Element> elementsById = new LinkedHashMap<>();
        final List<Element> sequenceFlows = new ArrayList<>();
        final List<Element> boundaryEvents = new ArrayList<>();

        for (final Element element : childElements(process, BPMN_NS).toList()) {
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
            if (BOUNDARY_EVENT_ELEMENT.equals(localName)) {
                boundaryEvents.add(element);
                continue;
            }
            createFlowNode(element, localName, id, flowNodeTypes, context);
        }

        // Both of these are stored relative to a node named by one of their attributes, so they can only be placed
        // once every node that may be named is in the tree.
        for (final Element boundaryEvent : boundaryEvents) {
            createBoundaryEvent(boundaryEvent, flowNodeTypes, context);
        }
        for (final Element sequenceFlow : sequenceFlows) {
            createSequenceFlow(sequenceFlow, elementsById, context);
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

    private static Element getProcessElement(final InputStream bpmnXml, final ParseProblems problems)
        throws ParserConfigurationException, SAXException, IOException
    {
        final Document document = parseXml(bpmnXml);
        final NodeList processes = document.getElementsByTagNameNS(BPMN_NS, PROCESS_ELEMENT);
        if (processes.getLength() == 0) {
            LOGGER.warn("No <bpmn:process> element found in bpmnXml at {}", problems.path());
            problems.report("bpmnXml declares no process element", problems.context());
            return null;
        }
        if (processes.getLength() > 1) {
            LOGGER.warn("bpmnXml at {} declares {} <bpmn:process> elements, only the first one is parsed",
                problems.path(), processes.getLength());
            problems.report("bpmnXml declares more than one process element",
                problems.context().with("processes", processes.getLength()));
        }
        return (Element) processes.item(0);
    }

    /**
     * Builds the parser factory. Built once and reused, because {@link DocumentBuilderFactory#newInstance()} performs
     * a {@code ServiceLoader} lookup through the thread context classloader, which under OSGi can scan across bundles
     * and costs more than the parse itself — on a path that runs inside a commit.
     *
     * <p>{@code disallow-doctype-decl} is the load-bearing hardening: {@code bpmn.xml} is content anyone allowed to
     * edit a workflow can write, and without it a {@code <!DOCTYPE ... SYSTEM "http://...">} makes the server fetch
     * that URL on every save, with no timeout, from inside the commit. Refusing DOCTYPE outright also rules out
     * entity expansion, so no separate expansion limits are needed. Entity references are deliberately left
     * expanding: with no DOCTYPE there are none, and turning expansion off would silently blank out the text content
     * of any element that used one — losing, for instance, a sequence flow's guard condition.</p>
     */
    private static DocumentBuilderFactory createFactory() throws ParserConfigurationException
    {
        final DocumentBuilderFactory result = DocumentBuilderFactory.newInstance();
        result.setNamespaceAware(true);
        result.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        result.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        result.setFeature("http://xml.org/sax/features/external-general-entities", false);
        result.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        result.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        result.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        result.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        result.setXIncludeAware(false);
        return result;
    }

    /**
     * Parses from the raw bytes rather than a decoded string, so that the document's own {@code encoding}
     * declaration and any byte order mark are honoured by the parser instead of being pre-empted by a fixed UTF-8
     * decode — a UTF-8 BOM read as a character is a fatal "content not allowed in prolog".
     */
    private static Document parseXml(final InputStream bpmnXml)
        throws ParserConfigurationException, SAXException, IOException
    {
        final DocumentBuilder builder;
        // Neither the factory nor a builder is thread safe, and commits are concurrent.
        synchronized (WorkflowDefinitionUtils.class) {
            if (factory == null) {
                factory = createFactory();
            }
            builder = factory.newDocumentBuilder();
        }
        return builder.parse(new InputSource(bpmnXml));
    }

    private static boolean isFlowNodeOrSequenceFlow(final NodeBuilder node, final NodeState nodeTypesRoot)
    {
        final String type = node.getName(JCR_PRIMARY_TYPE);
        return NodeTypeUtils.isOrExtends(type, FLOW_NODE_NODETYPE, nodeTypesRoot)
            || NodeTypeUtils.isOrExtends(type, SEQUENCE_FLOW_NODETYPE, nodeTypesRoot);
    }

    /**
     * Loads all configured {@code wf:FlowNodeType} nodes under {@code workflowTypesRoot}, grouped by the element
     * they match. That is their {@code xmlElement} property's local name, any namespace prefix stripped. Each
     * group is ordered from highest to lowest {@code priority}, so that the most specific match can be picked first,
     * and by entry name within one priority. {@code /WorkflowTypes} is not orderable, so without a tie-break two
     * equally specific entries would be tried in whatever order the repository happens to iterate its children.
     */
    private static Map<String, List<FlowNodeTypeInfo>> loadFlowNodeTypes(final NodeState workflowTypesRoot,
        final NodeState nodeTypesRoot)
    {
        final Map<String, List<FlowNodeTypeInfo>> result = new LinkedHashMap<>();
        collectFlowNodeTypes(WORKFLOW_TYPES_ROOT_NAME, workflowTypesRoot, nodeTypesRoot, result);
        for (final List<FlowNodeTypeInfo> candidates : result.values()) {
            candidates.sort(Comparator.comparingLong(FlowNodeTypeInfo::priority).reversed()
                .thenComparing(FlowNodeTypeInfo::name));
        }
        return result;
    }

    /**
     * Collects the entries in a subtree. An entry is recognized by its node type rather than by carrying an
     * {@code xmlElement}, which a parameter or any other unstructured child may carry too. Everything under an
     * entry is part of it, so the walk does not descend into one.
     */
    private static void collectFlowNodeTypes(final String name, final NodeState node, final NodeState nodeTypesRoot,
        final Map<String, List<FlowNodeTypeInfo>> result)
    {
        final String xmlElement = node.getString(XML_ELEMENT_PROPERTY);
        if (NodeTypeUtils.isOrExtends(node.getName(JCR_PRIMARY_TYPE), FLOW_NODE_TYPE_NODETYPE, nodeTypesRoot)) {
            if (xmlElement != null) {
                result.computeIfAbsent(localName(xmlElement), k -> new ArrayList<>())
                    .add(FlowNodeTypeInfo.from(name, node, nodeTypesRoot));
            }
            return;
        }
        for (final String child : node.getChildNodeNames()) {
            collectFlowNodeTypes(child, node.getChildNode(child), nodeTypesRoot, result);
        }
    }

    /**
     * The local part of a possibly prefixed XML name. Vocabulary entries name elements the way a document writes
     * them, prefix and all, but a prefix is the document's choice rather than the schema's.
     *
     * @param qualifiedName an XML name, e.g. {@code bpmn:startEvent}
     * @return the part after the colon, or the whole name when it carries no prefix
     */
    static String localName(final String qualifiedName)
    {
        final int colon = qualifiedName.indexOf(':');
        return colon < 0 ? qualifiedName : qualifiedName.substring(colon + 1);
    }

    private static void createFlowNode(final Element element, final String localName, final String id,
        final Map<String, List<FlowNodeTypeInfo>> flowNodeTypes, final ParseContext context)
    {
        final FlowNodeTypeInfo flowNodeType =
            matchFlowNodeType(element, localName, flowNodeTypes.get(localName), context);
        if (flowNodeType == null) {
            return;
        }
        final NodeBuilder node = createNode(context.workflowVersion(), id, flowNodeType.jcrNodeType(), context);
        if (node == null) {
            return;
        }
        populate(node, element, id, flowNodeType, context);
    }

    /**
     * Fills in a flow node just created for an element, in the order that lets the diagram's identity win over
     * anything the vocabulary configures.
     */
    private static void populate(final NodeBuilder node, final Element element, final String id,
        final FlowNodeTypeInfo flowNodeType, final ParseContext context)
    {
        applyParameters(flowNodeType, element, node, context);
        applyJcrProperties(flowNodeType, node, context);
        applyEventDefinitions(element, node, context);
        applyExtensionNodes(element, node, context);
        applyIdentity(node, element, id, flowNodeType);
        applySystemProperties(node, flowNodeType.jcrNodeType(), context);
    }

    /**
     * Look up and fill in out-of-place event definitions in the BPMN.
     *
     * <p>The event name and the timer duration are not attributes, which is why neither is a parameter. A message
     * event names a {@code <bpmn:message>} declared beside the process and the engine wants that message's
     * <em>name</em>, not the id used to point at it. The id is a document-internal handle and the name is what a
     * caller sends. And a timer carries its duration as the text of a {@code <bpmn:timeDuration>} grandchild.</p>
     *
     * @param element the BPMN element being translated
     * @param node the flow node being written
     * @param context the parse in progress, holding the document's message names
     */
    private static void applyEventDefinitions(final Element element, final NodeBuilder node, final ParseContext context)
    {
        final Element message = childElement(element, MESSAGE_EVENT_DEFINITION);
        if (message != null) {
            final String ref = message.getAttribute(MESSAGE_REF_ATTRIBUTE);
            final String name = context.messageNames().get(ref);
            if (StringUtils.isBlank(name)) {
                // Worth raising: an unnamed message can not ever be passed as an event
                LOGGER.warn("Message event {} in {} references message {} which declares no name",
                    element.getAttribute(ID_ATTRIBUTE), context.path(), ref);
            } else {
                node.setProperty(MESSAGE_NAME_PROPERTY, name);
            }
        }
        final Element timer = childElement(element, TIMER_EVENT_DEFINITION);
        if (timer != null) {
            final Element duration = childElement(timer, TIME_DURATION_ELEMENT);
            if (duration != null) {
                setIfNotBlank(node, TIMER_DURATION_PROPERTY, duration.getTextContent().trim());
            }
        }
    }

    /**
     * Builds whatever subtrees a BPMN element carries in its {@code extensionElements}, written as JCR System View.
     *
     * <p><strong>Why System View and not something shorter.</strong> Some of what a flow node holds is a subtree
     * rather than a value — a condition is a {@code cond:SingleCondition} with operand children, and a
     * {@code cond:ConditionGroup} nests. BPMN sanctions exactly this: {@code tExtensionElements} is
     * {@code xsd:any namespace="##other" processContents="lax"}, so an arbitrary foreign subtree is the intended
     * extension point, and {@code bpmn-moddle} preserves one across a round trip (measured, not assumed).</p>
     *
     * <p>The serialization is JCR's own, because every terser scheme reinvents it badly. A node name carries a
     * namespace prefix ({@code cond:condition}) which no element name can hold without deciding what an
     * unprefixed name means; a property may be multi-valued, which no attribute can express without the parser
     * knowing the node type's cardinality; and {@code value} here is {@code UNDEFINED multiple}, so its type is
     * not even implied. System View says all three outright, and it is what "the same structure as in JCR, as XML"
     * already means.</p>
     *
     * <p>Read leniently: a missing {@code sv:type} is a string and a missing {@code sv:multiple} is false, so a
     * hand-written subtree need not restate the obvious. A conformant System View document is still read exactly
     * as it says.</p>
     *
     * @param element the BPMN element being translated
     * @param node the flow node it became
     * @param context the parse in progress
     */
    private static void applyExtensionNodes(final Element element, final NodeBuilder node,
        final ParseContext context)
    {
        final Element extensions = extensions(element);
        if (extensions == null) {
            return;
        }
        childElements(extensions, SV_NS, SV_NODE).forEach(child -> buildSvNode(child, node, context));
    }

    /**
     * Creates one {@code sv:node} and everything under it.
     *
     * @param svNode the System View element describing the node
     * @param parent the node it belongs under
     * @param context the parse in progress
     */
    private static void buildSvNode(final Element svNode, final NodeBuilder parent, final ParseContext context)
    {
        final String name = svAttribute(svNode, SV_NAME_ATTRIBUTE);
        if (StringUtils.isBlank(name)) {
            LOGGER.warn("An sv:node in {} declares no sv:name, skipping it and everything under it",
                context.path());
            return;
        }
        // The primary type is a property in System View and the node's identity in Oak, so it is read out of the
        // properties before the node exists rather than set like the others afterwards
        final String primaryType = svProperties(svNode)
            .filter(property -> JCR_PRIMARY_TYPE.equals(svAttribute(property, SV_NAME_ATTRIBUTE)))
            .findFirst()
            .map(property -> String.join("", svValues(property)))
            .orElse(null);
        if (StringUtils.isBlank(primaryType)) {
            LOGGER.warn("The sv:node {} in {} declares no jcr:primaryType, skipping it", name, context.path());
            return;
        }
        final NodeBuilder child = parent.child(name);
        child.setProperty(JCR_PRIMARY_TYPE, primaryType, Type.NAME);
        applySystemProperties(child, primaryType, context);
        svProperties(svNode)
            .filter(property -> !JCR_PRIMARY_TYPE.equals(svAttribute(property, SV_NAME_ATTRIBUTE)))
            .forEach(property -> setSvProperty(property, child, context));
        childElements(svNode, SV_NS, SV_NODE).forEach(grandchild -> buildSvNode(grandchild, child, context));
    }

    /**
     * Sets one {@code sv:property}, single or multi-valued, in the type it declares.
     *
     * @param svProperty the System View element describing the property
     * @param node the node to set it on
     * @param context the parse in progress
     */
    private static void setSvProperty(final Element svProperty, final NodeBuilder node, final ParseContext context)
    {
        final String name = svAttribute(svProperty, SV_NAME_ATTRIBUTE);
        if (StringUtils.isBlank(name)) {
            LOGGER.warn("An sv:property of a node in {} declares no sv:name, ignoring it", context.path());
            return;
        }
        final List<String> values = svValues(svProperty);
        final String declaredType = svAttribute(svProperty, SV_TYPE_ATTRIBUTE);
        if (Boolean.parseBoolean(svAttribute(svProperty, SV_MULTIPLE_ATTRIBUTE))) {
            node.setProperty(name, values, svMultipleType(declaredType));
            return;
        }
        if (values.isEmpty()) {
            return;
        }
        setSvSingleValue(node, name, values.get(0), declaredType);
    }

    private static void setSvSingleValue(final NodeBuilder node, final String name, final String value,
        final String declaredType)
    {
        switch (declaredType) {
            case "Boolean" -> node.setProperty(name, Boolean.parseBoolean(value));
            case "Long" -> node.setProperty(name, Long.parseLong(value));
            case "Double" -> node.setProperty(name, Double.parseDouble(value));
            case "Name" -> node.setProperty(name, value, Type.NAME);
            // String, an absent type, and anything the engine has no use for: stored as written. A condition
            // operand's `value` is UNDEFINED in the node type precisely because the evaluator unifies types at
            // evaluation time rather than trusting what was stored.
            case null, default -> node.setProperty(name, value);
        }
    }

    private static Type<Iterable<String>> svMultipleType(final String declaredType)
    {
        return "Name".equals(declaredType) ? Type.NAMES : Type.STRINGS;
    }

    /**
     * One System View attribute, by namespace where the document kept it and by plain name where it did not.
     *
     * <p><strong>Measured, not defensive.</strong> {@code bpmn-moddle} — what the diagram editor reads and writes
     * with — preserves a foreign element's name and namespace across a round trip but flattens its <em>attribute</em>
     * prefixes: {@code sv:name="x"} is written back as {@code name="x"}. It only keeps the prefix on attributes it
     * has a schema for, which is why the neighbouring {@code xsi:type} survives and these do not. A parse that
     * insisted on the namespace would therefore read a hand-written diagram correctly and lose every condition in
     * one the editor had saved — quietly, since the flow node itself would still be derived.</p>
     *
     * <p>Reading both costs nothing in ambiguity: these attributes are only ever looked for on an {@code sv:node}
     * or {@code sv:property}, where an unprefixed {@code name} cannot mean anything else.</p>
     *
     * @param element the System View element to read
     * @param localName the attribute wanted
     * @return its value, or the empty string when the element carries it under neither form
     */
    private static String svAttribute(final Element element, final String localName)
    {
        final String qualified = element.getAttributeNS(SV_NS, localName);
        return StringUtils.isNotBlank(qualified) ? qualified : element.getAttribute(localName);
    }

    private static Stream<Element> svProperties(final Element svNode)
    {
        return childElements(svNode, SV_NS, SV_PROPERTY);
    }

    private static List<String> svValues(final Element svProperty)
    {
        return childElements(svProperty, SV_NS, SV_VALUE).map(Element::getTextContent).toList();
    }

    /**
     * The message names a document declares, by the id events point at them with.
     *
     * @param process the process element, used to reach the document it belongs to
     * @return message id to message name, empty when the document declares none
     */
    private static Map<String, String> messageNames(final Element process)
    {
        final Map<String, String> names = new LinkedHashMap<>();
        final NodeList messages = process.getOwnerDocument().getElementsByTagNameNS(BPMN_NS, MESSAGE_ELEMENT);
        for (int i = 0; i < messages.getLength(); ++i) {
            final Element message = (Element) messages.item(i);
            names.put(message.getAttribute(ID_ATTRIBUTE), message.getAttribute(NAME_ATTRIBUTE));
        }
        return names;
    }

    /**
     * The first direct child with this BPMN local name.
     *
     * @param element the element to look under
     * @param localName the BPMN local name wanted
     * @return that child, or {@code null} if the element has none
     */
    private static Element childElement(final Element element, final String localName)
    {
        return childElements(element, BPMN_NS, localName).findFirst().orElse(null);
    }

    /**
     * The direct children of an element in one namespace, matched by namespace since a prefix is the document's
     * choice.
     *
     * @param element the element to look under
     * @param namespace the namespace wanted
     * @return those children, in document order
     */
    private static Stream<Element> childElements(final Element element, final String namespace)
    {
        final NodeList children = element.getChildNodes();
        return IntStream.range(0, children.getLength())
            .mapToObj(children::item)
            .filter(child -> child instanceof Element candidate && namespace.equals(candidate.getNamespaceURI()))
            .map(Element.class::cast);
    }

    /**
     * The direct children of an element with one name in one namespace.
     *
     * @param element the element to look under
     * @param namespace the namespace wanted
     * @param localName the local name wanted
     * @return those children, in document order
     */
    static Stream<Element> childElements(final Element element, final String namespace, final String localName)
    {
        return childElements(element, namespace).filter(child -> localName.equals(child.getLocalName()));
    }

    /**
     * Writes what identifies the node, last: the vocabulary supplies defaults, but the diagram is the authority on
     * which element this is and what it is called, so no configured property may overwrite these.
     */
    private static void applyIdentity(final NodeBuilder node, final Element element, final String id,
        final FlowNodeTypeInfo flowNodeType)
    {
        node.setProperty(ELEMENT_ID_PROPERTY, id);
        node.setProperty(FLOW_NODE_TYPE_PROPERTY, flowNodeType.identifier(), Type.REFERENCE);
        setIfNotBlank(node, LABEL_PROPERTY, element.getAttribute(NAME_ATTRIBUTE));
    }

    /**
     * Stores a {@code bpmn:boundaryEvent} as a child of the activity its {@code attachedToRef} names. Where the event
     * is stored is the only thing that distinguishes a boundary event from a free-standing mid-process catch, so an
     * event whose {@code attachedToRef} is missing or names a node that was not created is dropped rather than
     * silently demoted to the other kind.
     */
    private static void createBoundaryEvent(final Element element,
        final Map<String, List<FlowNodeTypeInfo>> flowNodeTypes, final ParseContext context)
    {
        final String id = element.getAttribute(ID_ATTRIBUTE);
        final String attachedToRef = element.getAttribute(ATTACHED_TO_REF_ATTRIBUTE);
        if (StringUtils.isBlank(attachedToRef)) {
            LOGGER.warn("BoundaryEvent {} in {} has no attachedToRef, skipping", id, context.path());
            context.problems().report("boundary event has no attachedToRef",
                context.problems().context()
                    .with(ELEMENT_DETAIL, id));
            return;
        }
        // Only a node this parse produced will do: any other existing child of the version, bpmn.xml above all,
        // would take a child no later reparse could ever reclaim.
        if (!context.claimedNames().contains(attachedToRef)) {
            LOGGER.warn("BoundaryEvent {} in {} is attached to unknown node {}, skipping", id, context.path(),
                attachedToRef);
            context.problems().report("boundary event is attached to an unknown node",
                context.problems().context()
                    .with(ELEMENT_DETAIL, id).with("attachedTo", attachedToRef));
            return;
        }
        final FlowNodeTypeInfo flowNodeType =
            matchFlowNodeType(element, BOUNDARY_EVENT_ELEMENT, flowNodeTypes.get(BOUNDARY_EVENT_ELEMENT),
                context);
        if (flowNodeType == null) {
            return;
        }
        final NodeBuilder activity = context.workflowVersion().getChildNode(attachedToRef);
        final NodeBuilder node = createNode(activity, id, flowNodeType.jcrNodeType(), context);
        if (node == null) {
            return;
        }
        applyParameters(flowNodeType, element, node, context);
        applyJcrProperties(flowNodeType, node, context);
        applyEventDefinitions(element, node, context);
        applyIdentity(node, element, id, flowNodeType);
        // The BPMN attribute and the JCR property share both name-in-spirit and default (true), so an absent
        // attribute correctly leaves the node type's own default in place.
        final String cancelActivity = element.getAttribute(CANCEL_ACTIVITY_ATTRIBUTE);
        if (StringUtils.isNotBlank(cancelActivity)) {
            node.setProperty(INTERRUPTING_PROPERTY, Boolean.parseBoolean(cancelActivity));
        }
        applySystemProperties(node, flowNodeType.jcrNodeType(), context);
    }

    /**
     * Creates a node for a parsed element and claims its name. Returns {@code null} rather than a node when the
     * element's {@code id} cannot be used as a JCR node name, when it names a child that already exists for another
     * reason — {@code bpmn.xml} above all, which {@code NodeBuilder.child} would hand back for this parse to retype
     * and the next one to delete — or when a previous element in the same diagram already claimed it, since
     * {@code child} would otherwise merge the two into one node carrying properties of both.
     */
    private static NodeBuilder createNode(final NodeBuilder parent, final String id, final String nodeType,
        final ParseContext context)
    {
        if (!isValidName(id)) {
            LOGGER.warn("BPMN element id {} in {} is not usable as a JCR node name, skipping", id, context.path());
            context.problems().report("BPMN element id is not usable as a node name",
                context.problems().context()
                    .with(ELEMENT_DETAIL, id));
            return null;
        }
        if (context.createdNodes().containsKey(id)) {
            LOGGER.warn("BPMN element id {} in {} is used more than once, skipping the later element", id,
                context.path());
            context.problems().report("BPMN element id is used more than once",
                context.problems().context()
                    .with(ELEMENT_DETAIL, id));
            return null;
        }
        if (parent.hasChildNode(id) && !isFlowNodeOrSequenceFlow(parent.getChildNode(id), context.nodeTypesRoot())) {
            LOGGER.warn("BPMN element id {} in {} collides with an existing non-flow-node child, skipping", id,
                context.path());
            context.problems().report("BPMN element id collides with an existing child",
                context.problems().context()
                    .with(ELEMENT_DETAIL, id));
            return null;
        }
        final NodeBuilder node = parent.child(id);
        node.setProperty(JCR_PRIMARY_TYPE, nodeType, Type.NAME);
        context.createdNodes().put(id, node);
        return node;
    }

    /**
     * Whether {@code name} can be stored as a JCR node name. Oak's own name validation never sees nodes an editor
     * adds to the commit's builder, so an unusable name would otherwise be persisted unaddressable, or abort the
     * whole commit from inside {@code NodeBuilder.child}.
     */
    private static boolean isValidName(final String name)
    {
        return name.equals(name.trim()) && !RELATIVE_PATH_NAMES.contains(name)
            && name.chars().noneMatch(c -> INVALID_NAME_CHARACTERS.indexOf(c) >= 0);
    }

    private static void setIfNotBlank(final NodeBuilder node, final String name, final String value)
    {
        if (StringUtils.isNotBlank(value)) {
            node.setProperty(name, value);
        }
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
        final ParseContext context)
    {
        node.setProperty(JCR_CREATED_PROPERTY, context.created(), Type.DATE);
        node.setProperty(JCR_CREATED_BY_PROPERTY, context.author());
        node.setProperty(SLING_RESOURCE_TYPE_PROPERTY, toResourceType(primaryType));
        final String supertype = directSupertype(primaryType, context.nodeTypesRoot());
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
     * Picks the most specific {@code wf:FlowNodeType} matching the given element out of the candidates that match its
     * {@code xmlElement}. These are already sorted from highest to lowest priority, and by name within one. A candidate
     * with an {@code xmlChildElement} only matches when the element has a direct BPMN child of that name. One with an
     * {@code xmlExtensionElement} only matches when its {@code extensionElements} holds that IAP element, which is what
     * tells one service task handler from another. A candidate requiring neither always matches, which is how a task
     * naming no handler element falls back on the generic entry. A matching candidate that could not produce a valid
     * node is reported and passed over for the next one, so one half-authored vocabulary entry cannot break every save.
     */
    private static FlowNodeTypeInfo matchFlowNodeType(final Element element, final String localName,
        final List<FlowNodeTypeInfo> candidates, final ParseContext context)
    {
        final String id = element.getAttribute(ID_ATTRIBUTE);
        final Set<String> childNames =
            childElements(element, BPMN_NS).map(Element::getLocalName).collect(Collectors.toSet());
        final Set<String> extensionNames = iapExtensionNames(element);
        for (final FlowNodeTypeInfo candidate : candidates == null ? List.<FlowNodeTypeInfo>of() : candidates) {
            if (candidate.xmlChildElement() != null && !childNames.contains(candidate.xmlChildElement())
                || candidate.xmlExtensionElement() != null
                    && !extensionNames.contains(candidate.xmlExtensionElement())) {
                continue;
            }
            if (StringUtils.isBlank(candidate.jcrNodeType()) || StringUtils.isBlank(candidate.identifier())) {
                LOGGER.warn("FlowNodeType {} matching BPMN element {} in {} declares no jcrNodeType or is not"
                    + " referenceable, passing it over", candidate.name(), id, context.path());
                // A gap in the platform's own vocabulary rather than in the author's file, and the one of these
                // that nothing else validates
                context.problems().report("FlowNodeType declares no jcrNodeType",
                    context.problems().context().with("bpmnElement", localName).with(ELEMENT_DETAIL, id)
                        .with(FLOW_NODE_TYPE_DETAIL, candidate.name()));
                continue;
            }
            return candidate;
        }
        LOGGER.warn("No FlowNodeType configured for BPMN element <{}> (id={}) in {}, skipping", localName, id,
            context.path());
        context.problems().report("no FlowNodeType configured for a BPMN element",
            context.problems().context().with("bpmnElement", localName).with(ELEMENT_DETAIL, id));
        return null;
    }

    /**
     * The IAP-namespaced element of one name inside a BPMN element's {@code extensionElements}, which is where an
     * entry's own configuration is written: {@code <iap:addTag tag="active"/>} says both that this
     * service task is that entry's and what it was configured with.
     *
     * @param element the BPMN element being translated
     * @param localName the extension element wanted, without a prefix
     * @return that element, or {@code null} when the BPMN element carries no such extension
     */
    static Element extensionElement(final Element element, final String localName)
    {
        return Optional.ofNullable(extensions(element))
            .flatMap(extensions -> childElements(extensions, IAP_NS, localName).findFirst())
            .orElse(null);
    }

    /**
     * A BPMN element's {@code extensionElements}, where everything BPMN has no word for is written.
     *
     * @param element the BPMN element being translated
     * @return its {@code extensionElements} child, or {@code null} when it has none
     */
    private static Element extensions(final Element element)
    {
        return childElement(element, EXTENSION_ELEMENTS);
    }

    /** The local names of the IAP elements in an element's {@code extensionElements}. */
    private static Set<String> iapExtensionNames(final Element element)
    {
        final Element extensions = extensions(element);
        return extensions == null ? Set.of()
            : childElements(extensions, IAP_NS).map(Element::getLocalName).collect(Collectors.toSet());
    }

    /**
     * Sets the fixed property values configured in the FlowNodeType's {@code jcrProperties}, a JSON object as
     * {@code workflowTypes.cnd} declares it and as {@code FlowNodeType.getJcrProperties()} reads it. The JSON types
     * carry over: a boolean stays a boolean, an integral number a long, and a quoted value stays a string even when
     * it looks numeric.
     */
    private static void applyJcrProperties(final FlowNodeTypeInfo flowNodeType, final NodeBuilder node,
        final ParseContext context)
    {
        final JsonObject properties = flowNodeType.jcrProperties();
        if (properties == null) {
            LOGGER.warn("FlowNodeType {} in {} declares jcrProperties that are not a JSON object, ignoring them",
                flowNodeType.identifier(), context.path());
            context.problems().report("FlowNodeType declares jcrProperties that are not a JSON object",
                context.problems().context()
                    .with("flowNodeType", flowNodeType.identifier()));
            return;
        }
        properties.forEach((name, value) -> setJsonProperty(node, name, value, context));
    }

    private static void setJsonProperty(final NodeBuilder node, final String name, final JsonValue value,
        final ParseContext context)
    {
        switch (value.getValueType()) {
            case TRUE -> node.setProperty(name, true);
            case FALSE -> node.setProperty(name, false);
            case NUMBER -> setNumberProperty(node, name, (JsonNumber) value);
            case STRING -> node.setProperty(name, ((JsonString) value).getString());
            default -> {
                LOGGER.warn("Ignoring jcrProperties entry {}, {} values are not supported", name,
                    value.getValueType());
                context.problems().report("jcrProperties entry holds an unsupported value type",
                    context.problems().context()
                        .with("property", name).with("valueType", value.getValueType()));
            }
        }
    }

    private static void setNumberProperty(final NodeBuilder node, final String name, final JsonNumber value)
    {
        if (value.isIntegral()) {
            node.setProperty(name, value.longValue());
        } else {
            node.setProperty(name, value.doubleValue());
        }
    }

    /**
     * Applies the typed {@code wf:Parameter} children of the matched vocabulary entry, each read from wherever it
     * says a diagram writes it; see {@link ParameterTranslation}.
     */
    private static void applyParameters(final FlowNodeTypeInfo flowNodeType, final Element element,
        final NodeBuilder node, final ParseContext context)
    {
        ParameterTranslation.applyParameters(flowNodeType, element, node, context.root(), context.nodeTypesRoot(),
            context.problems());
    }

    private static void createSequenceFlow(final Element element, final Map<String, Element> elementsById,
        final ParseContext context)
    {
        final String id = element.getAttribute(ID_ATTRIBUTE);
        final String sourceRef = element.getAttribute("sourceRef");
        final String targetRef = element.getAttribute(TARGET_REF_PROPERTY);
        if (StringUtils.isBlank(sourceRef) || StringUtils.isBlank(targetRef)) {
            LOGGER.warn("SequenceFlow {} is missing sourceRef or targetRef, skipping", id);
            context.problems().report("sequence flow is missing sourceRef or targetRef",
                context.problems().context()
                    .with(ELEMENT_DETAIL, id));
            return;
        }
        // Both ends must name a node this parse produced: an arc stored under some other existing child of the
        // version could never be reclaimed by a reparse, and one pointing at a node that does not exist is an arc
        // the engine cannot follow, which the mandatory targetRef promises it never has to.
        if (!context.claimedNames().contains(sourceRef)) {
            LOGGER.warn("SequenceFlow {} references unknown source node {}, skipping", id, sourceRef);
            context.problems().report("sequence flow references an unknown node",
                context.problems().context()
                    .with(ELEMENT_DETAIL, id).with("references", sourceRef));
            return;
        }
        if (!context.claimedNames().contains(targetRef)) {
            LOGGER.warn("SequenceFlow {} references unknown target node {}, skipping", id, targetRef);
            context.problems().report("sequence flow references an unknown node",
                context.problems().context()
                    .with(ELEMENT_DETAIL, id).with("references", targetRef));
            return;
        }
        // Not `workflowVersion().getChildNode(sourceRef)`: a boundary event is stored inside the activity it
        // watches, so an arc leaving one — "and if nobody decides in time, go here" — has a source that is not a
        // child of the version at all. Asking the parse where it put the node is the only reliable answer.
        final NodeBuilder source = context.createdNodes().get(sourceRef);
        final NodeBuilder flow = createNode(source, id, SEQUENCE_FLOW_NODETYPE, context);
        if (flow == null) {
            return;
        }
        flow.setProperty(ELEMENT_ID_PROPERTY, id);
        flow.setProperty(TARGET_REF_PROPERTY, targetRef);
        setIfNotBlank(flow, LABEL_PROPERTY, element.getAttribute(NAME_ATTRIBUTE));
        setIfNotBlank(flow, CONDITION_EXPRESSION_PROPERTY, getConditionExpression(element));
        applyExtensionNodes(element, flow, context);
        final Element sourceElement = elementsById.get(sourceRef);
        if (sourceElement != null && id.equals(sourceElement.getAttribute("default"))) {
            flow.setProperty("isDefault", true);
        }
        applySystemProperties(flow, SEQUENCE_FLOW_NODETYPE, context);
    }

    private static String getConditionExpression(final Element sequenceFlow)
    {
        // Only a direct child counts: a conditionExpression buried in extensionElements belongs to whatever
        // extension put it there, not to this arc.
        final Element condition = childElement(sequenceFlow, CONDITION_EXPRESSION_PROPERTY);
        return condition == null ? null : condition.getTextContent().trim();
    }
}

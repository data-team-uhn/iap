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
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
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
 * property; a candidate missing the {@code jcrNodeType} or the identity that a stored node needs is reported and
 * skipped, since the residual child and property definitions in {@code workflowTypes.cnd} let any node under
 * {@code /WorkflowTypes} carry an {@code xmlElement}, including a half-authored one.</p>
 *
 * <p>When several configured types match the same XML element (e.g. a plain start event versus a message start
 * event), the one with the highest {@code priority} whose {@code xmlChildElement} requirement (if any) is
 * satisfied by the element is used.</p>
 *
 * <p>Two BPMN elements say where they are stored rather than being stored where they are written, so they are
 * handled in a second pass once every other flow node exists: a {@code bpmn:sequenceFlow} becomes a
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
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowDefinitionUtils.class);

    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    private static final String PROCESS_ELEMENT = "process";

    private static final String SEQUENCE_FLOW_ELEMENT = "sequenceFlow";

    private static final String BOUNDARY_EVENT_ELEMENT = "boundaryEvent";

    private static final String SEQUENCE_FLOW_NODETYPE = "wf:SequenceFlow";

    private static final String FLOW_NODE_NODETYPE = "wf:FlowNode";

    private static final String SUPERTYPES_PROPERTY = "rep:supertypes";

    private static final String NAME_ATTRIBUTE = "name";

    private static final String ATTACHED_TO_REF_ATTRIBUTE = "attachedToRef";

    private static final String CANCEL_ACTIVITY_ATTRIBUTE = "cancelActivity";

    private static final String INTERRUPTING_PROPERTY = "interrupting";

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
     * @since 0.1.0
     */
    private record ParseContext(NodeBuilder workflowVersion, NodeState nodeTypesRoot, String author, String created,
        String path, Set<String> claimedNames)
    {
        ParseContext(final NodeBuilder workflowVersion, final NodeState nodeTypesRoot, final String author,
            final String created, final String path)
        {
            this(workflowVersion, nodeTypesRoot, author, created, path, new HashSet<>());
        }
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
    public static void parse(@NotNull final InputStream bpmnXml, @NotNull final NodeBuilder workflowVersion,
        @NotNull final NodeState workflowTypesRoot, @NotNull final NodeState nodeTypesRoot,
        @NotNull final String author, @NotNull final String workflowVersionPath)
        throws ParserConfigurationException, SAXException, IOException
    {
        final Element process = getProcessElement(bpmnXml, workflowVersionPath);
        clear(workflowVersion, nodeTypesRoot);
        if (process == null) {
            return;
        }
        // One timestamp for the whole batch: every node in it was created by the same parse, and differing
        // millisecond values would suggest otherwise.
        final ParseContext context = new ParseContext(workflowVersion, nodeTypesRoot, author,
            ISO8601.format(Calendar.getInstance()), workflowVersionPath);
        final Map<String, List<FlowNodeTypeInfo>> flowNodeTypes = loadFlowNodeTypes(workflowTypesRoot);
        final Map<String, Element> elementsById = new LinkedHashMap<>();
        final List<Element> sequenceFlows = new ArrayList<>();
        final List<Element> boundaryEvents = new ArrayList<>();

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

    private static Element getProcessElement(final InputStream bpmnXml, final String workflowVersionPath)
        throws ParserConfigurationException, SAXException, IOException
    {
        final Document document = parseXml(bpmnXml);
        final NodeList processes = document.getElementsByTagNameNS(BPMN_NS, PROCESS_ELEMENT);
        if (processes.getLength() == 0) {
            LOGGER.warn("No <bpmn:process> element found in bpmnXml at {}", workflowVersionPath);
            return null;
        }
        if (processes.getLength() > 1) {
            LOGGER.warn("bpmnXml at {} declares {} <bpmn:process> elements, only the first one is parsed",
                workflowVersionPath, processes.getLength());
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
        final Map<String, List<FlowNodeTypeInfo>> flowNodeTypes, final ParseContext context)
    {
        final FlowNodeTypeInfo flowNodeType = matchFlowNodeType(element, localName, flowNodeTypes.get(localName));
        if (flowNodeType == null) {
            return;
        }
        final NodeBuilder node = createNode(context.workflowVersion(), id, flowNodeType.jcrNodeType(), context);
        if (node == null) {
            return;
        }
        applyJcrProperties(flowNodeType, node, context);
        applyCopiedProperties(flowNodeType, element, node);
        applyIdentity(node, element, id, flowNodeType);
        applySystemProperties(node, flowNodeType.jcrNodeType(), context);
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
            return;
        }
        // Only a node this parse produced will do: any other existing child of the version, bpmn.xml above all,
        // would take a child no later reparse could ever reclaim.
        if (!context.claimedNames().contains(attachedToRef)) {
            LOGGER.warn("BoundaryEvent {} in {} is attached to unknown node {}, skipping", id, context.path(),
                attachedToRef);
            return;
        }
        final FlowNodeTypeInfo flowNodeType =
            matchFlowNodeType(element, BOUNDARY_EVENT_ELEMENT, flowNodeTypes.get(BOUNDARY_EVENT_ELEMENT));
        if (flowNodeType == null) {
            return;
        }
        final NodeBuilder activity = context.workflowVersion().getChildNode(attachedToRef);
        final NodeBuilder node = createNode(activity, id, flowNodeType.jcrNodeType(), context);
        if (node == null) {
            return;
        }
        applyJcrProperties(flowNodeType, node, context);
        applyCopiedProperties(flowNodeType, element, node);
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
            return null;
        }
        if (!context.claimedNames().add(id)) {
            LOGGER.warn("BPMN element id {} in {} is used more than once, skipping the later element", id,
                context.path());
            return null;
        }
        if (parent.hasChildNode(id) && !isFlowNodeOrSequenceFlow(parent.getChildNode(id), context.nodeTypesRoot())) {
            LOGGER.warn("BPMN element id {} in {} collides with an existing non-flow-node child, skipping", id,
                context.path());
            return null;
        }
        final NodeBuilder node = parent.child(id);
        node.setProperty(JCR_PRIMARY_TYPE, nodeType, Type.NAME);
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
     * Picks the most specific {@code wf:FlowNodeType} matching the given element out of the candidates that match
     * its {@code xmlElement}, already sorted from highest to lowest priority. A candidate with an
     * {@code xmlChildElement} only matches when the element has a direct child of that name; a candidate without
     * one always matches. Candidates that could not produce a valid node are skipped, so one half-authored
     * vocabulary entry cannot break every save.
     */
    private static FlowNodeTypeInfo matchFlowNodeType(final Element element, final String localName,
        final List<FlowNodeTypeInfo> candidates)
    {
        if (candidates == null) {
            LOGGER.warn("No FlowNodeType configured for BPMN element <{}> (id={}), skipping", localName,
                element.getAttribute(ID_ATTRIBUTE));
            return null;
        }
        final FlowNodeTypeInfo match = candidates.stream()
            .filter(candidate -> candidate.xmlChildElement() == null
                || hasChildElement(element, candidate.xmlChildElement()))
            .findFirst()
            .orElse(null);
        if (match == null) {
            LOGGER.warn("No FlowNodeType configured for BPMN element <{}> (id={}), skipping", localName,
                element.getAttribute(ID_ATTRIBUTE));
            return null;
        }
        if (StringUtils.isBlank(match.jcrNodeType()) || StringUtils.isBlank(match.identifier())) {
            LOGGER.warn("FlowNodeType matching BPMN element <{}> declares no jcrNodeType or is not referenceable,"
                + " skipping element {}", localName, element.getAttribute(ID_ATTRIBUTE));
            return null;
        }
        return match;
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
    private static void applyJcrProperties(final FlowNodeTypeInfo flowNodeType, final NodeBuilder node,
        final ParseContext context)
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
        final ParseContext context)
    {
        final String id = element.getAttribute(ID_ATTRIBUTE);
        final String sourceRef = element.getAttribute("sourceRef");
        final String targetRef = element.getAttribute(TARGET_REF_PROPERTY);
        if (StringUtils.isBlank(sourceRef) || StringUtils.isBlank(targetRef)) {
            LOGGER.warn("SequenceFlow {} is missing sourceRef or targetRef, skipping", id);
            return;
        }
        // Both ends must name a node this parse produced: an arc stored under some other existing child of the
        // version could never be reclaimed by a reparse, and one pointing at a node that does not exist is an arc
        // the engine cannot follow, which the mandatory targetRef promises it never has to.
        if (!context.claimedNames().contains(sourceRef)) {
            LOGGER.warn("SequenceFlow {} references unknown source node {}, skipping", id, sourceRef);
            return;
        }
        if (!context.claimedNames().contains(targetRef)) {
            LOGGER.warn("SequenceFlow {} references unknown target node {}, skipping", id, targetRef);
            return;
        }
        final NodeBuilder source = context.workflowVersion().getChildNode(sourceRef);
        final NodeBuilder flow = createNode(source, id, SEQUENCE_FLOW_NODETYPE, context);
        if (flow == null) {
            return;
        }
        flow.setProperty(ELEMENT_ID_PROPERTY, id);
        flow.setProperty(TARGET_REF_PROPERTY, targetRef);
        setIfNotBlank(flow, LABEL_PROPERTY, element.getAttribute(NAME_ATTRIBUTE));
        setIfNotBlank(flow, CONDITION_EXPRESSION_PROPERTY, getConditionExpression(element));
        final Element sourceElement = elementsById.get(sourceRef);
        if (sourceElement != null && id.equals(sourceElement.getAttribute("default"))) {
            flow.setProperty("isDefault", true);
        }
        applySystemProperties(flow, SEQUENCE_FLOW_NODETYPE, context);
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

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

import java.util.List;

import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;

/**
 * The {@code /jcr:system/jcr:nodeTypes} registry a BPMN parse reads, as a plain builder tree.
 *
 * <p>The parser asks the registry two questions, and both have to be answerable for a parse to produce the same
 * nodes it does in a running repository. {@code rep:supertypes}, the transitively-expanded list, is how a
 * previously parsed child is recognized as a flow node worth clearing. The flattened property and child node
 * definitions tell which parameters a node can hold, and what a created node is given by default. Shared so that a
 * test about the parser and a test about the shipped definitions agree on what the platform's own node types
 * are.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class NodeTypesFixture
{
    private static final String REP_SUPERTYPES = "rep:supertypes";

    private static final String NAMED_PROPERTY_DEFINITIONS = "rep:namedPropertyDefinitions";

    private static final String AUTO_CREATED = "jcr:autoCreated";

    private static final String DEFAULT_VALUES = "jcr:defaultValues";

    private static final String CONDITION_OPERAND = "cond:ConditionOperand";

    private static final String ENTITY_PART = "data:EntityPart";

    private static final String FLOW_NODE = "wf:FlowNode";

    private static final String EVENT = "wf:Event";

    private static final String INTERMEDIATE_EVENT = "wf:IntermediateEvent";

    private static final String GATEWAY = "wf:Gateway";

    private static final String FLOW_NODE_TYPE = "wf:FlowNodeType";

    private static final String NT_BASE = "nt:base";

    private NodeTypesFixture()
    {
        // Utility class, no instances allowed
    }

    /**
     * Registers every concrete {@code wf:FlowNode}/{@code wf:SequenceFlow} type a diagram can be parsed into, and
     * the {@code wf:FlowNodeType} subtypes the vocabulary is written in. It also registers the {@code nt:file} a
     * version's {@code bpmn.xml} is stored as, with no flow node in its supertypes, so that a reparse leaves it
     * alone.
     *
     * @param root the repository root builder to register them under
     */
    public static void register(final NodeBuilder root)
    {
        final NodeBuilder nodeTypes = root.child("jcr:system").child("jcr:nodeTypes");
        type(nodeTypes, "wf:StartEvent", List.of(EVENT, FLOW_NODE), EVENT);
        type(nodeTypes, "wf:EndEvent", List.of(EVENT, FLOW_NODE), EVENT);
        type(nodeTypes, "wf:IntermediateCatchingEvent", List.of(INTERMEDIATE_EVENT, EVENT, FLOW_NODE),
            INTERMEDIATE_EVENT);
        type(nodeTypes, "wf:IntermediateThrowingEvent", List.of(INTERMEDIATE_EVENT, EVENT, FLOW_NODE),
            INTERMEDIATE_EVENT);
        type(nodeTypes, "wf:Activity", List.of(FLOW_NODE), FLOW_NODE);
        type(nodeTypes, "wf:ExclusiveGateway", List.of(GATEWAY, FLOW_NODE), GATEWAY);
        type(nodeTypes, "wf:InclusiveGateway", List.of(GATEWAY, FLOW_NODE), GATEWAY);
        type(nodeTypes, "wf:ParallelGateway", List.of(GATEWAY, FLOW_NODE), GATEWAY);
        type(nodeTypes, "wf:EventBasedGateway", List.of(GATEWAY, FLOW_NODE), GATEWAY);
        // The kinds of vocabulary entry, recognized as entries by what they extend
        for (final String entry : List.of("wf:CatchingEventType", "wf:ThrowingEventType", "wf:ActivityType",
            "wf:GatewayType", "wf:SequenceFlowType")) {
            type(nodeTypes, entry, List.of(FLOW_NODE_TYPE), FLOW_NODE_TYPE);
        }
        type(nodeTypes, "wf:SequenceFlow", List.of(ENTITY_PART), ENTITY_PART);
        // What a condition written into a diagram is built of
        type(nodeTypes, "cond:ConditionGroup", List.of("cond:Condition"), "cond:Condition");
        type(nodeTypes, "cond:SingleCondition", List.of("cond:Condition"), "cond:Condition")
            .child("rep:namedChildNodeDefinitions").child("operandB").child(CONDITION_OPERAND)
            .setProperty(AUTO_CREATED, true).setProperty("jcr:defaultPrimaryType", CONDITION_OPERAND, Type.NAME);
        autoCreated(type(nodeTypes, CONDITION_OPERAND, List.of(ENTITY_PART), ENTITY_PART), "source", "literal");
        nodeTypes.child("nt:file").setProperty(REP_SUPERTYPES, List.of("nt:hierarchyNode"), Type.NAMES);
        // What a structure is stored as when it names no node type, and what a repeated one's records sit in
        acceptingAnyChild(acceptingAnyProperty(nodeTypes.child("nt:unstructured")
            .setProperty(REP_SUPERTYPES, List.of(NT_BASE), Type.NAMES)));
        acceptingAnyChild(acceptingAnyProperty(nodeTypes.child("sling:Folder")
            .setProperty(REP_SUPERTYPES, List.of("nt:folder", "nt:hierarchyNode", NT_BASE), Type.NAMES)));
    }

    /**
     * Registers one more node type, giving only its transitive supertypes.
     *
     * @param root the repository root builder
     * @param name the node type's name
     * @param supertypes every type it extends, directly or not
     * @return the node type's definition builder
     */
    public static NodeBuilder type(final NodeBuilder root, final String name, final String... supertypes)
    {
        return root.child("jcr:system").child("jcr:nodeTypes").child(name)
            .setProperty(REP_SUPERTYPES, List.of(supertypes), Type.NAMES);
    }

    /** Registers a node type autocreating what every content node type in the platform's CNDs does. */
    private static NodeBuilder type(final NodeBuilder nodeTypes, final String name, final List<String> supertypes,
        final String directSupertype)
    {
        final NodeBuilder type = acceptingAnyChild(acceptingAnyProperty(nodeTypes.child(name)
            .setProperty(REP_SUPERTYPES, supertypes, Type.NAMES)));
        autoCreated(type, "sling:resourceType", name.replace(':', '/'));
        autoCreated(type, "sling:resourceSuperType", directSupertype.replace(':', '/'));
        type.child(NAMED_PROPERTY_DEFINITIONS).child("jcr:created").child("DATE").setProperty(AUTO_CREATED, true);
        type.child(NAMED_PROPERTY_DEFINITIONS).child("jcr:createdBy").child("STRING").setProperty(AUTO_CREATED, true);
        return type;
    }

    private static void autoCreated(final NodeBuilder type, final String name, final String value)
    {
        type.child(NAMED_PROPERTY_DEFINITIONS).child(name).child("STRING").setProperty(AUTO_CREATED, true)
            .setProperty(DEFAULT_VALUES, List.of(value), Type.STRINGS);
    }

    /**
     * Gives a node type the residual property definitions every flow node and sequence flow inherits, as Oak
     * flattens them. They allow any property, single or multiple, of any type.
     *
     * @param nodeType the node type's definition builder
     * @return the same builder
     */
    public static NodeBuilder acceptingAnyProperty(final NodeBuilder nodeType)
    {
        final NodeBuilder residual = nodeType.child("rep:residualPropertyDefinitions");
        residual.child("UNDEFINED");
        residual.child("UNDEFINEDS");
        return nodeType;
    }

    /**
     * Gives a node type the residual child node definition every flow node inherits, as Oak flattens it. It
     * allows a child of any name and any type.
     *
     * @param nodeType the node type's definition builder
     * @return the same builder
     */
    public static NodeBuilder acceptingAnyChild(final NodeBuilder nodeType)
    {
        nodeType.child("rep:residualChildNodeDefinitions").child(NT_BASE);
        return nodeType;
    }
}

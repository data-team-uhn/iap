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
 * nodes it does in a running repository: {@code rep:supertypes}, the transitively-expanded list, is how a
 * previously parsed child is recognized as a flow node worth clearing, and {@code jcr:supertypes}, the directly
 * declared one, is what a created node's {@code sling:resourceSuperType} is derived from. Shared so that a test
 * about the parser and a test about the shipped definitions agree on what the platform's own node types are.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class NodeTypesFixture
{
    private static final String REP_SUPERTYPES = "rep:supertypes";

    private static final String JCR_SUPERTYPES = "jcr:supertypes";

    private static final String FLOW_NODE = "wf:FlowNode";

    private static final String EVENT = "wf:Event";

    private static final String INTERMEDIATE_EVENT = "wf:IntermediateEvent";

    private static final String GATEWAY = "wf:Gateway";

    private static final String FLOW_NODE_TYPE = "wf:FlowNodeType";

    private NodeTypesFixture()
    {
        // Utility class, no instances allowed
    }

    /**
     * Registers every concrete {@code wf:FlowNode}/{@code wf:SequenceFlow} type a diagram can be parsed into, the
     * {@code wf:FlowNodeType} subtypes the vocabulary is written in, and the {@code nt:file} a version's
     * {@code bpmn.xml} is stored as, which is registered with no flow node in its supertypes precisely so that a
     * reparse leaves it alone.
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
        // A sequence flow is recognized by name rather than by supertype, so only what its resource super type is
        // derived from has to be declared.
        nodeTypes.child("wf:SequenceFlow").setProperty(JCR_SUPERTYPES, List.of("data:EntityPart"), Type.NAMES);
        nodeTypes.child("nt:file").setProperty(REP_SUPERTYPES, List.of("nt:hierarchyNode"), Type.NAMES);
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

    private static void type(final NodeBuilder nodeTypes, final String name, final List<String> supertypes,
        final String directSupertype)
    {
        nodeTypes.child(name)
            .setProperty(REP_SUPERTYPES, supertypes, Type.NAMES)
            .setProperty(JCR_SUPERTYPES, List.of(directSupertype), Type.NAMES);
    }
}

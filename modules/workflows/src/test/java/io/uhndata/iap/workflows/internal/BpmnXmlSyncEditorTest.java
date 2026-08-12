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
package io.uhndata.iap.workflows.internal;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EditorHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BpmnXmlSyncEditor} and {@link BpmnXmlSyncEditorProvider}, driving full commits through an
 * {@link EditorHook} the same way the repository does.
 *
 * @version $Id$
 * @since 0.1.0
 */
class BpmnXmlSyncEditorTest
{
    private static final String PRIMARY_TYPE = "jcr:primaryType";

    private static final String HASH_PROPERTY = "bpmnXmlParsedHash";

    private static final String WORKFLOWS_PATH = "Workflows";

    private static final String DEFINITION_NAME = "Approval";

    private static final String VERSION_NAME = "1.0";

    private static final String START_EVENT_XML =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" id=\"defs1\">\n"
        + "  <bpmn:process id=\"process1\">\n"
        + "    <bpmn:startEvent id=\"start1\" name=\"Start\"/>\n"
        + "    <bpmn:userTask id=\"task1\" name=\"Review\"/>\n"
        + "    <bpmn:endEvent id=\"end1\" name=\"End\"/>\n"
        + "    <bpmn:sequenceFlow id=\"flow1\" sourceRef=\"start1\" targetRef=\"task1\"/>\n"
        + "    <bpmn:sequenceFlow id=\"flow2\" sourceRef=\"task1\" targetRef=\"end1\"/>\n"
        + "  </bpmn:process>\n"
        + "</bpmn:definitions>\n";

    private static final String REPARSED_XML =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" id=\"defs1\">\n"
        + "  <bpmn:process id=\"process1\">\n"
        + "    <bpmn:startEvent id=\"onlyStart\" name=\"Only\"/>\n"
        + "  </bpmn:process>\n"
        + "</bpmn:definitions>\n";

    private static final String NO_PROCESS_XML =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" id=\"defs1\"/>\n";

    /**
     * Exercises: two candidates for the same element (priority ordering, matching with and without the required
     * child), an element type with no configured candidate at all, an element type whose only candidate's required
     * child is missing, an element missing its {@code id}, a non-BPMN-namespaced child, literal/copied property
     * application, and every {@code SequenceFlow} branch (missing refs, unknown source, name, condition, default).
     */
    private static final String RICH_XML =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" id=\"defs1\">\n"
        + "  <bpmn:process id=\"process1\">\n"
        + "    <other:note xmlns:other=\"urn:test\">hi</other:note>\n"
        + "    <bpmn:startEvent name=\"NoId\"/>\n"
        + "    <bpmn:startEvent id=\"start1\" name=\"Start\"/>\n"
        + "    <bpmn:startEvent id=\"msgStart1\" name=\"MsgStart\">\n"
        + "      <bpmn:messageEventDefinition/>\n"
        + "    </bpmn:startEvent>\n"
        + "    <bpmn:userTask id=\"task1\" name=\"Review\" priority=\"7\" assigneeExpression=\"mgr\"\n"
        + "        default=\"flow2\"/>\n"
        + "    <bpmn:intermediateCatchEvent id=\"ice1\">\n"
        + "      <bpmn:timerEventDefinition/>\n"
        + "    </bpmn:intermediateCatchEvent>\n"
        + "    <bpmn:exclusiveGateway id=\"gw1\"/>\n"
        + "    <bpmn:endEvent id=\"end1\" name=\"End\"/>\n"
        + "    <bpmn:endEvent id=\"end2\"/>\n"
        + "    <bpmn:sequenceFlow id=\"flow1\" sourceRef=\"start1\" targetRef=\"task1\" name=\"Proceed\">\n"
        + "      <bpmn:conditionExpression> </bpmn:conditionExpression>\n"
        + "    </bpmn:sequenceFlow>\n"
        + "    <bpmn:sequenceFlow id=\"flow2\" sourceRef=\"task1\" targetRef=\"end1\">\n"
        + "      <bpmn:conditionExpression>${approved}</bpmn:conditionExpression>\n"
        + "    </bpmn:sequenceFlow>\n"
        + "    <bpmn:sequenceFlow id=\"flowBad\" targetRef=\"end1\"/>\n"
        + "    <bpmn:sequenceFlow id=\"flowNoTarget\" sourceRef=\"start1\"/>\n"
        + "    <bpmn:sequenceFlow id=\"flowUnknown\" sourceRef=\"ghost\" targetRef=\"end1\"/>\n"
        + "  </bpmn:process>\n"
        + "</bpmn:definitions>\n";

    private final String startEventTypeId = UUID.randomUUID().toString();

    private final String messageStartEventTypeId = UUID.randomUUID().toString();

    private final String userTaskTypeId = UUID.randomUUID().toString();

    private final String endEventTypeId = UUID.randomUUID().toString();

    private final String messageIntermediateCatchEventTypeId = UUID.randomUUID().toString();

    /**
     * Builds the repository state before any {@code bpmn.xml} is saved: {@code /WorkflowTypes} fully configured,
     * and an empty {@code wf:WorkflowVersion} with no {@code bpmn.xml} child yet.
     */
    private NodeBuilder base()
    {
        final NodeBuilder root = EmptyNodeState.EMPTY_NODE.builder();

        final NodeBuilder types = root.child("WorkflowTypes");
        flowNodeType(types, "StartEvent", this.startEventTypeId, "bpmn:startEvent", null, "wf:StartEvent", 0);
        flowNodeType(types, "UserTask", this.userTaskTypeId, "bpmn:userTask", null, "wf:Activity", 0);
        flowNodeType(types, "EndEvent", this.endEventTypeId, "bpmn:endEvent", null, "wf:EndEvent", 0);

        // A minimal node type registry backing clearFlowNodes' is-a-FlowNode/SequenceFlow checks (rep:supertypes,
        // the full transitively-expanded list) and applySystemProperties' sling:resourceSuperType derivation
        // (jcr:supertypes, the directly-declared one): just enough for the concrete types this test suite
        // actually parses BPMN elements into.
        final NodeBuilder nodeTypes = root.child("jcr:system").child("jcr:nodeTypes");
        nodeTypes.child("wf:StartEvent")
            .setProperty("rep:supertypes", List.of("wf:Event", "wf:FlowNode"), Type.NAMES)
            .setProperty("jcr:supertypes", List.of("wf:Event"), Type.NAMES);
        nodeTypes.child("wf:EndEvent")
            .setProperty("rep:supertypes", List.of("wf:Event", "wf:FlowNode"), Type.NAMES)
            .setProperty("jcr:supertypes", List.of("wf:Event"), Type.NAMES);
        nodeTypes.child("wf:Activity")
            .setProperty("rep:supertypes", List.of("wf:FlowNode"), Type.NAMES)
            .setProperty("jcr:supertypes", List.of("wf:FlowNode"), Type.NAMES);
        nodeTypes.child("wf:IntermediateCatchingEvent")
            .setProperty("rep:supertypes", List.of("wf:IntermediateEvent", "wf:Event", "wf:FlowNode"), Type.NAMES)
            .setProperty("jcr:supertypes", List.of("wf:IntermediateEvent"), Type.NAMES);
        nodeTypes.child("wf:ExclusiveGateway")
            .setProperty("rep:supertypes", List.of("wf:Gateway", "wf:FlowNode"), Type.NAMES)
            .setProperty("jcr:supertypes", List.of("wf:Gateway"), Type.NAMES);
        nodeTypes.child("wf:SequenceFlow").setProperty("jcr:supertypes", List.of("iap:EntityPart"), Type.NAMES);

        descend(root, WORKFLOWS_PATH, DEFINITION_NAME).setProperty(PRIMARY_TYPE, "wf:WorkflowDefinition", Type.NAME);
        final NodeBuilder version = version(root);
        version.setProperty(PRIMARY_TYPE, "wf:WorkflowVersion", Type.NAME);
        version.setProperty("version", VERSION_NAME);

        return root;
    }

    /**
     * Builds on {@link #base()} with the extra {@code wf:FlowNodeType} candidates and property mappings exercised
     * by {@link #RICH_XML}.
     */
    private NodeBuilder richBase()
    {
        final NodeBuilder root = base();
        final NodeBuilder types = root.child("WorkflowTypes");
        flowNodeType(types, "MessageStartEvent", this.messageStartEventTypeId, "bpmn:startEvent",
            "bpmn:messageEventDefinition", "wf:StartEvent", 10);
        flowNodeType(types, "MessageIntermediateCatchEvent", this.messageIntermediateCatchEventTypeId,
            "bpmn:intermediateCatchEvent", "bpmn:messageEventDefinition", "wf:IntermediateCatchingEvent", 10);
        // Collected but never matched by any element in RICH_XML: exercises an xmlElement with no namespace
        // prefix, and a candidate with no configured priority at all.
        types.child("Incomplete").setProperty(PRIMARY_TYPE, "wf:FlowNodeType", Type.NAME);
        types.child("Incomplete").setProperty("xmlElement", "gateway");

        final NodeBuilder userTask = types.getChildNode("UserTask");
        userTask.setProperty("jcrProperties",
            "{catching: true, weight: 3, ratio: 1.5, label: ok, junk, : ignored, cancelled: false}");
        userTask.setProperty("properties",
            List.of("priority", "assigneeExpression=assignee", "missingAttr=ignored"), Type.STRINGS);
        types.getChildNode("EndEvent").setProperty("jcrProperties", "{}");
        return root;
    }

    private void flowNodeType(final NodeBuilder parent, final String name, final String uuid,
        final String xmlElement, final String xmlChildElement, final String jcrNodeType, final long priority)
    {
        final NodeBuilder type = parent.child(name);
        type.setProperty(PRIMARY_TYPE, "wf:FlowNodeType", Type.NAME);
        type.setProperty("jcr:uuid", uuid);
        type.setProperty("label", name);
        type.setProperty("priority", priority);
        type.setProperty("xmlElement", xmlElement);
        type.setProperty("jcrNodeType", jcrNodeType);
        if (xmlChildElement != null) {
            type.setProperty("xmlChildElement", xmlChildElement);
        }
    }

    private void setBpmnXml(final NodeBuilder root, final String xml)
    {
        final NodeBuilder file = version(root).child("bpmn.xml");
        file.setProperty(PRIMARY_TYPE, "nt:file", Type.NAME);
        final NodeBuilder content = file.child("jcr:content");
        content.setProperty(PRIMARY_TYPE, "nt:resource", Type.NAME);
        content.setProperty("jcr:data", xml);
        // An unrelated sibling property under jcr:content, to exercise the editor ignoring anything that isn't
        // jcr:data itself.
        content.setProperty("jcr:mimeType", "application/xml");
    }

    private NodeBuilder version(final NodeBuilder root)
    {
        return descend(root, WORKFLOWS_PATH, DEFINITION_NAME, VERSION_NAME);
    }

    private NodeState version(final NodeState root)
    {
        return descend(root, WORKFLOWS_PATH, DEFINITION_NAME, VERSION_NAME);
    }

    private NodeBuilder descend(final NodeBuilder root, final String... names)
    {
        NodeBuilder current = root;
        for (final String name : names) {
            current = current.child(name);
        }
        return current;
    }

    private NodeState descend(final NodeState root, final String... names)
    {
        NodeState current = root;
        for (final String name : names) {
            current = current.getChildNode(name);
        }
        return current;
    }

    private NodeState process(final NodeState before, final NodeBuilder after) throws CommitFailedException
    {
        return process(before, after, CommitInfo.EMPTY);
    }

    private NodeState process(final NodeState before, final NodeBuilder after, final CommitInfo info)
        throws CommitFailedException
    {
        final EditorHook hook = new EditorHook(new BpmnXmlSyncEditorProvider());
        return hook.processCommit(before, after.getNodeState(), info);
    }

    private static String sha256(final String input)
    {
        return DigestUtils.sha256Hex(input.getBytes(StandardCharsets.UTF_8));
    }

    private NodeBuilder withBpmnXml(final String xml)
    {
        final NodeBuilder root = base();
        setBpmnXml(root, xml);
        return root;
    }

    @Test
    void parsesNewBpmnXmlIntoFlowNodes() throws Exception
    {
        final NodeState before = base().getNodeState();
        final NodeBuilder after = before.builder();
        setBpmnXml(after, START_EVENT_XML);

        final NodeState result = process(before, after);
        final NodeState version = version(result);

        assertEquals(sha256(START_EVENT_XML), version.getProperty(HASH_PROPERTY).getValue(Type.STRING));

        final NodeState start = version.getChildNode("start1");
        assertTrue(start.exists());
        assertEquals("wf:StartEvent", start.getProperty(PRIMARY_TYPE).getValue(Type.NAME));
        assertEquals("start1", start.getProperty("elementId").getValue(Type.STRING));
        assertEquals("Start", start.getProperty("label").getValue(Type.STRING));
        assertEquals(this.startEventTypeId, start.getProperty("flowNodeType").getValue(Type.REFERENCE));

        final NodeState task = version.getChildNode("task1");
        assertTrue(task.exists());
        assertEquals("wf:Activity", task.getProperty(PRIMARY_TYPE).getValue(Type.NAME));
        assertEquals(this.userTaskTypeId, task.getProperty("flowNodeType").getValue(Type.REFERENCE));

        final NodeState end = version.getChildNode("end1");
        assertTrue(end.exists());
        assertEquals("wf:EndEvent", end.getProperty(PRIMARY_TYPE).getValue(Type.NAME));

        final NodeState flow1 = start.getChildNode("flow1");
        assertTrue(flow1.exists());
        assertEquals("wf:SequenceFlow", flow1.getProperty(PRIMARY_TYPE).getValue(Type.NAME));
        assertEquals("task1", flow1.getProperty("targetRef").getValue(Type.STRING));

        final NodeState flow2 = task.getChildNode("flow2");
        assertTrue(flow2.exists());
        assertEquals("end1", flow2.getProperty("targetRef").getValue(Type.STRING));
    }

    @Test
    void parsedFlowNodesAndSequenceFlowsGetSystemProperties() throws Exception
    {
        final NodeState before = base().getNodeState();
        final NodeBuilder after = before.builder();
        setBpmnXml(after, START_EVENT_XML);

        final NodeState result = process(before, after, new CommitInfo("session1", "alice"));
        final NodeState start = version(result).getChildNode("start1");

        assertTrue(start.hasProperty("jcr:created"));
        assertEquals("alice", start.getProperty("jcr:createdBy").getValue(Type.STRING));
        assertEquals("wf/StartEvent", start.getProperty("sling:resourceType").getValue(Type.STRING));
        assertEquals("wf/Event", start.getProperty("sling:resourceSuperType").getValue(Type.STRING));

        final NodeState flow1 = start.getChildNode("flow1");
        assertTrue(flow1.hasProperty("jcr:created"));
        assertEquals("alice", flow1.getProperty("jcr:createdBy").getValue(Type.STRING));
        assertEquals("wf/SequenceFlow", flow1.getProperty("sling:resourceType").getValue(Type.STRING));
        assertEquals("iap/EntityPart", flow1.getProperty("sling:resourceSuperType").getValue(Type.STRING));
    }

    @Test
    void parsedFlowNodesFallBackToOakUnknownCreatedByWithNoCommitUser() throws Exception
    {
        final NodeState before = base().getNodeState();
        final NodeBuilder after = before.builder();
        setBpmnXml(after, START_EVENT_XML);

        final NodeState result = process(before, after);
        final NodeState start = version(result).getChildNode("start1");

        assertEquals(CommitInfo.OAK_UNKNOWN, start.getProperty("jcr:createdBy").getValue(Type.STRING));
    }

    @Test
    void resavingIdenticalBpmnXmlIsANoOp() throws Exception
    {
        final NodeState synced = process(EmptyNodeState.EMPTY_NODE, withBpmnXml(START_EVENT_XML));

        final NodeBuilder after = synced.builder();
        setBpmnXml(after, START_EVENT_XML);

        final NodeState result = process(synced, after);
        assertEquals(version(synced), version(result));
    }

    @Test
    void unrelatedSiblingAdditionsDoNotTriggerReparse() throws Exception
    {
        final NodeState synced = process(EmptyNodeState.EMPTY_NODE, withBpmnXml(START_EVENT_XML));

        final NodeBuilder after = synced.builder();
        // Exercise the editor's descent into unrelated siblings at every stage: none of these should ever
        // trigger a reparse, and the JCR_CONTENT-stage editor has no children of interest at all.
        version(after).child("extraFlowNode");
        version(after).getChildNode("bpmn.xml").child("extraUnderFile");
        version(after).getChildNode("bpmn.xml").getChildNode("jcr:content").child("extraUnderContent");

        final NodeState result = process(synced, after);
        final NodeState before = version(synced);
        final NodeState afterState = version(result);
        assertEquals(before.getProperty(HASH_PROPERTY).getValue(Type.STRING),
            afterState.getProperty(HASH_PROPERTY).getValue(Type.STRING));
        assertTrue(afterState.getChildNode("start1").exists());
        assertTrue(afterState.getChildNode("extraFlowNode").exists());
    }

    @Test
    void malformedBpmnXmlLeavesPreviousFlowNodesAndHashUntouched() throws Exception
    {
        final NodeState synced = process(EmptyNodeState.EMPTY_NODE, withBpmnXml(START_EVENT_XML));

        final NodeBuilder after = synced.builder();
        setBpmnXml(after, "<not-well-formed");

        final NodeState result = process(synced, after);
        final NodeState version = version(result);

        assertEquals(sha256(START_EVENT_XML), version.getProperty(HASH_PROPERTY).getValue(Type.STRING));
        assertTrue(version.getChildNode("start1").exists());
        assertTrue(version.getChildNode("task1").exists());
        assertTrue(version.getChildNode("end1").exists());
    }

    @Test
    void unrelatedPropertyChangeDoesNotTriggerReparse() throws Exception
    {
        final NodeState synced = process(EmptyNodeState.EMPTY_NODE, withBpmnXml(START_EVENT_XML));

        final NodeBuilder after = synced.builder();
        version(after).setProperty("description", "Updated description");

        final NodeState result = process(synced, after);

        final NodeState before = version(synced);
        final NodeState afterState = version(result);
        assertEquals(before.getProperty(HASH_PROPERTY).getValue(Type.STRING),
            afterState.getProperty(HASH_PROPERTY).getValue(Type.STRING));
        assertTrue(afterState.getChildNode("start1").exists());
        assertFalse(before.hasProperty("description"));
        assertTrue(afterState.hasProperty("description"));
    }

    @Test
    void blankBpmnXmlIsIgnored() throws Exception
    {
        final NodeState before = base().getNodeState();
        final NodeBuilder after = before.builder();
        setBpmnXml(after, "   ");

        final NodeState result = process(before, after);
        final NodeState version = version(result);

        assertFalse(version.hasProperty(HASH_PROPERTY));
        assertFalse(version.getChildNode("start1").exists());
    }

    @Test
    void alreadyParsedHashSkipsReparseEvenOnFirstSave() throws Exception
    {
        final NodeState before = base().getNodeState();
        final NodeBuilder after = before.builder();
        version(after).setProperty(HASH_PROPERTY, sha256(START_EVENT_XML));
        setBpmnXml(after, START_EVENT_XML);

        final NodeState result = process(before, after);
        final NodeState version = version(result);

        assertFalse(version.getChildNode("start1").exists());
    }

    @Test
    void reparsingRemovesFlowNodesThatNoLongerExist() throws Exception
    {
        final NodeState synced = process(EmptyNodeState.EMPTY_NODE, withBpmnXml(START_EVENT_XML));

        final NodeBuilder after = synced.builder();
        setBpmnXml(after, REPARSED_XML);

        final NodeState result = process(synced, after);
        final NodeState version = version(result);

        assertFalse(version.getChildNode("start1").exists());
        assertFalse(version.getChildNode("task1").exists());
        assertFalse(version.getChildNode("end1").exists());
        assertTrue(version.getChildNode("onlyStart").exists());
    }

    @Test
    void deletingBpmnXmlClearsFlowNodesAndParsedHash() throws Exception
    {
        final NodeState synced = process(EmptyNodeState.EMPTY_NODE, withBpmnXml(START_EVENT_XML));

        final NodeBuilder after = synced.builder();
        version(after).getChildNode("bpmn.xml").remove();

        final NodeState result = process(synced, after);
        final NodeState version = version(result);

        assertFalse(version.hasProperty(HASH_PROPERTY));
        assertFalse(version.getChildNode("bpmn.xml").exists());
        assertFalse(version.getChildNode("start1").exists());
        assertFalse(version.getChildNode("task1").exists());
        assertFalse(version.getChildNode("end1").exists());
    }

    @Test
    void reparsingPreservesChildrenThatAreNotFlowNodesOrSequenceFlows() throws Exception
    {
        final NodeState synced = process(EmptyNodeState.EMPTY_NODE, withBpmnXml(START_EVENT_XML));

        final NodeBuilder after = synced.builder();
        version(after).child("extension").setProperty(PRIMARY_TYPE, "nt:unstructured", Type.NAME);
        setBpmnXml(after, REPARSED_XML);

        final NodeState result = process(synced, after);
        final NodeState version = version(result);

        assertFalse(version.getChildNode("start1").exists());
        assertTrue(version.getChildNode("onlyStart").exists());
        assertTrue(version.getChildNode("extension").exists());
    }

    @Test
    void bpmnXmlWithoutAProcessElementParsesToNoFlowNodes() throws Exception
    {
        final NodeState before = base().getNodeState();
        final NodeBuilder after = before.builder();
        setBpmnXml(after, NO_PROCESS_XML);

        final NodeState result = process(before, after);
        final NodeState version = version(result);

        assertEquals(sha256(NO_PROCESS_XML), version.getProperty(HASH_PROPERTY).getValue(Type.STRING));
        assertFalse(version.getChildNode("start1").exists());
    }

    @Test
    void richBpmnXmlExercisesEveryMatchingAndPropertyBranch() throws Exception
    {
        final NodeState before = richBase().getNodeState();
        final NodeBuilder after = before.builder();
        setBpmnXml(after, RICH_XML);

        final NodeState result = process(before, after);
        final NodeState version = version(result);

        // Plain start event: the message-requiring candidate doesn't match, falls back to the plain one.
        final NodeState start = version.getChildNode("start1");
        assertTrue(start.exists());
        assertEquals(this.startEventTypeId, start.getProperty("flowNodeType").getValue(Type.REFERENCE));

        // Start event with a message child: the higher-priority message candidate matches.
        final NodeState msgStart = version.getChildNode("msgStart1");
        assertTrue(msgStart.exists());
        assertEquals(this.messageStartEventTypeId, msgStart.getProperty("flowNodeType").getValue(Type.REFERENCE));

        // Copied/renamed attributes and literal jcrProperties.
        final NodeState task = version.getChildNode("task1");
        assertTrue(task.exists());
        assertEquals("7", task.getProperty("priority").getValue(Type.STRING));
        assertEquals("mgr", task.getProperty("assignee").getValue(Type.STRING));
        assertEquals(true, task.getProperty("catching").getValue(Type.BOOLEAN));
        assertEquals(3L, task.getProperty("weight").getValue(Type.LONG));
        assertEquals(1.5, task.getProperty("ratio").getValue(Type.DOUBLE));
        assertEquals("ok", task.getProperty("label").getValue(Type.STRING));
        assertFalse(task.hasProperty("missingAttr"));

        // An element with no id, and one with a completely unconfigured type, are both skipped.
        assertFalse(version.getChildNode("gw1").exists());
        // The only candidate for intermediateCatchEvent requires a child this element doesn't have, even though
        // it does have a (different) child.
        assertFalse(version.getChildNode("ice1").exists());

        // A matched element with no name attribute at all.
        final NodeState end2 = version.getChildNode("end2");
        assertTrue(end2.exists());
        assertFalse(end2.hasProperty("label"));

        // SequenceFlow branches: name, blank condition expression, condition expression, isDefault, missing
        // target, missing source, unknown source.
        final NodeState flow1 = start.getChildNode("flow1");
        assertEquals("Proceed", flow1.getProperty("label").getValue(Type.STRING));
        assertFalse(flow1.hasProperty("conditionExpression"));

        final NodeState flow2 = task.getChildNode("flow2");
        assertEquals("${approved}", flow2.getProperty("conditionExpression").getValue(Type.STRING));
        assertEquals(true, flow2.getProperty("isDefault").getValue(Type.BOOLEAN));

        assertFalse(task.getChildNode("flowBad").exists());
        assertFalse(start.getChildNode("flowNoTarget").exists());
        assertFalse(task.getChildNode("flowUnknown").exists());
    }

    @Test
    void arrayValuedJcrDataIsIgnored() throws Exception
    {
        final NodeState before = base().getNodeState();
        final NodeBuilder after = before.builder();
        final NodeBuilder file = version(after).child("bpmn.xml");
        file.setProperty(PRIMARY_TYPE, "nt:file", Type.NAME);
        final NodeBuilder content = file.child("jcr:content");
        content.setProperty(PRIMARY_TYPE, "nt:resource", Type.NAME);
        content.setProperty("jcr:data", List.of(START_EVENT_XML), Type.STRINGS);

        final NodeState result = process(before, after);
        final NodeState version = version(result);

        assertFalse(version.hasProperty(HASH_PROPERTY));
        assertFalse(version.getChildNode("start1").exists());
    }

    /**
     * Exercises {@code isFlowNodeOrSequenceFlow}'s two ways of settling the question without ever consulting
     * {@code rep:supertypes}: a child with no {@code jcr:primaryType} at all (not a flow node), and children whose
     * {@code jcr:primaryType} is exactly {@code wf:FlowNode}/{@code wf:SequenceFlow} themselves, not a subtype
     * (are).
     */
    @Test
    void reparsingHandlesChildrenWithNoPrimaryTypeOrAnExactFlowNodeOrSequenceFlowType() throws Exception
    {
        final NodeState synced = process(EmptyNodeState.EMPTY_NODE, withBpmnXml(START_EVENT_XML));

        final NodeBuilder after = synced.builder();
        version(after).child("noPrimaryTypeAtAll");
        version(after).child("bareFlowNode").setProperty(PRIMARY_TYPE, "wf:FlowNode", Type.NAME);
        version(after).child("bareSequenceFlow").setProperty(PRIMARY_TYPE, "wf:SequenceFlow", Type.NAME);
        setBpmnXml(after, REPARSED_XML);

        final NodeState result = process(synced, after);
        final NodeState version = version(result);

        assertTrue(version.getChildNode("noPrimaryTypeAtAll").exists());
        assertFalse(version.getChildNode("bareFlowNode").exists());
        assertFalse(version.getChildNode("bareSequenceFlow").exists());
        assertTrue(version.getChildNode("onlyStart").exists());
    }

    /**
     * Exercises {@code directSupertype}'s two null-returning paths: a {@code jcrNodeType} not registered under
     * {@code /jcr:system/jcr:nodeTypes} at all (no {@code jcr:supertypes} property to read), and one registered
     * with an empty {@code jcr:supertypes} (property present, but its value iterator has nothing to return).
     */
    @Test
    void directSupertypeHandlesMissingAndEmptySupertypesProperty() throws Exception
    {
        final NodeBuilder root = base();
        final NodeBuilder types = root.child("WorkflowTypes");
        flowNodeType(types, "NoSupertypeGateway", UUID.randomUUID().toString(), "bpmn:exclusiveGateway", null,
            "wf:NoSupertypeType", 0);
        flowNodeType(types, "EmptySupertypeGateway", UUID.randomUUID().toString(), "bpmn:parallelGateway", null,
            "wf:EmptySupertypeType", 0);
        // wf:NoSupertypeType is intentionally left unregistered under jcr:nodeTypes altogether.
        root.child("jcr:system").child("jcr:nodeTypes").child("wf:EmptySupertypeType")
            .setProperty("jcr:supertypes", List.of(), Type.NAMES);

        final String xml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" id=\"defs1\">\n"
            + "  <bpmn:process id=\"process1\">\n"
            + "    <bpmn:exclusiveGateway id=\"gwNoSuper\"/>\n"
            + "    <bpmn:parallelGateway id=\"gwEmptySuper\"/>\n"
            + "  </bpmn:process>\n"
            + "</bpmn:definitions>\n";

        final NodeState before = root.getNodeState();
        final NodeBuilder after = before.builder();
        setBpmnXml(after, xml);

        final NodeState result = process(before, after);
        final NodeState version = version(result);

        final NodeState gwNoSuper = version.getChildNode("gwNoSuper");
        assertTrue(gwNoSuper.exists());
        assertEquals("wf/NoSupertypeType", gwNoSuper.getProperty("sling:resourceType").getValue(Type.STRING));
        assertFalse(gwNoSuper.hasProperty("sling:resourceSuperType"));

        final NodeState gwEmptySuper = version.getChildNode("gwEmptySuper");
        assertTrue(gwEmptySuper.exists());
        assertFalse(gwEmptySuper.hasProperty("sling:resourceSuperType"));
    }
}

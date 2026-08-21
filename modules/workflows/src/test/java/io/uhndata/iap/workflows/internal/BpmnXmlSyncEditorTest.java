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
import java.util.ArrayList;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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

    private static final String AUTHORITATIVE = "bpmnAuthoritative";

    private static final String HANDLER = "handler";

    private static final String IAP_NS = "https://iap.uhndata.io/bpmn";

    private static final String WORKFLOWS_PATH = "Workflows";

    private static final String DEFINITION_NAME = "Approval";

    private static final String VERSION_NAME = "1.0";

    private static final String BPMN_XML = "bpmn.xml";

    private static final String JCR_DATA = "jcr:data";

    private static final String JCR_CONTENT = "jcr:content";

    private static final String START_1 = "start1";

    private static final String TASK_1 = "task1";

    private static final String END_1 = "end1";

    private static final String FLOW_1 = "flow1";

    private static final String FLOW_2 = "flow2";

    private static final String ONLY_START = "onlyStart";

    private static final String FLOW_NODE_TYPE = "flowNodeType";

    private static final String INTERRUPTING = "interrupting";

    private static final String RESOURCE_SUPER_TYPE = "sling:resourceSuperType";

    private static final String PROCESS_OPEN = "  <bpmn:process id=\"process1\">\n";

    private static final String PROCESS_CLOSE = "  </bpmn:process>\n";

    private static final String DEFS_OPEN =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" id=\"defs1\">\n";

    private static final String DEFS_CLOSE = "</bpmn:definitions>\n";

    private static final String START_EVENT_XML =
        DEFS_OPEN
        + PROCESS_OPEN
        + "    <bpmn:startEvent id=\"start1\" name=\"Start\"/>\n"
        + "    <bpmn:userTask id=\"task1\" name=\"Review\"/>\n"
        + "    <bpmn:endEvent id=\"end1\" name=\"End\"/>\n"
        + "    <bpmn:sequenceFlow id=\"flow1\" sourceRef=\"start1\" targetRef=\"task1\"/>\n"
        + "    <bpmn:sequenceFlow id=\"flow2\" sourceRef=\"task1\" targetRef=\"end1\"/>\n"
        + PROCESS_CLOSE
        + DEFS_CLOSE;

    private static final String REPARSED_XML =
        DEFS_OPEN
        + PROCESS_OPEN
        + "    <bpmn:startEvent id=\"onlyStart\" name=\"Only\"/>\n"
        + PROCESS_CLOSE
        + DEFS_CLOSE;

    private static final String NO_PROCESS_XML =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" id=\"defs1\"/>\n";

    /**
     * Exercises: two candidates for the same element (priority ordering, matching with and without the required
     * child), an element type with no configured candidate at all, an element type whose only candidate's required
     * child is missing, an element missing its {@code id}, a non-BPMN-namespaced child, literal/copied property
     * application, and every {@code SequenceFlow} branch (missing refs, unknown source, unknown target, name,
     * condition, default).
     */
    private static final String RICH_XML =
        DEFS_OPEN
        + PROCESS_OPEN
        + "    <other:note xmlns:other=\"urn:test\">hi</other:note>\n"
        + "    <bpmn:startEvent name=\"NoId\"/>\n"
        + "    <bpmn:startEvent id=\"start1\" name=\"Start\"/>\n"
        + "    <bpmn:startEvent id=\"msgStart1\" name=\"MsgStart\">\n"
        + "      <bpmn:messageEventDefinition/>\n"
        + "    </bpmn:startEvent>\n"
        + "    <bpmn:userTask id=\"task1\" name=\"Review\" priority=\"7\" assigneeExpression=\"mgr\"\n"
        + "        async=\"TRUE\" default=\"flow2\"/>\n"
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
        + "    <bpmn:sequenceFlow id=\"flowGhostTarget\" sourceRef=\"start1\" targetRef=\"ghost\"/>\n"
        + PROCESS_CLOSE
        + DEFS_CLOSE;

    private final String startEventTypeId = UUID.randomUUID().toString();

    private final String messageStartEventTypeId = UUID.randomUUID().toString();

    private final String userTaskTypeId = UUID.randomUUID().toString();

    private final String endEventTypeId = UUID.randomUUID().toString();

    private final String messageIntermediateCatchEventTypeId = UUID.randomUUID().toString();

    private final String timerBoundaryEventTypeId = UUID.randomUUID().toString();

    /**
     * Builds the repository state before any {@code bpmn.xml} is saved: {@code /WorkflowTypes} fully configured,
     * and an empty {@code wf:WorkflowVersion} with no {@code bpmn.xml} child yet.
     */
    @Test
    void carriesAnExtensionAttributeAcrossByNamespace() throws Exception
    {
        // BPMN says nothing about which code a service task runs, so `handler` arrives as an extension attribute.
        // The vocabulary names it by namespace, which is what this proves lands.
        final NodeBuilder root = base();
        final NodeBuilder types = root.child("WorkflowTypes");
        flowNodeType(types, "ServiceTask", this.userTaskTypeId, "bpmn:serviceTask", null, "wf:Activity", 0);
        types.child("ServiceTask").setProperty("properties",
            List.of("{" + IAP_NS + "}handler=handler"), Type.STRINGS);

        final NodeState after = firstSave(root, DEFS_OPEN.replace("<bpmn:definitions",
            "<bpmn:definitions xmlns:iap=\"" + IAP_NS + "\"") + PROCESS_OPEN
            + "    <bpmn:serviceTask id=\"" + TASK_1 + "\" iap:handler=\"checkBudget\" />\n"
            + PROCESS_CLOSE + DEFS_CLOSE);

        assertEquals("checkBudget", after.getChildNode(TASK_1).getString(HANDLER));
    }

    @Test
    void carriesItAcrossWhateverPrefixTheFileChose() throws Exception
    {
        // The whole reason the rule names a namespace rather than a prefix. A diagram editor is free to
        // renormalise `iap:` to anything on save, and a prefix-matched lookup would silently stop carrying the
        // handler across — leaving a service task that runs nothing and a workflow that looks right.
        final NodeBuilder root = base();
        final NodeBuilder types = root.child("WorkflowTypes");
        flowNodeType(types, "ServiceTask", this.userTaskTypeId, "bpmn:serviceTask", null, "wf:Activity", 0);
        types.child("ServiceTask").setProperty("properties",
            List.of("{" + IAP_NS + "}handler=handler"), Type.STRINGS);

        final NodeState after = firstSave(root, DEFS_OPEN.replace("<bpmn:definitions",
            "<bpmn:definitions xmlns:ns7=\"" + IAP_NS + "\"") + PROCESS_OPEN
            + "    <bpmn:serviceTask id=\"" + TASK_1 + "\" ns7:handler=\"checkBudget\" />\n"
            + PROCESS_CLOSE + DEFS_CLOSE);

        assertEquals("checkBudget", after.getChildNode(TASK_1).getString(HANDLER));
    }

    @Test
    void ignoresARuleThatOpensANamespaceAndNeverClosesIt() throws Exception
    {
        // A malformed vocabulary entry should cost one property, not throw inside somebody's commit
        final NodeBuilder root = base();
        final NodeBuilder types = root.child("WorkflowTypes");
        flowNodeType(types, "ServiceTask", this.userTaskTypeId, "bpmn:serviceTask", null, "wf:Activity", 0);
        types.child("ServiceTask").setProperty("properties", List.of("{unclosed=handler"), Type.STRINGS);

        final NodeState after = firstSave(root, DEFS_OPEN + PROCESS_OPEN
            + "    <bpmn:serviceTask id=\"" + TASK_1 + "\" handler=\"checkBudget\" />\n"
            + PROCESS_CLOSE + DEFS_CLOSE);

        assertTrue(after.getChildNode(TASK_1).exists(), "the node itself should still be derived");
        assertNull(after.getChildNode(TASK_1).getString(HANDLER));
    }

    @Test
    void leavesAloneAVersionWhoseDiagramDoesNotOwnItsFlowNodes() throws Exception
    {
        // The whole point of the flag. A version whose flow nodes were written by hand holds things no diagram
        // expresses yet — a handler naming the code a service task runs, a multi-valued list of performers — and
        // deriving it would quietly drop them, leaving a workflow shaped like the diagram and unable to run.
        final NodeBuilder root = base();
        version(root).removeProperty(AUTHORITATIVE);
        final NodeBuilder authored = version(root).child(TASK_1);
        authored.setProperty(PRIMARY_TYPE, "wf:Activity", Type.NAME);
        authored.setProperty(HANDLER, "checkBudget");

        final NodeState after = firstSave(root, DEFS_OPEN + PROCESS_OPEN
            + "    <bpmn:userTask id=\"" + TASK_1 + "\" name=\"Decide\" />\n"
            + PROCESS_CLOSE + DEFS_CLOSE);

        // Untouched, not merged: the authored node is exactly as authored, and nothing was derived beside it
        assertEquals("checkBudget", after.getChildNode(TASK_1).getString(HANDLER));
        assertFalse(after.hasProperty(HASH_PROPERTY),
            "a version it did not parse should carry no record of a parse");
    }

    @Test
    void treatsASilentVersionAsNotOwnedByItsDiagram() throws Exception
    {
        // Absent is the same answer as false, and it has to be: every workflow that existed before the flag did
        // was written by hand, so the safe reading of silence is the only one that does not break them all
        final NodeBuilder root = base();
        version(root).removeProperty(AUTHORITATIVE);

        final NodeState after = firstSave(root, DEFS_OPEN + PROCESS_OPEN
            + "    <bpmn:startEvent id=\"" + START_1 + "\" />\n"
            + PROCESS_CLOSE + DEFS_CLOSE);

        assertFalse(after.getChildNode(START_1).exists(), "nothing should have been derived");
    }

    @Test
    void keepsTheFlowNodesOfANonAuthoritativeVersionWhenItsDiagramGoesAway() throws Exception
    {
        // Removing the diagram from a hand-authored version says nothing about its flow nodes, which were never
        // derived from it. Clearing them would delete a working workflow on the strength of a deleted drawing.
        final NodeBuilder root = base();
        version(root).removeProperty(AUTHORITATIVE);
        setBpmnXml(root, DEFS_OPEN + PROCESS_OPEN
            + "    <bpmn:startEvent id=\"" + START_1 + "\" />\n" + PROCESS_CLOSE + DEFS_CLOSE);
        final NodeBuilder authored = version(root).child(TASK_1);
        authored.setProperty(PRIMARY_TYPE, "wf:Activity", Type.NAME);
        authored.setProperty(HANDLER, "checkBudget");

        final NodeState before = root.getNodeState();
        final NodeBuilder after = before.builder();
        descend(after, WORKFLOWS_PATH, DEFINITION_NAME, VERSION_NAME).getChildNode(BPMN_XML).remove();

        assertEquals("checkBudget",
            version(process(before, after)).getChildNode(TASK_1).getString(HANDLER));
    }

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
        nodeTypes.child("wf:SequenceFlow").setProperty("jcr:supertypes", List.of("data:EntityPart"), Type.NAMES);
        // nt:file is registered without any supertype naming a flow node, so bpmn.xml survives a reparse.
        nodeTypes.child("nt:file").setProperty("rep:supertypes", List.of("nt:hierarchyNode"), Type.NAMES);

        descend(root, WORKFLOWS_PATH, DEFINITION_NAME).setProperty(PRIMARY_TYPE, "wf:WorkflowDefinition", Type.NAME);
        final NodeBuilder version = version(root);
        version.setProperty(PRIMARY_TYPE, "wf:WorkflowVersion", Type.NAME);
        version.setProperty("version", VERSION_NAME);
        // Every test below is about a version whose diagram owns its flow nodes, which is the only case this
        // editor acts on. The other case — a version whose nodes were written by hand, because the translation
        // cannot yet carry all of them — has tests of its own.
        version.setProperty(AUTHORITATIVE, true);

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
        final NodeBuilder incomplete = types.child("Incomplete");
        incomplete.setProperty(PRIMARY_TYPE, "wf:FlowNodeType", Type.NAME);
        incomplete.setProperty("xmlElement", "gateway");

        final NodeBuilder userTask = types.getChildNode("UserTask");
        // The shipped vocabulary stores jcrProperties as JSON, so that is what the parser has to read: quoted keys,
        // and values whose JSON type decides the JCR type rather than the shape of their text.
        userTask.setProperty("jcrProperties",
            "{\"catching\": true, \"weight\": 3, \"ratio\": 1.5, \"label\": \"ok\", \"code\": \"007\","
                + " \"cancelled\": false, \"unset\": null}");
        userTask.setProperty("properties",
            List.of("priority", "assigneeExpression=assignee", "async=asynchronous", "missingAttr=ignored"),
            Type.STRINGS);
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

    private NodeBuilder bpmnContent(final NodeBuilder root)
    {
        final NodeBuilder file = version(root).child(BPMN_XML);
        file.setProperty(PRIMARY_TYPE, "nt:file", Type.NAME);
        final NodeBuilder content = file.child(JCR_CONTENT);
        content.setProperty(PRIMARY_TYPE, "nt:resource", Type.NAME);
        return content;
    }

    private void setBpmnXml(final NodeBuilder root, final String xml)
    {
        final NodeBuilder content = bpmnContent(root);
        content.setProperty(JCR_DATA, xml);
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

    /** Saves {@code xml} onto a freshly built {@code base}, and returns the resulting version node state. */
    private NodeState firstSave(final NodeBuilder base, final String xml) throws CommitFailedException
    {
        final NodeState before = base.getNodeState();
        final NodeBuilder after = before.builder();
        setBpmnXml(after, xml);
        return version(process(before, after));
    }

    private NodeState firstSave(final String xml) throws CommitFailedException
    {
        return firstSave(base(), xml);
    }

    @Test
    void parsesNewBpmnXmlIntoFlowNodes() throws Exception
    {
        final NodeState version = firstSave(START_EVENT_XML);

        assertEquals(sha256(START_EVENT_XML), version.getProperty(HASH_PROPERTY).getValue(Type.STRING));

        final NodeState start = version.getChildNode(START_1);
        assertTrue(start.exists());
        assertEquals("wf:StartEvent", start.getProperty(PRIMARY_TYPE).getValue(Type.NAME));
        assertEquals("start1", start.getProperty("elementId").getValue(Type.STRING));
        assertEquals("Start", start.getProperty("label").getValue(Type.STRING));
        assertEquals(this.startEventTypeId, start.getProperty(FLOW_NODE_TYPE).getValue(Type.REFERENCE));

        final NodeState task = version.getChildNode(TASK_1);
        assertTrue(task.exists());
        assertEquals("wf:Activity", task.getProperty(PRIMARY_TYPE).getValue(Type.NAME));
        assertEquals(this.userTaskTypeId, task.getProperty(FLOW_NODE_TYPE).getValue(Type.REFERENCE));

        final NodeState end = version.getChildNode(END_1);
        assertTrue(end.exists());
        assertEquals("wf:EndEvent", end.getProperty(PRIMARY_TYPE).getValue(Type.NAME));

        final NodeState flow1 = start.getChildNode(FLOW_1);
        assertTrue(flow1.exists());
        assertEquals("wf:SequenceFlow", flow1.getProperty(PRIMARY_TYPE).getValue(Type.NAME));
        assertEquals("task1", flow1.getProperty("targetRef").getValue(Type.STRING));

        final NodeState flow2 = task.getChildNode(FLOW_2);
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
        final NodeState start = version(result).getChildNode(START_1);

        assertTrue(start.hasProperty("jcr:created"));
        assertEquals("alice", start.getProperty("jcr:createdBy").getValue(Type.STRING));
        assertEquals("wf/StartEvent", start.getProperty("sling:resourceType").getValue(Type.STRING));
        assertEquals("wf/Event", start.getProperty(RESOURCE_SUPER_TYPE).getValue(Type.STRING));

        final NodeState flow1 = start.getChildNode(FLOW_1);
        assertTrue(flow1.hasProperty("jcr:created"));
        assertEquals("alice", flow1.getProperty("jcr:createdBy").getValue(Type.STRING));
        assertEquals("wf/SequenceFlow", flow1.getProperty("sling:resourceType").getValue(Type.STRING));
        assertEquals("data/EntityPart", flow1.getProperty(RESOURCE_SUPER_TYPE).getValue(Type.STRING));

        // One timestamp for the whole batch, so that the nodes of a single parse cannot be told apart by age.
        assertEquals(start.getProperty("jcr:created").getValue(Type.DATE),
            flow1.getProperty("jcr:created").getValue(Type.DATE));
    }

    @Test
    void parsedFlowNodesFallBackToOakUnknownCreatedByWithNoCommitUser() throws Exception
    {
        final NodeState start = firstSave(START_EVENT_XML).getChildNode(START_1);

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
    void commitsThatLeaveTheWorkflowsTreeAloneChangeNothing() throws Exception
    {
        final NodeState synced = process(EmptyNodeState.EMPTY_NODE, withBpmnXml(START_EVENT_XML));

        final NodeBuilder after = synced.builder();
        after.child("Elsewhere").setProperty("touched", true);

        final NodeState result = process(synced, after);
        assertTrue(result.getChildNode("Elsewhere").hasProperty("touched"));
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
        version(after).getChildNode(BPMN_XML).child("extraUnderFile");
        version(after).getChildNode(BPMN_XML).getChildNode(JCR_CONTENT).child("extraUnderContent");

        final NodeState result = process(synced, after);
        final NodeState before = version(synced);
        final NodeState afterState = version(result);
        assertEquals(before.getProperty(HASH_PROPERTY).getValue(Type.STRING),
            afterState.getProperty(HASH_PROPERTY).getValue(Type.STRING));
        assertTrue(afterState.getChildNode(START_1).exists());
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
        assertTrue(version.getChildNode(START_1).exists());
        assertTrue(version.getChildNode(TASK_1).exists());
        assertTrue(version.getChildNode(END_1).exists());
    }

    /**
     * A {@code bpmn.xml} is content anyone who may edit a workflow can write, so a DOCTYPE must never be honoured:
     * an external one would have the server fetch a URL of the author's choosing, with no timeout, from inside the
     * commit. The document is rejected outright instead, which the editor treats like any other malformed source.
     */
    @Test
    void bpmnXmlDeclaringADoctypeIsRejectedWithoutFetchingIt() throws Exception
    {
        final NodeState synced = process(EmptyNodeState.EMPTY_NODE, withBpmnXml(START_EVENT_XML));

        final NodeBuilder after = synced.builder();
        setBpmnXml(after,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<!DOCTYPE bpmn:definitions SYSTEM \"http://127.0.0.1:1/never-fetched.dtd\">\n"
            + START_EVENT_XML.substring(START_EVENT_XML.indexOf("<bpmn:definitions")));

        final NodeState version = version(process(synced, after));
        assertEquals(sha256(START_EVENT_XML), version.getProperty(HASH_PROPERTY).getValue(Type.STRING));
        assertTrue(version.getChildNode(START_1).exists());
    }

    /**
     * In a real repository {@code jcr:data} is a binary, whose bytes may start with a UTF-8 byte order mark. Reading
     * them as a string first and handing the parser characters makes that mark "content in prolog" and the whole
     * diagram unparseable, so the bytes go to the parser untouched.
     */
    @Test
    void bpmnXmlWithAByteOrderMarkIsParsed() throws Exception
    {
        final String withBom = "﻿" + START_EVENT_XML;
        final NodeState version = firstSave(withBom);

        assertEquals(sha256(withBom), version.getProperty(HASH_PROPERTY).getValue(Type.STRING));
        assertTrue(version.getChildNode(START_1).exists());
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
        assertTrue(afterState.getChildNode(START_1).exists());
        assertFalse(before.hasProperty("description"));
        assertTrue(afterState.hasProperty("description"));
    }

    @Test
    void emptyBpmnXmlIsIgnored() throws Exception
    {
        final NodeState version = firstSave("");

        assertFalse(version.hasProperty(HASH_PROPERTY));
        assertFalse(version.getChildNode(START_1).exists());
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

        assertFalse(version.getChildNode(START_1).exists());
    }

    @Test
    void reparsingRemovesFlowNodesThatNoLongerExist() throws Exception
    {
        final NodeState synced = process(EmptyNodeState.EMPTY_NODE, withBpmnXml(START_EVENT_XML));

        final NodeBuilder after = synced.builder();
        setBpmnXml(after, REPARSED_XML);

        final NodeState result = process(synced, after);
        final NodeState version = version(result);

        assertFalse(version.getChildNode(START_1).exists());
        assertFalse(version.getChildNode(TASK_1).exists());
        assertFalse(version.getChildNode(END_1).exists());
        assertTrue(version.getChildNode(ONLY_START).exists());
        // The source file is not a flow node, so clearing must leave it alone.
        assertTrue(version.getChildNode(BPMN_XML).exists());
    }

    @Test
    void deletingBpmnXmlClearsFlowNodesAndParsedHash() throws Exception
    {
        final NodeState synced = process(EmptyNodeState.EMPTY_NODE, withBpmnXml(START_EVENT_XML));

        final NodeBuilder after = synced.builder();
        version(after).getChildNode(BPMN_XML).remove();

        final NodeState result = process(synced, after);
        final NodeState version = version(result);

        assertFalse(version.hasProperty(HASH_PROPERTY));
        assertFalse(version.getChildNode(BPMN_XML).exists());
        assertFalse(version.getChildNode(START_1).exists());
        assertFalse(version.getChildNode(TASK_1).exists());
        assertFalse(version.getChildNode(END_1).exists());
    }

    /**
     * The source can also disappear without the file node going with it: the content node holding the bytes may be
     * removed, or the bytes themselves. Either way the derived graph describes something that no longer exists, and
     * leaving the hash behind would make re-uploading the original content a no-op.
     */
    @Test
    void deletingTheContentNodeClearsFlowNodesAndParsedHash() throws Exception
    {
        final NodeState synced = process(EmptyNodeState.EMPTY_NODE, withBpmnXml(START_EVENT_XML));

        final NodeBuilder after = synced.builder();
        version(after).getChildNode(BPMN_XML).getChildNode(JCR_CONTENT).remove();
        // A version child other than bpmn.xml going away says nothing about the diagram, so it clears nothing.
        version(after).child("unrelated").setProperty("x", 1L);
        final NodeState withUnrelated = process(synced, after);

        final NodeBuilder second = withUnrelated.builder();
        version(second).getChildNode("unrelated").remove();

        final NodeState version = version(process(withUnrelated, second));

        assertFalse(version.hasProperty(HASH_PROPERTY));
        assertFalse(version.getChildNode(START_1).exists());
    }

    @Test
    void deletingJcrDataClearsFlowNodesAndParsedHash() throws Exception
    {
        final NodeState synced = process(EmptyNodeState.EMPTY_NODE, withBpmnXml(START_EVENT_XML));

        final NodeBuilder after = synced.builder();
        final NodeBuilder content = version(after).getChildNode(BPMN_XML).getChildNode(JCR_CONTENT);
        content.removeProperty(JCR_DATA);
        // Removing an unrelated property at the same stage must not clear anything on its own.
        content.removeProperty("jcr:mimeType");

        final NodeState version = version(process(synced, after));

        assertFalse(version.hasProperty(HASH_PROPERTY));
        assertFalse(version.getChildNode(START_1).exists());
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

        assertFalse(version.getChildNode(START_1).exists());
        assertTrue(version.getChildNode(ONLY_START).exists());
        assertTrue(version.getChildNode("extension").exists());
    }

    @Test
    void bpmnXmlWithoutAProcessElementParsesToNoFlowNodes() throws Exception
    {
        final NodeState version = firstSave(NO_PROCESS_XML);

        assertEquals(sha256(NO_PROCESS_XML), version.getProperty(HASH_PROPERTY).getValue(Type.STRING));
        assertFalse(version.getChildNode(START_1).exists());
    }

    /** A collaboration declares one process per participant; only the first is parsed, and that is reported. */
    @Test
    void bpmnXmlWithSeveralProcessElementsParsesOnlyTheFirst() throws Exception
    {
        final NodeState version = firstSave(
            DEFS_OPEN
            + PROCESS_OPEN
            + "    <bpmn:startEvent id=\"first\"/>\n"
            + PROCESS_CLOSE
            + "  <bpmn:process id=\"process2\">\n"
            + "    <bpmn:startEvent id=\"second\"/>\n"
            + PROCESS_CLOSE
            + DEFS_CLOSE);

        assertTrue(version.getChildNode("first").exists());
        assertFalse(version.getChildNode("second").exists());
    }

    @Test
    void richBpmnXmlExercisesEveryMatchingAndPropertyBranch() throws Exception
    {
        final NodeState version = firstSave(richBase(), RICH_XML);

        // Plain start event: the message-requiring candidate doesn't match, falls back to the plain one.
        final NodeState start = version.getChildNode(START_1);
        assertTrue(start.exists());
        assertEquals(this.startEventTypeId, start.getProperty(FLOW_NODE_TYPE).getValue(Type.REFERENCE));

        // Start event with a message child: the higher-priority message candidate matches.
        final NodeState msgStart = version.getChildNode("msgStart1");
        assertTrue(msgStart.exists());
        assertEquals(this.messageStartEventTypeId, msgStart.getProperty(FLOW_NODE_TYPE).getValue(Type.REFERENCE));

        // Copied/renamed attributes and the JSON jcrProperties, whose value types carry over as they are.
        final NodeState task = version.getChildNode(TASK_1);
        assertTrue(task.exists());
        assertEquals("7", task.getProperty("priority").getValue(Type.STRING));
        assertEquals("mgr", task.getProperty("assignee").getValue(Type.STRING));
        assertEquals(true, task.getProperty("asynchronous").getValue(Type.BOOLEAN));
        assertEquals(true, task.getProperty("catching").getValue(Type.BOOLEAN));
        assertEquals(false, task.getProperty("cancelled").getValue(Type.BOOLEAN));
        assertEquals(3L, task.getProperty("weight").getValue(Type.LONG));
        assertEquals(1.5, task.getProperty("ratio").getValue(Type.DOUBLE));
        // The vocabulary supplies a label, but the diagram is the authority on what an element is called, so the
        // element's own name wins -- as it does over any other configured property that names part of its identity.
        assertEquals("Review", task.getProperty("label").getValue(Type.STRING));
        assertEquals(TASK_1, task.getProperty("elementId").getValue(Type.STRING));
        assertEquals(this.userTaskTypeId, task.getProperty(FLOW_NODE_TYPE).getValue(Type.REFERENCE));
        // A quoted JSON value stays a string, even when its text would parse as a number.
        assertEquals("007", task.getProperty("code").getValue(Type.STRING));
        // A JSON null has no JCR equivalent to store, so it is reported and skipped.
        assertFalse(task.hasProperty("unset"));
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
        // target, missing source, unknown source, unknown target.
        final NodeState flow1 = start.getChildNode(FLOW_1);
        assertEquals("Proceed", flow1.getProperty("label").getValue(Type.STRING));
        assertFalse(flow1.hasProperty("conditionExpression"));

        final NodeState flow2 = task.getChildNode(FLOW_2);
        assertEquals("${approved}", flow2.getProperty("conditionExpression").getValue(Type.STRING));
        assertEquals(true, flow2.getProperty("isDefault").getValue(Type.BOOLEAN));

        assertFalse(task.getChildNode("flowBad").exists());
        assertFalse(start.getChildNode("flowNoTarget").exists());
        assertFalse(task.getChildNode("flowUnknown").exists());
        // An arc whose target was never created would be one the engine cannot follow.
        assertFalse(start.getChildNode("flowGhostTarget").exists());
    }

    /**
     * Where a catching event is stored is the whole of what makes it a boundary event, so an event carrying an
     * {@code attachedToRef} belongs inside the activity it names — not beside it, where nothing would tell it apart
     * from a free-standing mid-process catch.
     */
    @Test
    void boundaryEventsAreStoredInsideTheActivityTheyWatch() throws Exception
    {
        final NodeBuilder root = richBase();
        flowNodeType(root.child("WorkflowTypes"), "TimerBoundaryEvent", this.timerBoundaryEventTypeId,
            "bpmn:boundaryEvent", "bpmn:timerEventDefinition", "wf:IntermediateCatchingEvent", 10);

        final NodeState version = firstSave(root,
            DEFS_OPEN
            + PROCESS_OPEN
            + "    <bpmn:userTask id=\"task1\" name=\"Review\"/>\n"
            + "    <bpmn:userTask id=\"task2\" name=\"Escalate\"/>\n"
            + "    <bpmn:boundaryEvent id=\"deadline\" attachedToRef=\"task1\" cancelActivity=\"true\">\n"
            + "      <bpmn:timerEventDefinition/>\n"
            + "    </bpmn:boundaryEvent>\n"
            + "    <bpmn:boundaryEvent id=\"reminder\" attachedToRef=\"task2\" cancelActivity=\"false\">\n"
            + "      <bpmn:timerEventDefinition/>\n"
            + "    </bpmn:boundaryEvent>\n"
            + "    <bpmn:boundaryEvent id=\"defaulted\" attachedToRef=\"task2\">\n"
            + "      <bpmn:timerEventDefinition/>\n"
            + "    </bpmn:boundaryEvent>\n"
            + "    <bpmn:boundaryEvent id=\"orphan\" attachedToRef=\"ghost\">\n"
            + "      <bpmn:timerEventDefinition/>\n"
            + "    </bpmn:boundaryEvent>\n"
            + "    <bpmn:boundaryEvent id=\"unattached\">\n"
            + "      <bpmn:timerEventDefinition/>\n"
            + "    </bpmn:boundaryEvent>\n"
            + "    <bpmn:boundaryEvent id=\"unconfigured\" attachedToRef=\"task1\">\n"
            + "      <bpmn:signalEventDefinition/>\n"
            + "    </bpmn:boundaryEvent>\n"
            + PROCESS_CLOSE
            + DEFS_CLOSE);

        final NodeState task1 = version.getChildNode(TASK_1);
        final NodeState deadline = task1.getChildNode("deadline");
        assertTrue(deadline.exists());
        assertEquals("wf:IntermediateCatchingEvent", deadline.getProperty(PRIMARY_TYPE).getValue(Type.NAME));
        assertEquals(this.timerBoundaryEventTypeId, deadline.getProperty(FLOW_NODE_TYPE).getValue(Type.REFERENCE));
        assertEquals("deadline", deadline.getProperty("elementId").getValue(Type.STRING));
        assertEquals("wf/IntermediateEvent", deadline.getProperty(RESOURCE_SUPER_TYPE).getValue(Type.STRING));
        assertEquals(true, deadline.getProperty(INTERRUPTING).getValue(Type.BOOLEAN));

        final NodeState task2 = version.getChildNode("task2");
        assertEquals(false, task2.getChildNode("reminder").getProperty(INTERRUPTING).getValue(Type.BOOLEAN));
        // No cancelActivity attribute leaves the node type's own default in place rather than guessing.
        assertFalse(task2.getChildNode("defaulted").hasProperty(INTERRUPTING));

        // Never stored beside the activity, whatever goes wrong.
        assertFalse(version.getChildNode("deadline").exists());
        assertFalse(version.getChildNode("orphan").exists());
        assertFalse(version.getChildNode("unattached").exists());
        assertFalse(task1.getChildNode("unconfigured").exists());
    }

    /**
     * The node name is the BPMN id, and not every id can be one. Oak's own name validation never sees nodes an
     * editor adds to the builder, so an unusable name would otherwise be stored unaddressable — or, for an id that
     * happens to name an existing child, would retype that child, and {@code bpmn.xml} is the child that matters:
     * the next reparse would then delete the diagram it was derived from.
     */
    @Test
    void elementIdsThatCannotBeNodeNamesAreSkipped() throws Exception
    {
        final NodeState version = firstSave(
            DEFS_OPEN
            + PROCESS_OPEN
            + "    <bpmn:startEvent id=\"a/b\"/>\n"
            + "    <bpmn:startEvent id=\"ns:foo\"/>\n"
            + "    <bpmn:startEvent id=\"a[1]\"/>\n"
            + "    <bpmn:startEvent id=\"a|b\"/>\n"
            + "    <bpmn:startEvent id=\"a*b\"/>\n"
            + "    <bpmn:startEvent id=\" leading\"/>\n"
            + "    <bpmn:startEvent id=\".\"/>\n"
            + "    <bpmn:startEvent id=\"..\"/>\n"
            + "    <bpmn:startEvent id=\"bpmn.xml\"/>\n"
            + "    <bpmn:endEvent id=\"good\"/>\n"
            + "    <bpmn:sequenceFlow id=\"a/flow\" sourceRef=\"good\" targetRef=\"good\"/>\n"
            + PROCESS_CLOSE
            + DEFS_CLOSE);

        // The diagram itself survives, still a file rather than a start event.
        assertEquals("nt:file", version.getChildNode(BPMN_XML).getProperty(PRIMARY_TYPE).getValue(Type.NAME));
        assertTrue(version.getChildNode(BPMN_XML).getChildNode(JCR_CONTENT).hasProperty(JCR_DATA));

        final List<String> names = new ArrayList<>();
        version.getChildNodeNames().forEach(names::add);
        assertEquals(List.of(BPMN_XML, "good"), names);

        final NodeState good = version.getChildNode("good");
        assertTrue(good.exists());
        assertEquals(0, good.getChildNodeCount(Long.MAX_VALUE));
    }

    /**
     * BPMN ids are unique within a document, but nothing enforces that, and {@code NodeBuilder.child} hands back the
     * node the first element already populated — merging two elements into one that carries the primary type of the
     * second and properties of both. The later element is dropped instead.
     */
    @Test
    void elementsReusingAnEarlierIdAreSkipped() throws Exception
    {
        final NodeBuilder root = richBase();
        flowNodeType(root.child("WorkflowTypes"), "TimerBoundaryEvent", this.timerBoundaryEventTypeId,
            "bpmn:boundaryEvent", "bpmn:timerEventDefinition", "wf:IntermediateCatchingEvent", 10);

        final NodeState version = firstSave(root,
            DEFS_OPEN
            + PROCESS_OPEN
            + "    <bpmn:userTask id=\"dup\" name=\"TheTask\"/>\n"
            + "    <bpmn:startEvent id=\"dup\" name=\"TheEvent\"/>\n"
            + "    <bpmn:boundaryEvent id=\"dup\" attachedToRef=\"dup\">\n"
            + "      <bpmn:timerEventDefinition/>\n"
            + "    </bpmn:boundaryEvent>\n"
            + "    <bpmn:sequenceFlow id=\"dup\" sourceRef=\"dup\" targetRef=\"dup\"/>\n"
            + PROCESS_CLOSE
            + DEFS_CLOSE);

        // The first element to claim the id keeps it, with none of the later ones' type or properties mixed in.
        final NodeState dup = version.getChildNode("dup");
        assertEquals("wf:Activity", dup.getProperty(PRIMARY_TYPE).getValue(Type.NAME));
        assertEquals("TheTask", dup.getProperty("label").getValue(Type.STRING));
        assertEquals(this.userTaskTypeId, dup.getProperty(FLOW_NODE_TYPE).getValue(Type.REFERENCE));
        assertEquals(0, dup.getChildNodeCount(Long.MAX_VALUE));
    }

    /**
     * {@code /WorkflowTypes} accepts residual children and properties, so a half-authored entry can carry an
     * {@code xmlElement} without the {@code jcrNodeType} or the identity a stored node needs. Writing a null into
     * Oak throws, and an exception out of a commit editor aborts the whole commit — so such an entry has to be
     * recognized and skipped instead.
     */
    @Test
    void incompleteFlowNodeTypesAreSkippedWithoutFailingTheCommit() throws Exception
    {
        final NodeBuilder root = base();
        final NodeBuilder types = root.child("WorkflowTypes");
        // Matches, but names no node type to create.
        final NodeBuilder noType = types.child("NoNodeType");
        noType.setProperty(PRIMARY_TYPE, "wf:FlowNodeType", Type.NAME);
        noType.setProperty("jcr:uuid", UUID.randomUUID().toString());
        noType.setProperty("xmlElement", "bpmn:parallelGateway");
        // Matches and names a node type, but is not referenceable, so nothing could point at it.
        final NodeBuilder noUuid = types.child("NoUuid");
        noUuid.setProperty(PRIMARY_TYPE, "wf:FlowNodeType", Type.NAME);
        noUuid.setProperty("xmlElement", "bpmn:inclusiveGateway");
        noUuid.setProperty("jcrNodeType", "wf:InclusiveGateway");

        final NodeState version = firstSave(root,
            DEFS_OPEN
            + PROCESS_OPEN
            + "    <bpmn:parallelGateway id=\"gwNoType\"/>\n"
            + "    <bpmn:inclusiveGateway id=\"gwNoUuid\"/>\n"
            + "    <bpmn:startEvent id=\"start1\"/>\n"
            + PROCESS_CLOSE
            + DEFS_CLOSE);

        assertFalse(version.getChildNode("gwNoType").exists());
        assertFalse(version.getChildNode("gwNoUuid").exists());
        // The rest of the diagram still parses, and the commit still succeeds.
        assertTrue(version.getChildNode(START_1).exists());
    }

    /**
     * {@code jcrProperties} is a JSON object, and the vocabulary is content: a malformed one must cost only its own
     * properties, not the parse and not the commit.
     */
    @Test
    void unparseableJcrPropertiesAreIgnored() throws Exception
    {
        final NodeBuilder root = base();
        root.child("WorkflowTypes").getChildNode("StartEvent").setProperty("jcrProperties", "{not json");
        root.child("WorkflowTypes").getChildNode("EndEvent").setProperty("jcrProperties", "[1, 2]");

        final NodeState version = firstSave(root, START_EVENT_XML);

        final NodeState start = version.getChildNode(START_1);
        assertTrue(start.exists());
        assertEquals("Start", start.getProperty("label").getValue(Type.STRING));
        assertTrue(version.getChildNode(END_1).exists());
    }

    /** A guard belongs to the arc that declares it, not to whatever an extension nested further down. */
    @Test
    void onlyADirectConditionExpressionChildCounts() throws Exception
    {
        final NodeState version = firstSave(
            DEFS_OPEN
            + PROCESS_OPEN
            + "    <bpmn:startEvent id=\"start1\"/>\n"
            + "    <bpmn:endEvent id=\"end1\"/>\n"
            + "    <bpmn:sequenceFlow id=\"flow1\" sourceRef=\"start1\" targetRef=\"end1\">\n"
            + "      <bpmn:extensionElements>\n"
            + "        <bpmn:conditionExpression>${notMine}</bpmn:conditionExpression>\n"
            + "      </bpmn:extensionElements>\n"
            + "    </bpmn:sequenceFlow>\n"
            + PROCESS_CLOSE
            + DEFS_CLOSE);

        assertFalse(version.getChildNode(START_1).getChildNode(FLOW_1).hasProperty("conditionExpression"));
    }

    @Test
    void arrayValuedJcrDataIsIgnored() throws Exception
    {
        final NodeState before = base().getNodeState();
        final NodeBuilder after = before.builder();
        bpmnContent(after).setProperty(JCR_DATA, List.of(START_EVENT_XML), Type.STRINGS);

        final NodeState result = process(before, after);
        final NodeState version = version(result);

        assertFalse(version.hasProperty(HASH_PROPERTY));
        assertFalse(version.getChildNode(START_1).exists());
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
        assertTrue(version.getChildNode(ONLY_START).exists());
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

        final NodeState version = firstSave(root,
            DEFS_OPEN
            + PROCESS_OPEN
            + "    <bpmn:exclusiveGateway id=\"gwNoSuper\"/>\n"
            + "    <bpmn:parallelGateway id=\"gwEmptySuper\"/>\n"
            + PROCESS_CLOSE
            + DEFS_CLOSE);

        final NodeState gwNoSuper = version.getChildNode("gwNoSuper");
        assertTrue(gwNoSuper.exists());
        assertEquals("wf/NoSupertypeType", gwNoSuper.getProperty("sling:resourceType").getValue(Type.STRING));
        assertFalse(gwNoSuper.hasProperty(RESOURCE_SUPER_TYPE));

        final NodeState gwEmptySuper = version.getChildNode("gwEmptySuper");
        assertTrue(gwEmptySuper.exists());
        assertFalse(gwEmptySuper.hasProperty(RESOURCE_SUPER_TYPE));
    }

    /**
     * The workflow node types are meant to be extended, and a subtype whose diagram is silently never parsed would
     * be the least visible way for that extensibility to fail.
     */
    @Test
    void subtypesOfWorkflowVersionAreSyncedToo() throws Exception
    {
        final NodeBuilder root = base();
        root.child("jcr:system").child("jcr:nodeTypes").child("x:ApprovalVersion")
            .setProperty("rep:supertypes", List.of("wf:WorkflowVersion", "data:Entity"), Type.NAMES);
        version(root).setProperty(PRIMARY_TYPE, "x:ApprovalVersion", Type.NAME);
        // Siblings that are not versions are walked past: one with an unrelated type, one with none at all.
        descend(root, WORKFLOWS_PATH, DEFINITION_NAME, "notAVersion")
            .setProperty(PRIMARY_TYPE, "nt:unstructured", Type.NAME);
        descend(root, WORKFLOWS_PATH, DEFINITION_NAME, "noTypeAtAll").setProperty("something", "else");

        final NodeState version = firstSave(root, START_EVENT_XML);

        assertEquals(sha256(START_EVENT_XML), version.getProperty(HASH_PROPERTY).getValue(Type.STRING));
        assertTrue(version.getChildNode(START_1).exists());
    }
}

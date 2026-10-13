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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.junit.jupiter.api.Test;

import io.uhndata.iap.workflows.models.Parameter;

import static io.uhndata.iap.workflows.ParseFixture.parse;
import static io.uhndata.iap.workflows.ParseFixture.serviceTask;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ParameterTranslation} and for the matching rules that decide which vocabulary entry's
 * parameters a BPMN element is read with. They are driven straight through {@link WorkflowDefinitionUtils#parse}
 * rather than through the editor that normally calls it.
 *
 * <p>All of these are about what the parser <em>says</em> as much as what it stores. Everything a diagram can get
 * wrong is reported and then survived, so the report is the only sign a mistake was noticed.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
class ParameterTranslationTest
{
    private static final String PRIMARY_TYPE = "jcr:primaryType";

    private static final String MIXIN_TYPES = "jcr:mixinTypes";

    private static final String WORKFLOW_TYPES = "WorkflowTypes";

    private static final String PARAMETER_NODETYPE = "wf:Parameter";

    private static final String ACTIVITY = "wf:Activity";

    private static final String TEXT = Parameter.TYPE_TEXT;

    private static final String FLOW_NODE_TYPE = "flowNodeType";

    private static final String SERVICE_TASK_ELEMENT = "bpmn:serviceTask";

    private static final String XML_LOCATION = "xmlLocation";

    private static final String PARAMETER_DETAIL = "parameter";

    private static final String ELEMENT_DETAIL = "element";

    private static final String GENERIC_SERVICE_TASK = "ServiceTask";

    private static final String SET_STATE = "setState";

    private static final String TO_STATE = "toState";

    private static final String FROM_STATES = "fromStates";

    private static final String HOST_TAG = "hostTag";

    private static final String HANDLER = "handler";

    private static final String TEMPLATE = "template";

    private static final String MAIL_TEMPLATE = "mail:Template";

    private static final String TARGETS = "targets";

    private static final String DEADLINE = "deadline";

    private static final String CANCEL_ACTIVITY = "cancelActivity";

    private static final String TIME_DURATION = "timeDuration";

    private static final String CONDITION_EXPRESSION = "conditionExpression";

    private static final String SEQUENCE_FLOW = "SequenceFlow";

    private static final String TASK = ParseFixture.TASK;

    @Test
    void breaksATieOfEqualPrioritiesByEntryName() throws Exception
    {
        // /WorkflowTypes is not orderable, so two equally specific entries would otherwise be tried in whatever
        // order the repository iterates its children
        final NodeBuilder root = VocabularyFixture.repository();
        VocabularyFixture.entry(root, "ZebraTask", SERVICE_TASK_ELEMENT, ACTIVITY, 10);
        VocabularyFixture.entry(root, "AardvarkTask", SERVICE_TASK_ELEMENT, ACTIVITY, 10);

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState after = parse(root, "    <bpmn:serviceTask id=\"" + TASK + "\"/>\n");

            assertEquals(VocabularyFixture.id("AardvarkTask"),
                after.getChildNode(TASK).getProperty(FLOW_NODE_TYPE).getValue(Type.REFERENCE));
            assertEquals(List.of(), problems.messages());
        }
    }

    @Test
    void trimsASingleValuedAttribute() throws Exception
    {
        // Whitespace around an attribute value is the diagram's formatting, not part of the state a handler is
        // then asked to move a version to
        final NodeBuilder root = withSetState();
        VocabularyFixture.attributeParameter(root.child(WORKFLOW_TYPES).child("SetState"), HOST_TAG, TEXT, 1);

        final NodeState after = parse(root, "    <bpmn:serviceTask id=\"" + TASK + "\" iap:hostTag=\" approved \">\n"
            + "      <bpmn:extensionElements>\n"
            + "        <iap:setState toState=\" ACTIVE\">"
            + "<iap:fromStates>DRAFT</iap:fromStates></iap:setState>\n"
            + "      </bpmn:extensionElements>\n"
            + "    </bpmn:serviceTask>\n");

        assertEquals("ACTIVE", after.getChildNode(TASK).getString(TO_STATE));
        assertEquals("approved", after.getChildNode(TASK).getString(HOST_TAG));
    }

    @Test
    void treatsAnAllWhitespaceValueAsNoValueAtAll() throws Exception
    {
        // A required parameter left empty is reported missing, and an optional one is not written at all
        final NodeBuilder root = withSetState();

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState after = parse(root, serviceTask("<iap:setState toState=\"  \">"
                + "<iap:fromStates>DRAFT</iap:fromStates></iap:setState>"));

            assertFalse(after.getChildNode(TASK).hasProperty(TO_STATE));
            assertEquals(List.of("a required parameter has no value"),
                problems.names());
            assertEquals(TO_STATE, problems.all().get(0).details().get(PARAMETER_DETAIL));
        }
    }

    @Test
    void reportsAListWrittenAsAnAttribute() throws Exception
    {
        final NodeBuilder root = withSetState();

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState after = parse(root,
                serviceTask("<iap:setState toState=\"ACTIVE\" fromStates=\"DRAFT, TRIAL\"/>"));

            assertFalse(after.getChildNode(TASK).hasProperty(FROM_STATES));
            assertEquals(List.of("a parameter taking a list has it written as an attribute instead",
                "a required parameter has no value"),
                problems.names());
            assertEquals(FROM_STATES, problems.all().get(0).details().get(PARAMETER_DETAIL));
        }
    }

    @Test
    void reportsASingleValueWrittenAsChildElements() throws Exception
    {
        final NodeBuilder root = withSetState();

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState after = parse(root, serviceTask("<iap:setState>"
                + "<iap:toState>ACTIVE</iap:toState><iap:fromStates>DRAFT</iap:fromStates></iap:setState>"));

            assertFalse(after.getChildNode(TASK).hasProperty(TO_STATE));
            assertEquals(List.of("a parameter taking one value has it written as child elements instead",
                "a required parameter has no value"),
                problems.names());
        }
    }

    @Test
    void acceptsAReferenceToANodeCarryingTheDeclaredTypeAsAMixin() throws Exception
    {
        // A node type constraint is satisfied by a mixin as much as by a primary type. The referenceable,
        // documented or lifecycle-bearing part of a node is regularly a mixin.
        final NodeBuilder root = withTemplateReference();
        target(root, "byMixin", "sling:Folder").setProperty(MIXIN_TYPES, List.of(MAIL_TEMPLATE), Type.NAMES);
        target(root, "bySubtypeOfMixin", "sling:Folder")
            .setProperty(MIXIN_TYPES, List.of("mail:HtmlTemplate"), Type.NAMES);
        NodeTypesFixture.type(root, "mail:HtmlTemplate", MAIL_TEMPLATE);

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState after = parse(root,
                serviceTask("byMixin", "<iap:notify template=\"/targets/byMixin\"/>")
                + serviceTask("bySubtype", "<iap:notify template=\"/targets/bySubtypeOfMixin\"/>"));

            assertEquals("/targets/byMixin", after.getChildNode("byMixin").getString(TEMPLATE));
            assertEquals("/targets/bySubtypeOfMixin", after.getChildNode("bySubtype").getString(TEMPLATE));
            assertEquals(List.of(), problems.messages());
        }
    }

    @Test
    void reportsAReferenceWhoseMixinsAreAllOfTheWrongType() throws Exception
    {
        final NodeBuilder root = withTemplateReference();
        target(root, "folder", "sling:Folder").setProperty(MIXIN_TYPES, List.of("mix:created"), Type.NAMES);

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState after = parse(root, serviceTask("<iap:notify template=\"/targets/folder\"/>"));

            assertEquals("/targets/folder", after.getChildNode(TASK).getString(TEMPLATE),
                "kept as written: a path that is wrong is easier to repair than configuration silently lost");
            assertEquals(List.of("a reference parameter names a node of the wrong type"),
                problems.names());
        }
    }

    @Test
    void readsAParameterOfAnEntryWithNoExtensionElementAsAnAttribute() throws Exception
    {
        final NodeBuilder root = VocabularyFixture.repository();
        final NodeBuilder type = VocabularyFixture.entry(root, GENERIC_SERVICE_TASK, SERVICE_TASK_ELEMENT, ACTIVITY, 0);
        VocabularyFixture.parameter(type, HANDLER, TEXT, 0, 1);

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState after =
                parse(root, "    <bpmn:serviceTask id=\"" + TASK + "\" iap:handler=\"checkBudget\"/>\n");

            assertEquals("checkBudget", after.getChildNode(TASK).getString(HANDLER));
            assertEquals(List.of("a parameter is not an attribute of an entry that declares no extension element"),
                problems.names());
            assertEquals(TASK, problems.all().get(0).details().get(ELEMENT_DETAIL));
            assertEquals(HANDLER, problems.all().get(0).details().get(PARAMETER_DETAIL));
        }
    }

    @Test
    void saysNothingAboutAMalformedEntryNoDiagramUses() throws Exception
    {
        // The reason the report lives at the point of use. An unusable corner of the vocabulary must not fill
        // the log on every save of every workflow that never touches it.
        final NodeBuilder root = withGenericServiceTask();
        VocabularyFixture.parameter(VocabularyFixture.entry(root, "Unused", "bpmn:sendTask", ACTIVITY, 0), "recipients",
            TEXT, 0, 0);

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState after = parse(root, "    <bpmn:serviceTask id=\"" + TASK + "\"/>\n");

            assertTrue(after.getChildNode(TASK).exists());
            assertEquals(List.of(), problems.messages());
        }
    }

    @Test
    void storesNothingForAParameterTheDiagramLeavesOut() throws Exception
    {
        // Left to the node type's default
        final NodeBuilder root = withGenericServiceTask();

        final NodeState after = parse(root, "    <bpmn:serviceTask id=\"" + TASK + "\"/>\n");

        assertNull(after.getChildNode(TASK).getString(HANDLER));
    }

    @Test
    void passesOverAMatchingEntryThatCannotProduceANode() throws Exception
    {
        // A half-authored entry must not take the element away from the next candidate, which can store it
        final NodeBuilder root = withGenericServiceTask();
        VocabularyFixture.entry(root, "Broken", SERVICE_TASK_ELEMENT, ACTIVITY, 10).removeProperty("jcrNodeType");

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState after = parse(root, "    <bpmn:serviceTask id=\"" + TASK + "\"/>\n");

            assertEquals(VocabularyFixture.id(GENERIC_SERVICE_TASK),
                after.getChildNode(TASK).getProperty(FLOW_NODE_TYPE).getValue(Type.REFERENCE));
            assertEquals(List.of("FlowNodeType declares no jcrNodeType"), problems.names());
            assertEquals("Broken", problems.all().get(0).details().get(FLOW_NODE_TYPE));
        }
    }

    @Test
    void reportsFewerValuesThanTheMinimum() throws Exception
    {
        final NodeBuilder root = withSetState();
        root.child(WORKFLOW_TYPES).child("SetState").child(FROM_STATES).setProperty("minValues", 2L);

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState after = parse(root, serviceTask(setState("ACTIVE")));

            assertEquals(List.of("DRAFT"), after.getChildNode(TASK).getProperty(FROM_STATES).getValue(Type.STRINGS));
            assertEquals(List.of("a parameter has fewer values than it requires"), problems.names());
        }
    }

    @Test
    void keepsMoreValuesThanTheMaximumAndSaysSo() throws Exception
    {
        // Dropping the extras would hide the mistake and change what the workflow does
        final NodeBuilder root = withSetState();
        root.child(WORKFLOW_TYPES).child("SetState").child(FROM_STATES).setProperty("maxValues", 2L);

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState after = parse(root, serviceTask("<iap:setState toState=\"ACTIVE\"><iap:fromStates>DRAFT"
                + "</iap:fromStates><iap:fromStates>TRIAL</iap:fromStates><iap:fromStates>ACTIVE</iap:fromStates>"
                + "</iap:setState>"));

            assertEquals(List.of("DRAFT", "TRIAL", "ACTIVE"),
                after.getChildNode(TASK).getProperty(FROM_STATES).getValue(Type.STRINGS));
            assertEquals(List.of("a parameter has more values than it accepts"), problems.names());
        }
    }

    @Test
    void readsAParameterDeclaringNothingAsOneTextValue() throws Exception
    {
        // A declaration without the properties its node type autocreates, as stored under another node type
        final NodeBuilder root = withGenericServiceTask();
        root.child(WORKFLOW_TYPES).child(GENERIC_SERVICE_TASK).child("hostTag")
            .setProperty(PRIMARY_TYPE, PARAMETER_NODETYPE, Type.NAME)
            .setProperty(XML_LOCATION, "attribute");

        final NodeState after = parse(root, "    <bpmn:serviceTask id=\"" + TASK + "\" iap:hostTag=\"a, b\"/>\n");

        final PropertyState hostTag = after.getChildNode(TASK).getProperty("hostTag");
        assertFalse(hostTag.isArray());
        assertEquals("a, b", hostTag.getValue(Type.STRING));
    }

    @Test
    void doesNotStoreAParameterTheNodeTypeDeclaresDifferently() throws Exception
    {
        // Oak would take the value through the residual definitions, leaving a property in a shape its own node
        // type says it never has
        final NodeBuilder root = withGenericServiceTask();
        final NodeBuilder definitions = root.child("jcr:system").child("jcr:nodeTypes").child(ACTIVITY)
            .child("rep:namedPropertyDefinitions");
        definitions.child("outcomes").child("STRINGS");
        definitions.child(HANDLER).child("STRING");
        VocabularyFixture.attributeParameter(root.child(WORKFLOW_TYPES).child(GENERIC_SERVICE_TASK), "outcomes",
            TEXT, 1);

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState after = parse(root, "    <bpmn:serviceTask id=\"" + TASK + "\" iap:handler=\"review\""
                + " iap:outcomes=\"approved\"/>\n");

            assertEquals("review", after.getChildNode(TASK).getString(HANDLER));
            assertFalse(after.getChildNode(TASK).hasProperty("outcomes"));
            assertEquals(List.of("a parameter is declared differently by the node type it is stored in"),
                problems.names());
            assertEquals("outcomes", problems.all().get(0).details().get(PARAMETER_DETAIL));
        }
    }

    @Test
    void storesParametersTheNodeTypeDeclaresTheSameWay() throws Exception
    {
        final NodeBuilder root = withGenericServiceTask();
        final NodeBuilder definitions = root.child("jcr:system").child("jcr:nodeTypes").child(ACTIVITY)
            .child("rep:namedPropertyDefinitions");
        definitions.child("urgent").child("BOOLEAN");
        definitions.child("retries").child("LONGS");
        definitions.child("note").child("UNDEFINED");
        final NodeBuilder type = root.child(WORKFLOW_TYPES).child(GENERIC_SERVICE_TASK);
        VocabularyFixture.attributeParameter(type, "urgent", Parameter.TYPE_BOOLEAN, 1);
        VocabularyFixture.attributeParameter(type, "retries", Parameter.TYPE_LONG, 0);
        VocabularyFixture.attributeParameter(type, "note", TEXT, 1);

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState task = parse(root, "    <bpmn:serviceTask id=\"" + TASK + "\" iap:urgent=\"true\""
                + " iap:retries=\"1, 2\" iap:note=\"n\"/>\n").getChildNode(TASK);

            assertTrue(task.getBoolean("urgent"));
            assertEquals(List.of(1L, 2L), task.getProperty("retries").getValue(Type.LONGS));
            assertEquals("n", task.getString("note"));
            assertEquals(List.of(), problems.messages());
        }
    }

    @Test
    void readsParametersAndOptionsOfASubtype() throws Exception
    {
        final NodeBuilder root = withGenericServiceTask();
        NodeTypesFixture.type(root, "x:Parameter", PARAMETER_NODETYPE);
        NodeTypesFixture.type(root, "x:Option", "wf:ParameterOption");
        final NodeBuilder urgency = VocabularyFixture.attributeParameter(
            root.child(WORKFLOW_TYPES).child(GENERIC_SERVICE_TASK), "urgency", TEXT, 1)
            .setProperty(PRIMARY_TYPE, "x:Parameter", Type.NAME);
        urgency.child("now").setProperty(PRIMARY_TYPE, "x:Option", Type.NAME).setProperty("value", "now");
        // Neither a valueless option nor a child of another type is offered
        urgency.child("blank").setProperty(PRIMARY_TYPE, "x:Option", Type.NAME);
        urgency.child("note").setProperty(PRIMARY_TYPE, "nt:unstructured", Type.NAME);

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState after = parse(root, "    <bpmn:serviceTask id=\"" + TASK + "\" iap:urgency=\"later\"/>\n");

            assertEquals("later", after.getChildNode(TASK).getString("urgency"));
            assertEquals(List.of("a parameter has a value it does not offer"), problems.names());
        }
    }

    @Test
    void reportsAParameterDeclaredByItsResourceTypeAlone() throws Exception
    {
        final NodeBuilder root = withGenericServiceTask();
        root.child(WORKFLOW_TYPES).child(GENERIC_SERVICE_TASK).child("note")
            .setProperty(PRIMARY_TYPE, "nt:unstructured", Type.NAME)
            .setProperty("sling:resourceType", Parameter.RESOURCE_TYPE);

        try (RecordedProblems problems = new RecordedProblems()) {
            parse(root, "    <bpmn:serviceTask id=\"" + TASK + "\"/>\n");

            assertEquals(List.of("a vocabulary entry holds a parameter that is not a wf:Parameter node"),
                problems.names());
            assertEquals("note", problems.all().get(0).details().get(PARAMETER_DETAIL));
        }
    }

    @Test
    void takesOnlyFlowNodeTypesAsEntries() throws Exception
    {
        // Anything under /WorkflowTypes may carry an xmlElement; only an entry may claim elements with it
        final NodeBuilder root = withGenericServiceTask();
        VocabularyFixture.entry(root, "Stray", SERVICE_TASK_ELEMENT, ACTIVITY, 100)
            .setProperty(PRIMARY_TYPE, "nt:unstructured", Type.NAME);

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState after = parse(root, "    <bpmn:serviceTask id=\"" + TASK + "\"/>\n");

            assertEquals(VocabularyFixture.id(GENERIC_SERVICE_TASK),
                after.getChildNode(TASK).getProperty(FLOW_NODE_TYPE).getValue(Type.REFERENCE));
            assertEquals(List.of(), problems.messages());
        }
    }

    @Test
    void readsAParameterInALocationItCannotNameInsideItsExtensionElement() throws Exception
    {
        // Only a node of another type can hold such a location; the node type constrains it
        final NodeBuilder root = withSetState();
        root.child(WORKFLOW_TYPES).child("SetState").child(TO_STATE).setProperty(XML_LOCATION, "elsewhere");

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState after = parse(root, serviceTask(setState("ACTIVE")));

            assertEquals("ACTIVE", after.getChildNode(TASK).getString(TO_STATE));
            assertEquals(List.of(), problems.messages());
        }
    }

    @Test
    void dropsAValueItsTypeCannotReadAndSaysSo() throws Exception
    {
        final NodeBuilder root = VocabularyFixture.repository();
        final NodeBuilder type = VocabularyFixture.handler(root, "Escalate", "escalate");
        VocabularyFixture.parameter(type, "urgent", Parameter.TYPE_BOOLEAN, 0, 1);
        VocabularyFixture.parameter(type, "retries", Parameter.TYPE_LONG, 0, 0);

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState task = parse(root, serviceTask("<iap:escalate urgent=\"maybe\">"
                + "<iap:retries>2</iap:retries><iap:retries>often</iap:retries></iap:escalate>")).getChildNode(TASK);

            assertFalse(task.hasProperty("urgent"));
            assertEquals(List.of(2L), task.getProperty("retries").getValue(Type.LONGS));
            assertEquals(Map.of("urgent", "a parameter has a value that is not a boolean",
                "retries", "a parameter has a value that is not a whole number"), problems.all().stream()
                    .collect(Collectors.toMap(problem -> problem.details().get(PARAMETER_DETAIL),
                        RecordedProblems.Problem::problem)));
        }
    }

    @Test
    void reportsARequiredParameterWhoseEveryValueIsDroppedAsMissing() throws Exception
    {
        final NodeBuilder root = VocabularyFixture.repository();
        VocabularyFixture.parameter(VocabularyFixture.handler(root, "Escalate", "escalate"), "count",
            Parameter.TYPE_LONG, 1, 1);

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState task = parse(root, serviceTask("<iap:escalate count=\"abc\"/>")).getChildNode(TASK);

            assertFalse(task.hasProperty("count"));
            assertEquals(List.of("a parameter has a value that is not a whole number",
                "a required parameter has no value"), problems.names());
        }
    }

    @Test
    void keepsAReferenceNamingNothingAndSaysSo() throws Exception
    {
        // A relative path names nothing either
        final NodeBuilder root = withTemplateReference();
        target(root, "folder", MAIL_TEMPLATE);

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState after = parse(root, serviceTask("missing", "<iap:notify template=\"/targets/missing\"/>")
                + serviceTask("relative", "<iap:notify template=\"targets/folder\"/>")
                + serviceTask("doubled", "<iap:notify template=\"/targets//folder\"/>")
                + serviceTask("trailing", "<iap:notify template=\"/targets/folder/\"/>"));

            assertEquals("/targets/missing", after.getChildNode("missing").getString(TEMPLATE));
            assertEquals("targets/folder", after.getChildNode("relative").getString(TEMPLATE));
            // Oak refuses an empty step outright, which must cost the value its check rather than the save
            assertEquals("/targets//folder", after.getChildNode("doubled").getString(TEMPLATE));
            assertEquals("/targets/folder/", after.getChildNode("trailing").getString(TEMPLATE));
            assertEquals(Collections.nCopies(4, "a reference parameter names a node that does not exist"),
                problems.names());
        }
    }

    @Test
    void readsATimerBoundaryEventFromBpmnsOwnMarkup() throws Exception
    {
        final NodeBuilder root = withTimerBoundaryEvent();

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState after = parse(root, boundaryEvent(" cancelActivity=\"false\"",
                "<bpmn:timeDuration xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
                    + " xsi:type=\"bpmn:tFormalExpression\"> P5D </bpmn:timeDuration>"));

            final NodeState event = after.getChildNode(TASK).getChildNode(DEADLINE);
            assertEquals(Type.BOOLEAN, event.getProperty(CANCEL_ACTIVITY).getType());
            assertFalse(event.getBoolean(CANCEL_ACTIVITY));
            assertEquals("P5D", event.getString(TIME_DURATION));
            assertEquals(List.of(), problems.messages());
        }
    }

    @Test
    void leavesABpmnValueTheDiagramDoesNotGiveUnwritten() throws Exception
    {
        // Nothing written: cancelActivity is autocreated as true
        final NodeBuilder root = withTimerBoundaryEvent();

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState after = parse(root, boundaryEvent("", ""));

            final NodeState event = after.getChildNode(TASK).getChildNode(DEADLINE);
            assertTrue(event.exists());
            assertFalse(event.hasProperty(CANCEL_ACTIVITY));
            assertFalse(event.hasProperty(TIME_DURATION));
            assertEquals(List.of(), problems.messages());
        }
    }

    @Test
    void readsNothingAlongAPathTheDiagramDoesNotHave() throws Exception
    {
        final NodeBuilder root = VocabularyFixture.repository();
        final NodeBuilder type = VocabularyFixture.entry(root, "Scripted", SERVICE_TASK_ELEMENT, ACTIVITY, 0);
        bpmnParameter(type, "scriptFormat", Parameter.LOCATION_BPMN_ATTRIBUTE, "bpmn:ioSpecification/bpmn:dataInput");

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState after = parse(root, "    <bpmn:serviceTask id=\"" + TASK + "\"/>\n");

            assertTrue(after.getChildNode(TASK).exists());
            assertFalse(after.getChildNode(TASK).hasProperty("scriptFormat"));
            assertEquals(List.of(), problems.messages());
        }
    }

    @Test
    void readsAListFromRepeatedBpmnElementsAndABpmnAttribute() throws Exception
    {
        final NodeBuilder root = VocabularyFixture.repository();
        final NodeBuilder type = VocabularyFixture.entry(root, "Scripted", SERVICE_TASK_ELEMENT, ACTIVITY, 0);
        bpmnParameter(type, "script", Parameter.LOCATION_BPMN_ELEMENT, null).setProperty("maxValues", 0L);
        bpmnParameter(type, "scriptFormat", Parameter.LOCATION_BPMN_ATTRIBUTE, null);

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState after = parse(root, "    <bpmn:serviceTask id=\"" + TASK + "\" scriptFormat=\" js \">\n"
                + "      <bpmn:script>one</bpmn:script><bpmn:script> </bpmn:script><bpmn:script>two</bpmn:script>\n"
                + "    </bpmn:serviceTask>\n");

            assertEquals(List.of("one", "two"),
                after.getChildNode(TASK).getProperty("script").getValue(Type.STRINGS));
            assertEquals("js", after.getChildNode(TASK).getString("scriptFormat"));
            assertEquals(List.of(), problems.messages());
        }
    }

    @Test
    void appliesASequenceFlowsEntryButRefusesItsConditionExpression() throws Exception
    {
        final NodeBuilder root = withTimerBoundaryEvent();
        root.child(WORKFLOW_TYPES).child(SEQUENCE_FLOW).setProperty("jcrProperties", "{\"weight\": 2}");

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState after = parse(root, "    <bpmn:userTask id=\"" + TASK + "\"/>\n"
                + "    <bpmn:userTask id=\"next\"/>\n"
                + "    <bpmn:sequenceFlow id=\"flow\" sourceRef=\"" + TASK + "\" targetRef=\"next\">\n"
                + "      <bpmn:conditionExpression>approved</bpmn:conditionExpression>\n"
                + "    </bpmn:sequenceFlow>\n");

            final NodeState flow = after.getChildNode(TASK).getChildNode("flow");
            assertEquals("wf:SequenceFlow", flow.getName(PRIMARY_TYPE));
            assertFalse(flow.hasProperty(CONDITION_EXPRESSION));
            assertEquals(2L, flow.getLong("weight"));
            assertEquals("next", flow.getString("targetRef"));
            assertFalse(flow.hasProperty(FLOW_NODE_TYPE));
            assertEquals(List.of("sequence flow guard is a conditionExpression, not a cond:condition"),
                problems.names());
        }
    }

    @Test
    void storesASequenceFlowNoEntryMatchesAndSaysSo() throws Exception
    {
        final NodeBuilder root = withGenericServiceTask();

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState after = parse(root, serviceTask(TASK, "") + serviceTask("next", "")
                + "    <bpmn:sequenceFlow id=\"flow\" sourceRef=\"" + TASK + "\" targetRef=\"next\"/>\n");

            final NodeState flow = after.getChildNode(TASK).getChildNode("flow");
            assertEquals("next", flow.getString("targetRef"));
            assertEquals(List.of("no FlowNodeType configured for a BPMN element"), problems.names());
        }
    }

    @Test
    void buildsASystemViewSubtreeUnderABoundaryEvent() throws Exception
    {
        final NodeBuilder root = withTimerBoundaryEvent();

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState after = parse(root, boundaryEvent("",
                "<bpmn:timeDuration>P5D</bpmn:timeDuration>").replace("      <bpmn:timerEventDefinition>",
                    "      <bpmn:extensionElements><sv:node xmlns:sv=\"http://www.jcp.org/jcr/sv/1.0\""
                    + " sv:name=\"cond:condition\"><sv:property sv:name=\"jcr:primaryType\" sv:type=\"Name\">"
                    + "<sv:value>cond:SingleCondition</sv:value></sv:property></sv:node></bpmn:extensionElements>\n"
                    + "      <bpmn:timerEventDefinition>"));

            final NodeState condition = after.getChildNode(TASK).getChildNode(DEADLINE).getChildNode("cond:condition");
            assertEquals("cond:SingleCondition", condition.getName(PRIMARY_TYPE));
            assertEquals(List.of(), problems.messages());
        }
    }

    /** An empty vocabulary plus the generic service task entry, the fallback for a task naming no handler element. */
    private NodeBuilder withGenericServiceTask()
    {
        final NodeBuilder root = VocabularyFixture.repository();
        VocabularyFixture.attributeParameter(
            VocabularyFixture.entry(root, GENERIC_SERVICE_TASK, SERVICE_TASK_ELEMENT, ACTIVITY, 0), HANDLER, TEXT, 1);
        return root;
    }

    /** An empty vocabulary plus a per-handler entry taking one required state and a required list of them. */
    private NodeBuilder withSetState()
    {
        final NodeBuilder root = VocabularyFixture.repository();
        final NodeBuilder type = VocabularyFixture.handler(root, "SetState", "iap:" + SET_STATE);
        VocabularyFixture.parameter(type, TO_STATE, TEXT, 1, 1);
        VocabularyFixture.parameter(type, FROM_STATES, TEXT, 1, 0);
        return root;
    }

    /**
     * An empty vocabulary plus a user task, the shipped timer boundary event that may be attached to it, and the
     * shipped sequence flow, which declares no parameters.
     */
    private NodeBuilder withTimerBoundaryEvent()
    {
        final NodeBuilder root = VocabularyFixture.repository();
        VocabularyFixture.entry(root, "UserTask", "bpmn:userTask", ACTIVITY, 0);
        final NodeBuilder timer = VocabularyFixture.entry(root, "TimerBoundaryEvent", "bpmn:boundaryEvent",
            "wf:IntermediateCatchingEvent", 10).setProperty("xmlChildElement", "bpmn:timerEventDefinition");
        bpmnParameter(timer, TIME_DURATION, Parameter.LOCATION_BPMN_ELEMENT, "bpmn:timerEventDefinition");
        bpmnParameter(timer, CANCEL_ACTIVITY, Parameter.LOCATION_BPMN_ATTRIBUTE, null)
            .setProperty("dataType", Parameter.TYPE_BOOLEAN);
        VocabularyFixture.entry(root, SEQUENCE_FLOW, "bpmn:sequenceFlow", "wf:SequenceFlow", 0);
        return root;
    }

    /** One optional text value held in BPMN's own markup, at the end of the given path when there is one. */
    private NodeBuilder bpmnParameter(final NodeBuilder type, final String name, final String location,
        final String path)
    {
        final NodeBuilder parameter = VocabularyFixture.parameter(type, name, TEXT, 0, 1).setProperty(XML_LOCATION,
            location);
        return path == null ? parameter : parameter.setProperty("xmlPath", path);
    }

    /** A user task and a timer boundary event attached to it, carrying the given attributes and timer content. */
    private static String boundaryEvent(final String attributes, final String timer)
    {
        return "    <bpmn:userTask id=\"" + TASK + "\"/>\n"
            + "    <bpmn:boundaryEvent id=\"" + DEADLINE + "\" attachedToRef=\"" + TASK + "\"" + attributes + ">\n"
            + "      <bpmn:timerEventDefinition>" + timer + "</bpmn:timerEventDefinition>\n"
            + "    </bpmn:boundaryEvent>\n";
    }

    /** An empty vocabulary plus a per-handler entry taking a reference that must name a mail template. */
    private NodeBuilder withTemplateReference()
    {
        final NodeBuilder root = VocabularyFixture.repository();
        final NodeBuilder type = VocabularyFixture.handler(root, "Notify", "notify");
        VocabularyFixture.parameter(type, TEMPLATE, Parameter.TYPE_REFERENCE, 1, 1)
            .setProperty("referenceType", MAIL_TEMPLATE);
        return root;
    }

    /** A node a reference parameter can name, together with what the node type registry says its type is. */
    private NodeBuilder target(final NodeBuilder root, final String name, final String nodeType)
    {
        NodeTypesFixture.type(root, nodeType);
        return root.child(TARGETS).child(name).setProperty(PRIMARY_TYPE, nodeType, Type.NAME);
    }

    /** A complete {@code setState} extension element, moving from a draft to the given state. */
    private static String setState(final String toState)
    {
        return "<iap:setState toState=\"" + toState + "\"><iap:fromStates>DRAFT</iap:fromStates>"
            + "</iap:setState>";
    }
}

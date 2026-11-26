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
import java.util.stream.StreamSupport;

import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.junit.jupiter.api.Test;

import io.uhndata.iap.utils.NodeTypeUtils;
import io.uhndata.iap.workflows.ParseFixture.Parsed;
import io.uhndata.iap.workflows.models.Parameter;

import static io.uhndata.iap.workflows.ParseFixture.parse;
import static io.uhndata.iap.workflows.ParseFixture.parsed;
import static io.uhndata.iap.workflows.ParseFixture.problemLines;
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
 * wrong is reported and parsing carries on, so the report is the only sign a mistake was noticed.</p>
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

    private static final String TEXT = Parameter.TEXT_DATA_TYPE;

    private static final String FLOW_NODE_TYPE = "flowNodeType";

    private static final String SERVICE_TASK_ELEMENT = "bpmn:serviceTask";

    private static final String XML_LOCATION = "xmlLocation";

    private static final String PARAMETER_DETAIL = "parameter";

    private static final String ELEMENT_DETAIL = "element";

    private static final String GENERIC_SERVICE_TASK = "ServiceTask";

    private static final String SET_STATE = "setState";

    private static final String TO_STATE = "toState";

    private static final String FROM_STATES = "fromStates";

    private static final String COLOUR = "colour";

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

    private static final String CHILD_ORDER = ":childOrder";

    private static final String URGENT = "urgent";

    private static final String SERVICE_TASK_OPEN = "    <bpmn:serviceTask id=\"";

    @Test
    void reportsOnlyTheExtensionElementsBesideTheMatchedOne() throws Exception
    {
        final NodeBuilder root = withSetState();

        final Parsed parsed = parsed(root, serviceTask("<iap:setState toState=\"ACTIVE\">"
            + "<iap:fromStates>DRAFT</iap:fromStates></iap:setState>\n"
            + "        <iap:notify urgency=\"immediate\"/>"));
        final NodeState after = parsed.version();

        assertEquals("ACTIVE", after.getChildNode(TASK).getString(TO_STATE));
        assertEquals(List.of(
            "an IAP extension element belongs to no matching FlowNodeType (task1, extensionElement=notify,"
                + " flowNodeType=SetState)"), parsed.problems());
    }

    @Test
    void leavesExtensionElementsInOtherNamespacesAlone() throws Exception
    {
        final NodeBuilder root = withGenericServiceTask();

        final Parsed parsed = parsed(root, serviceTask(
            "<camunda:executionListener xmlns:camunda=\"urn:camunda\" event=\"start\"/>"));
        final NodeState after = parsed.version();

        assertTrue(after.getChildNode(TASK).exists());
        assertEquals(List.of(), parsed.problems());
    }

    @Test
    void breaksATieOfEqualPrioritiesByEntryName() throws Exception
    {
        final NodeBuilder root = VocabularyFixture.repository();
        VocabularyFixture.entry(root, "ZebraTask", SERVICE_TASK_ELEMENT, ACTIVITY, 10);
        VocabularyFixture.entry(root, "AardvarkTask", SERVICE_TASK_ELEMENT, ACTIVITY, 10);

        final Parsed parsed = parsed(root, SERVICE_TASK_OPEN + TASK + "\"/>\n");
        final NodeState after = parsed.version();

        assertEquals(VocabularyFixture.id("AardvarkTask"),
            after.getChildNode(TASK).getProperty(FLOW_NODE_TYPE).getValue(Type.REFERENCE));
        assertEquals(List.of(), parsed.problems());
    }

    @Test
    void trimsASingleValuedAttribute() throws Exception
    {
        // Whitespace around an attribute value is the diagram's formatting, not part of the state a handler is
        // then asked to move a version to
        final NodeBuilder root = withSetState();
        VocabularyFixture.attributeParameter(root.child(WORKFLOW_TYPES).child("SetState"), COLOUR, TEXT, 1);

        final NodeState after = parse(root, SERVICE_TASK_OPEN + TASK + "\" iap:colour=\" green \">\n"
            + "      <bpmn:extensionElements>\n"
            + "        <iap:setState toState=\" ACTIVE\">"
            + "<iap:fromStates>DRAFT</iap:fromStates></iap:setState>\n"
            + "      </bpmn:extensionElements>\n"
            + "    </bpmn:serviceTask>\n");

        assertEquals("ACTIVE", after.getChildNode(TASK).getString(TO_STATE));
        assertEquals("green", after.getChildNode(TASK).getString(COLOUR));
    }

    @Test
    void treatsAnAllWhitespaceValueAsNoValueAtAll() throws Exception
    {
        // A required parameter left empty is reported missing, and an optional one is not written at all
        final NodeBuilder root = withSetState();

        final Parsed parsed = parsed(root, serviceTask("<iap:setState toState=\"  \">"
            + "<iap:fromStates>DRAFT</iap:fromStates></iap:setState>"));
        final NodeState after = parsed.version();

        assertFalse(after.getChildNode(TASK).hasProperty(TO_STATE));
        assertEquals(List.of(
            "a required parameter has no value (task1, parameter=toState)"), parsed.problems());
    }

    @Test
    void reportsAListWrittenAsAnAttribute() throws Exception
    {
        final NodeBuilder root = withSetState();

        final Parsed parsed = parsed(root,
            serviceTask("<iap:setState toState=\"ACTIVE\" fromStates=\"DRAFT, TRIAL\"/>"));
        final NodeState after = parsed.version();

        assertFalse(after.getChildNode(TASK).hasProperty(FROM_STATES));
        assertEquals(List.of(
            "a parameter taking a list has it written as an attribute instead (task1, parameter=fromStates)",
            "a required parameter has no value (task1, parameter=fromStates)"), parsed.problems());
    }

    @Test
    void reportsASingleValueWrittenAsChildElements() throws Exception
    {
        final NodeBuilder root = withSetState();

        final Parsed parsed = parsed(root, serviceTask("<iap:setState>"
            + "<iap:toState>ACTIVE</iap:toState><iap:fromStates>DRAFT</iap:fromStates></iap:setState>"));
        final NodeState after = parsed.version();

        assertFalse(after.getChildNode(TASK).hasProperty(TO_STATE));
        assertEquals(List.of(
            "a single-valued parameter is written as child elements (task1, parameter=toState)",
            "a required parameter has no value (task1, parameter=toState)"), parsed.problems());
    }

    @Test
    void acceptsAReferenceToANodeCarryingTheDeclaredTypeAsAMixin() throws Exception
    {
        // A node type constraint is satisfied by a mixin as much as by a primary type
        final NodeBuilder root = withTemplateReference();
        target(root, "byMixin", "sling:Folder").setProperty(MIXIN_TYPES, List.of(MAIL_TEMPLATE), Type.NAMES);
        target(root, "bySubtypeOfMixin", "sling:Folder")
            .setProperty(MIXIN_TYPES, List.of("mail:HtmlTemplate"), Type.NAMES);
        NodeTypesFixture.type(root, "mail:HtmlTemplate", MAIL_TEMPLATE);

        final Parsed parsed = parsed(root,
            serviceTask("byMixin", "<iap:notify template=\"/targets/byMixin\"/>")
            + serviceTask("bySubtype", "<iap:notify template=\"/targets/bySubtypeOfMixin\"/>"));
        final NodeState after = parsed.version();

        assertEquals("/targets/byMixin", after.getChildNode("byMixin").getString(TEMPLATE));
        assertEquals("/targets/bySubtypeOfMixin", after.getChildNode("bySubtype").getString(TEMPLATE));
        assertEquals(List.of(), parsed.problems());
    }

    @Test
    void reportsAReferenceWhoseMixinsAreAllOfTheWrongType() throws Exception
    {
        final NodeBuilder root = withTemplateReference();
        target(root, "folder", "sling:Folder").setProperty(MIXIN_TYPES, List.of("mix:created"), Type.NAMES);

        final Parsed parsed = parsed(root, serviceTask("<iap:notify template=\"/targets/folder\"/>"));
        final NodeState after = parsed.version();

        assertEquals("/targets/folder", after.getChildNode(TASK).getString(TEMPLATE),
            "kept as written: a path that is wrong is easier to repair than configuration silently lost");
        assertEquals(List.of(
            "a reference parameter names a node of the wrong type (task1, parameter=template,"
                + " value=/targets/folder)"), parsed.problems());
    }

    @Test
    void readsAParameterOfAnEntryWithNoExtensionElementAsAnAttribute() throws Exception
    {
        final NodeBuilder root = VocabularyFixture.repository();
        final NodeBuilder type = VocabularyFixture.entry(root, GENERIC_SERVICE_TASK, SERVICE_TASK_ELEMENT, ACTIVITY, 0);
        VocabularyFixture.parameter(type, HANDLER, TEXT, 0, 1);

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState after =
                parse(root, SERVICE_TASK_OPEN + TASK + "\" iap:handler=\"checkBudget\"/>\n");

            assertEquals("checkBudget", after.getChildNode(TASK).getString(HANDLER));
            assertEquals(List.of("a non-attribute parameter on an entry with no extension element"),
                problems.names());
            assertEquals(TASK, problems.all().get(0).details().get(ELEMENT_DETAIL));
            assertEquals(HANDLER, problems.all().get(0).details().get(PARAMETER_DETAIL));
        }
    }

    @Test
    void saysNothingAboutAMalformedEntryNoDiagramUses() throws Exception
    {
        // An unusable corner of the vocabulary must not fill the log on every save of a workflow that never
        // touches it
        final NodeBuilder root = withGenericServiceTask();
        VocabularyFixture.parameter(VocabularyFixture.entry(root, "Unused", "bpmn:sendTask", ACTIVITY, 0), "recipients",
            TEXT, 0, 0);

        final Parsed parsed = parsed(root, SERVICE_TASK_OPEN + TASK + "\"/>\n");
        final NodeState after = parsed.version();

        assertTrue(after.getChildNode(TASK).exists());
        assertEquals(List.of(), parsed.problems());
    }

    @Test
    void storesNothingForAParameterTheDiagramLeavesOut() throws Exception
    {
        // Left to the node type's default
        final NodeBuilder root = withGenericServiceTask();

        final NodeState after = parse(root, SERVICE_TASK_OPEN + TASK + "\"/>\n");

        assertNull(after.getChildNode(TASK).getString(HANDLER));
    }

    @Test
    void passesOverAMatchingEntryThatCannotProduceANode() throws Exception
    {
        // A half-authored entry must not take the element away from the next candidate, which can store it
        final NodeBuilder root = withGenericServiceTask();
        // Its parameters are read with the rest of the vocabulary, though no node of its type can hold them
        VocabularyFixture.parameter(VocabularyFixture.entry(root, "Broken", SERVICE_TASK_ELEMENT, ACTIVITY, 10)
            .removeProperty("jcrNodeType"), "note", TEXT, 0, 1);

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState after = parse(root, SERVICE_TASK_OPEN + TASK + "\"/>\n");

            assertEquals(VocabularyFixture.id(GENERIC_SERVICE_TASK),
                after.getChildNode(TASK).getProperty(FLOW_NODE_TYPE).getValue(Type.REFERENCE));
            assertEquals(List.of("FlowNodeType declares no jcrNodeType"), problems.names());
            assertEquals("Broken", problems.all().get(0).details().get(FLOW_NODE_TYPE));
        }
    }

    @Test
    void reportsASecondCopyOfTheMatchedExtensionElement() throws Exception
    {
        final NodeBuilder root = withSetState();

        final Parsed parsed = parsed(root, serviceTask(setState("ACTIVE") + setState("RETIRED")));
        final NodeState after = parsed.version();

        assertEquals("ACTIVE", after.getChildNode(TASK).getString(TO_STATE));
        assertEquals(List.of(
            "an IAP extension element is written more than once (task1, extensionElement=setState,"
                + " flowNodeType=SetState)"), parsed.problems());
    }

    @Test
    void reportsAnIapAttributeNoParameterReads() throws Exception
    {
        // A handler the generic entry dispatches to, with configuration it has no parameter for
        final NodeBuilder root = withGenericServiceTask();

        final Parsed parsed = parsed(root, SERVICE_TASK_OPEN + TASK + "\" name=\"Move it\""
            + " iap:handler=\"moveContent\" iap:within=\"wf/Workflows\"/>\n");
        final NodeState after = parsed.version();

        assertEquals("moveContent", after.getChildNode(TASK).getString(HANDLER));
        assertFalse(after.getChildNode(TASK).hasProperty("within"));
        assertEquals(List.of(
            "an IAP attribute is read by no parameter of its entry (task1, attribute=within,"
                + " flowNodeType=ServiceTask)"), parsed.problems());
    }

    @Test
    void reportsAGenericTaskNamingAHandlerThatHasAnEntryOfItsOwn() throws Exception
    {
        final NodeBuilder root = withSetState();
        VocabularyFixture.attributeParameter(
            VocabularyFixture.entry(root, GENERIC_SERVICE_TASK, SERVICE_TASK_ELEMENT, ACTIVITY, 0), HANDLER, TEXT, 1);

        final Parsed parsed =
            parsed(root, SERVICE_TASK_OPEN + TASK + "\" iap:handler=\"" + SET_STATE + "\"/>\n");

        assertEquals(List.of(
            "a task names a handler by attribute instead of its element (task1, handler=setState)"), parsed.problems());
    }

    @Test
    void reportsAHandlerAttributeBesideAHandlerElement() throws Exception
    {
        // The element decides the handler, so an attribute naming another one is a disagreement nothing settles
        final NodeBuilder root = withSetState();

        final Parsed parsed = parsed(root, SERVICE_TASK_OPEN + TASK + "\" iap:handler=\"notify\">\n"
            + "      <bpmn:extensionElements>" + setState("ACTIVE") + "</bpmn:extensionElements>\n"
            + "    </bpmn:serviceTask>\n");

        assertEquals(List.of(
            "an IAP attribute is read by no parameter of its entry (task1, attribute=handler,"
                + " flowNodeType=SetState)"), parsed.problems());
    }

    @Test
    void reportsValuesWrittenWhereTheirParameterDoesNotLive() throws Exception
    {
        // Each of these is a value the panel would have written in the other place, or a name it never offered
        final NodeBuilder root = withSetState();
        VocabularyFixture.attributeParameter(root.child(WORKFLOW_TYPES).child("SetState"), COLOUR, TEXT, 1);

        final Parsed parsed = parsed(root, SERVICE_TASK_OPEN + TASK + "\" iap:toState=\"RETIRED\">\n"
            + "      <bpmn:extensionElements><iap:setState toState=\"ACTIVE\" colour=\"green\">"
            + "<iap:fromStates>DRAFT</iap:fromStates><iap:fromState>TRIAL</iap:fromState>"
            + "</iap:setState></bpmn:extensionElements>\n"
            + "    </bpmn:serviceTask>\n");

        assertEquals(List.of(
            "an IAP attribute is read by no parameter of its entry (task1, attribute=toState,"
                + " flowNodeType=SetState)",
            "an IAP extension attribute is read by no parameter of its entry (task1, attribute=colour,"
                + " flowNodeType=SetState)",
            "an IAP extension child is read by no parameter of its entry (task1, child=fromState,"
                + " flowNodeType=SetState)"), parsed.problems());
    }

    @Test
    void leavesBpmnsOwnAttributesAndNamespaceDeclarationsAlone() throws Exception
    {
        final NodeBuilder root = withSetState();

        final Parsed parsed = parsed(root, SERVICE_TASK_OPEN + TASK + "\" name=\"Activate\">\n"
            + "      <bpmn:extensionElements><iap:setState xmlns:x=\"urn:x\" x:note=\"n\""
            + " toState=\"ACTIVE\"><iap:fromStates>DRAFT</iap:fromStates></iap:setState>"
            + "</bpmn:extensionElements>\n"
            + "    </bpmn:serviceTask>\n");

        assertEquals(List.of(), parsed.problems());
    }

    @Test
    void reportsFewerValuesThanTheMinimum() throws Exception
    {
        final NodeBuilder root = withSetState();
        root.child(WORKFLOW_TYPES).child("SetState").child(FROM_STATES).setProperty("minValues", 2L);

        final Parsed parsed = parsed(root, serviceTask(setState("ACTIVE")));
        final NodeState after = parsed.version();

        assertEquals(List.of("DRAFT"), after.getChildNode(TASK).getProperty(FROM_STATES).getValue(Type.STRINGS));
        assertEquals(List.of(
            "a parameter has fewer values than it requires (task1, parameter=fromStates)"), parsed.problems());
    }

    @Test
    void keepsMoreValuesThanTheMaximumAndSaysSo() throws Exception
    {
        // Dropping the extras would hide the mistake and change what the workflow does
        final NodeBuilder root = withSetState();
        root.child(WORKFLOW_TYPES).child("SetState").child(FROM_STATES).setProperty("maxValues", 2L);

        final Parsed parsed = parsed(root, serviceTask("<iap:setState toState=\"ACTIVE\"><iap:fromStates>DRAFT"
            + "</iap:fromStates><iap:fromStates>TRIAL</iap:fromStates><iap:fromStates>ACTIVE</iap:fromStates>"
            + "</iap:setState>"));
        final NodeState after = parsed.version();

        assertEquals(List.of("DRAFT", "TRIAL", "ACTIVE"),
            after.getChildNode(TASK).getProperty(FROM_STATES).getValue(Type.STRINGS));
        assertEquals(List.of(
            "a parameter has more values than it accepts (task1, parameter=fromStates)"), parsed.problems());
    }

    @Test
    void readsAParameterDeclaringNothingAsOneTextValue() throws Exception
    {
        // A declaration without the properties its node type autocreates, as stored under another node type
        final NodeBuilder root = withGenericServiceTask();
        root.child(WORKFLOW_TYPES).child(GENERIC_SERVICE_TASK).child("colour")
            .setProperty(PRIMARY_TYPE, PARAMETER_NODETYPE, Type.NAME)
            .setProperty(XML_LOCATION, "attribute");

        final NodeState after = parse(root, SERVICE_TASK_OPEN + TASK + "\" iap:colour=\"a, b\"/>\n");

        final PropertyState colour = after.getChildNode(TASK).getProperty("colour");
        assertFalse(colour.isArray());
        assertEquals("a, b", colour.getValue(Type.STRING));
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
            final NodeState after = parse(root, SERVICE_TASK_OPEN + TASK + "\" iap:handler=\"review\""
                + " iap:outcomes=\"approved\"/>\n");

            assertEquals("review", after.getChildNode(TASK).getString(HANDLER));
            assertFalse(after.getChildNode(TASK).hasProperty("outcomes"));
            assertEquals(List.of("a parameter disagrees with the node type it is stored in"),
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
        VocabularyFixture.attributeParameter(type, "urgent", Parameter.BOOLEAN_DATA_TYPE, 1);
        VocabularyFixture.attributeParameter(type, "retries", Parameter.LONG_DATA_TYPE, 0);
        VocabularyFixture.attributeParameter(type, "note", TEXT, 1);

        final Parsed parsed = parsed(root, SERVICE_TASK_OPEN + TASK + "\" iap:urgent=\"true\""
            + " iap:retries=\"1, 2\" iap:note=\"n\"/>\n");
        final NodeState task = parsed.version().getChildNode(TASK);

        assertTrue(task.getBoolean("urgent"));
        assertEquals(List.of(1L, 2L), task.getProperty("retries").getValue(Type.LONGS));
        assertEquals("n", task.getString("note"));
        assertEquals(List.of(), parsed.problems());
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

        final Parsed parsed = parsed(root, SERVICE_TASK_OPEN + TASK + "\" iap:urgency=\"later\"/>\n");
        final NodeState after = parsed.version();

        assertEquals("later", after.getChildNode(TASK).getString("urgency"));
        assertEquals(List.of(
            "a parameter has a value it does not offer (task1, parameter=urgency, value=later)"), parsed.problems());
    }

    @Test
    void asksForAnAdministratorOnlyWhenTheVocabularyIsAtFault() throws Exception
    {
        final NodeBuilder broken = withGenericServiceTask();
        broken.child(WORKFLOW_TYPES).child(GENERIC_SERVICE_TASK).child("note")
            .setProperty(PRIMARY_TYPE, "nt:unstructured", Type.NAME)
            .setProperty("sling:resourceType", Parameter.RESOURCE_TYPE);
        final NodeBuilder required = VocabularyFixture.repository();
        VocabularyFixture.parameter(VocabularyFixture.handler(required, "Escalate", "escalate"), "count",
            Parameter.LONG_DATA_TYPE, 1, 1);

        final List<String> vocabulary = problemLines(broken, SERVICE_TASK_OPEN + TASK + "\"/>\n");
        final List<String> diagram = problemLines(required, serviceTask("<iap:escalate/>"));

        assertEquals(1, vocabulary.size());
        assertTrue(vocabulary.get(0).endsWith(". Please contact an administrator"));
        assertEquals(1, diagram.size());
        assertTrue(diagram.get(0).startsWith("a required parameter has no value"));
        assertFalse(diagram.get(0).contains("administrator"));
    }

    @Test
    void reportsAParameterDeclaredByItsResourceTypeAlone() throws Exception
    {
        final NodeBuilder root = withGenericServiceTask();
        root.child(WORKFLOW_TYPES).child(GENERIC_SERVICE_TASK).child("note")
            .setProperty(PRIMARY_TYPE, "nt:unstructured", Type.NAME)
            .setProperty("sling:resourceType", Parameter.RESOURCE_TYPE);

        try (RecordedProblems problems = new RecordedProblems()) {
            parse(root, SERVICE_TASK_OPEN + TASK + "\"/>\n");

            assertEquals(List.of("a vocabulary entry holds a parameter of the wrong node type"),
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

        final Parsed parsed = parsed(root, SERVICE_TASK_OPEN + TASK + "\"/>\n");
        final NodeState after = parsed.version();

        assertEquals(VocabularyFixture.id(GENERIC_SERVICE_TASK),
            after.getChildNode(TASK).getProperty(FLOW_NODE_TYPE).getValue(Type.REFERENCE));
        assertEquals(List.of(), parsed.problems());
    }

    @Test
    void readsAParameterInALocationItCannotNameInsideItsExtensionElement() throws Exception
    {
        // Only a node of another type can hold such a location; the node type constrains it
        final NodeBuilder root = withSetState();
        root.child(WORKFLOW_TYPES).child("SetState").child(TO_STATE).setProperty(XML_LOCATION, "elsewhere");

        final Parsed parsed = parsed(root, serviceTask(setState("ACTIVE")));
        final NodeState after = parsed.version();

        assertEquals("ACTIVE", after.getChildNode(TASK).getString(TO_STATE));
        assertEquals(List.of(), parsed.problems());
    }

    @Test
    void dropsAValueItsTypeCannotReadAndSaysSo() throws Exception
    {
        final NodeBuilder root = VocabularyFixture.repository();
        final NodeBuilder type = VocabularyFixture.handler(root, "Escalate", "escalate");
        VocabularyFixture.parameter(type, "retries", Parameter.LONG_DATA_TYPE, 0, 0);

        final Parsed parsed = parsed(root, serviceTask("<iap:escalate>"
            + "<iap:retries>2</iap:retries><iap:retries>often</iap:retries></iap:escalate>"));
        final NodeState task = parsed.version().getChildNode(TASK);

        assertEquals(List.of(2L), task.getProperty("retries").getValue(Type.LONGS));
        assertEquals(List.of(
            "a parameter has a value that is not a whole number (task1, parameter=retries, value=often)"),
            parsed.problems());
    }

    @Test
    void reportsARequiredParameterWhoseEveryValueIsDroppedAsMissing() throws Exception
    {
        final NodeBuilder root = VocabularyFixture.repository();
        VocabularyFixture.parameter(VocabularyFixture.handler(root, "Escalate", "escalate"), "count",
            Parameter.LONG_DATA_TYPE, 1, 1);

        final Parsed parsed = parsed(root, serviceTask("<iap:escalate count=\"abc\"/>"));
        final NodeState task = parsed.version().getChildNode(TASK);

        assertFalse(task.hasProperty("count"));
        assertEquals(List.of(
            "a parameter has a value that is not a whole number (task1, parameter=count, value=abc)",
            "a required parameter has no value (task1, parameter=count)"), parsed.problems());
    }

    @Test
    void keepsAReferenceNamingNothingAndSaysSo() throws Exception
    {
        // A relative path names nothing either
        final NodeBuilder root = withTemplateReference();
        target(root, "folder", MAIL_TEMPLATE);

        final Parsed parsed = parsed(root, serviceTask("missing", "<iap:notify template=\"/targets/missing\"/>")
            + serviceTask("relative", "<iap:notify template=\"targets/folder\"/>")
            + serviceTask("doubled", "<iap:notify template=\"/targets//folder\"/>")
            + serviceTask("trailing", "<iap:notify template=\"/targets/folder/\"/>"));
        final NodeState after = parsed.version();

        assertEquals("/targets/missing", after.getChildNode("missing").getString(TEMPLATE));
        assertEquals("targets/folder", after.getChildNode("relative").getString(TEMPLATE));
        // Oak refuses an empty step outright, which must cost the value its check rather than the save
        assertEquals("/targets//folder", after.getChildNode("doubled").getString(TEMPLATE));
        assertEquals("/targets/folder/", after.getChildNode("trailing").getString(TEMPLATE));
        assertEquals(List.of(
            "a reference parameter names a node that does not exist (missing, parameter=template,"
                + " value=/targets/missing)",
            "a reference parameter names a node that does not exist (relative, parameter=template,"
                + " value=targets/folder)",
            "a reference parameter names a node that does not exist (doubled, parameter=template,"
                + " value=/targets//folder)",
            "a reference parameter names a node that does not exist (trailing, parameter=template,"
                + " value=/targets/folder/)"), parsed.problems());
    }

    @Test
    void readsATimerBoundaryEventFromBpmnsOwnMarkup() throws Exception
    {
        final NodeBuilder root = withTimerBoundaryEvent();

        final Parsed parsed = parsed(root, boundaryEvent(" cancelActivity=\"false\"",
            "<bpmn:timeDuration xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
                + " xsi:type=\"bpmn:tFormalExpression\"> P5D </bpmn:timeDuration>"));
        final NodeState after = parsed.version();

        final NodeState event = after.getChildNode(TASK).getChildNode(DEADLINE);
        assertEquals(Type.BOOLEAN, event.getProperty(CANCEL_ACTIVITY).getType());
        assertFalse(event.getBoolean(CANCEL_ACTIVITY));
        assertEquals("P5D", event.getString(TIME_DURATION));
        assertEquals(List.of(), parsed.problems());
    }

    @Test
    void leavesABpmnValueTheDiagramDoesNotGiveUnwritten() throws Exception
    {
        // Nothing written: cancelActivity is autocreated as true
        final NodeBuilder root = withTimerBoundaryEvent();

        final Parsed parsed = parsed(root, boundaryEvent("", ""));
        final NodeState after = parsed.version();

        final NodeState event = after.getChildNode(TASK).getChildNode(DEADLINE);
        assertTrue(event.exists());
        assertFalse(event.hasProperty(CANCEL_ACTIVITY));
        assertFalse(event.hasProperty(TIME_DURATION));
        assertEquals(List.of(), parsed.problems());
    }

    @Test
    void readsNothingAlongAPathTheDiagramDoesNotHave() throws Exception
    {
        final NodeBuilder root = VocabularyFixture.repository();
        final NodeBuilder type = VocabularyFixture.entry(root, "Scripted", SERVICE_TASK_ELEMENT, ACTIVITY, 0);
        bpmnParameter(type, "scriptFormat", Parameter.BPMN_ATTRIBUTE_LOCATION, "bpmn:ioSpecification/bpmn:dataInput");

        final Parsed parsed = parsed(root, SERVICE_TASK_OPEN + TASK + "\"/>\n");
        final NodeState after = parsed.version();

        assertTrue(after.getChildNode(TASK).exists());
        assertFalse(after.getChildNode(TASK).hasProperty("scriptFormat"));
        assertEquals(List.of(), parsed.problems());
    }

    @Test
    void readsAListFromRepeatedBpmnElementsAndABpmnAttribute() throws Exception
    {
        final NodeBuilder root = VocabularyFixture.repository();
        final NodeBuilder type = VocabularyFixture.entry(root, "Scripted", SERVICE_TASK_ELEMENT, ACTIVITY, 0);
        bpmnParameter(type, "script", Parameter.BPMN_ELEMENT_LOCATION, null).setProperty("maxValues", 0L);
        bpmnParameter(type, "scriptFormat", Parameter.BPMN_ATTRIBUTE_LOCATION, null);

        final Parsed parsed = parsed(root, SERVICE_TASK_OPEN + TASK + "\" scriptFormat=\" js \">\n"
            + "      <bpmn:script>one</bpmn:script><bpmn:script> </bpmn:script><bpmn:script>two</bpmn:script>\n"
            + "    </bpmn:serviceTask>\n");
        final NodeState after = parsed.version();

        assertEquals(List.of("one", "two"),
            after.getChildNode(TASK).getProperty("script").getValue(Type.STRINGS));
        assertEquals("js", after.getChildNode(TASK).getString("scriptFormat"));
        assertEquals(List.of(), parsed.problems());
    }

    @Test
    void reportsTheBpmnAnEntryDoesNotRead() throws Exception
    {
        final NodeBuilder root = withTimerBoundaryEvent();

        final Parsed parsed = parsed(root, "    <bpmn:userTask id=\"" + TASK + "\">\n"
            + "      <bpmn:multiInstanceLoopCharacteristics/>\n"
            + "    </bpmn:userTask>\n"
            + "    <bpmn:boundaryEvent id=\"" + DEADLINE + "\" attachedToRef=\"" + TASK + "\""
            + " parallelMultiple=\"true\">\n"
            + "      <bpmn:timerEventDefinition unit=\"days\"><bpmn:timeCycle>R3/P1D</bpmn:timeCycle>"
            + "</bpmn:timerEventDefinition>\n"
            + "    </bpmn:boundaryEvent>\n");

        assertEquals(List.of(
            "a BPMN element is read by no parameter of its entry (task1,"
                + " child=multiInstanceLoopCharacteristics, flowNodeType=UserTask)",
            "a BPMN attribute is read by no parameter of its entry (deadline, attribute=parallelMultiple,"
                + " flowNodeType=TimerBoundaryEvent)",
            "a BPMN attribute is read by no parameter of its entry (deadline,"
                + " attribute=timerEventDefinition/unit, flowNodeType=TimerBoundaryEvent)",
            "a BPMN element is read by no parameter of its entry (deadline,"
                + " child=timerEventDefinition/timeCycle, flowNodeType=TimerBoundaryEvent)"), parsed.problems());
    }

    @Test
    void leavesTheBpmnTheGraphIsBuiltFromAlone() throws Exception
    {
        final NodeBuilder root = withTimerBoundaryEvent();
        VocabularyFixture.entry(root, "MessageStartEvent", "bpmn:startEvent", "wf:StartEvent", 10)
            .setProperty("xmlChildElement", "bpmn:messageEventDefinition");

        final Parsed parsed = parsed(root, "    <bpmn:startEvent id=\"start\" name=\"Start\">\n"
            + "      <bpmn:documentation>Begins it</bpmn:documentation>\n"
            + "      <bpmn:outgoing>flow</bpmn:outgoing>\n"
            + "      <bpmn:messageEventDefinition id=\"def\" messageRef=\"go\"/>\n"
            + "    </bpmn:startEvent>\n"
            + "    <bpmn:userTask id=\"" + TASK + "\" default=\"flow\"><bpmn:incoming>flow</bpmn:incoming>"
            + "</bpmn:userTask>\n"
            + "    <bpmn:sequenceFlow id=\"flow\" name=\"Go\" sourceRef=\"start\" targetRef=\"" + TASK
            + "\"/>\n");
        final NodeState after = parsed.version();

        assertTrue(after.getChildNode("start").getChildNode("flow").exists());
        assertEquals(List.of(), parsed.problems());
    }

    @Test
    void appliesASequenceFlowsEntryButRefusesItsConditionExpression() throws Exception
    {
        final NodeBuilder root = withTimerBoundaryEvent();
        root.child(WORKFLOW_TYPES).child(SEQUENCE_FLOW).setProperty("jcrProperties", "{\"weight\": 2}");

        final Parsed parsed = parsed(root, "    <bpmn:userTask id=\"" + TASK + "\"/>\n"
            + "    <bpmn:userTask id=\"next\"/>\n"
            + "    <bpmn:sequenceFlow id=\"flow\" sourceRef=\"" + TASK + "\" targetRef=\"next\">\n"
            + "      <bpmn:conditionExpression>approved</bpmn:conditionExpression>\n"
            + "    </bpmn:sequenceFlow>\n");
        final NodeState after = parsed.version();

        final NodeState flow = after.getChildNode(TASK).getChildNode("flow");
        assertEquals("wf:SequenceFlow", flow.getName(PRIMARY_TYPE));
        assertFalse(flow.hasProperty(CONDITION_EXPRESSION));
        assertEquals(2L, flow.getLong("weight"));
        assertEquals("next", flow.getString("targetRef"));
        assertFalse(flow.hasProperty(FLOW_NODE_TYPE));
        assertEquals(List.of(
            "sequence flow guard is a conditionExpression, not a cond:condition (flow,"
                + " conditionExpression=approved)"), parsed.problems());
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

        final Parsed parsed = parsed(root, boundaryEvent("",
            "<bpmn:timeDuration>P5D</bpmn:timeDuration>").replace("      <bpmn:timerEventDefinition>",
                "      <bpmn:extensionElements><sv:node xmlns:sv=\"http://www.jcp.org/jcr/sv/1.0\""
                + " sv:name=\"cond:condition\"><sv:property sv:name=\"jcr:primaryType\" sv:type=\"Name\">"
                + "<sv:value>cond:SingleCondition</sv:value></sv:property></sv:node></bpmn:extensionElements>\n"
                + "      <bpmn:timerEventDefinition>"));
        final NodeState after = parsed.version();

        final NodeState condition = after.getChildNode(TASK).getChildNode(DEADLINE).getChildNode("cond:condition");
        assertEquals("cond:SingleCondition", condition.getName(PRIMARY_TYPE));
        assertEquals(List.of(), parsed.problems());
    }

    @Test
    void readsABooleanTheWayXmlSchemaWritesOne() throws Exception
    {
        final NodeBuilder root = withGenericServiceTask();
        VocabularyFixture.attributeParameter(root.child(WORKFLOW_TYPES).child(GENERIC_SERVICE_TASK), URGENT,
            Parameter.BOOLEAN_DATA_TYPE, 1);

        final Parsed parsed = parsed(root, booleanTask("upper", "TRUE") + booleanTask("one", "1")
            + booleanTask("lower", "false") + booleanTask("zero", "0"));

        assertTrue(parsed.version().getChildNode("upper").getBoolean(URGENT));
        assertTrue(parsed.version().getChildNode("one").getBoolean(URGENT));
        assertEquals(Type.BOOLEAN, parsed.version().getChildNode("lower").getProperty(URGENT).getType());
        assertFalse(parsed.version().getChildNode("lower").getBoolean(URGENT));
        assertFalse(parsed.version().getChildNode("zero").getBoolean(URGENT));
        assertEquals(List.of(), parsed.problems());
    }

    @Test
    void dropsAndReportsABooleanItCannotRead() throws Exception
    {
        // Nothing is written, so the node type's own default stands
        final NodeBuilder root = withGenericServiceTask();
        VocabularyFixture.attributeParameter(root.child(WORKFLOW_TYPES).child(GENERIC_SERVICE_TASK), URGENT,
            Parameter.BOOLEAN_DATA_TYPE, 1);

        final Parsed parsed = parsed(root, booleanTask(TASK, "yes"));

        assertFalse(parsed.version().getChildNode(TASK).hasProperty(URGENT));
        assertEquals(List.of("a parameter has a value that is not a boolean (task1, parameter=urgent, value=yes)"),
            parsed.problems());
    }

    @Test
    void passesOverTheArtifactsThatDocumentADiagram() throws Exception
    {
        final NodeBuilder root = withGenericServiceTask();

        final Parsed parsed = parsed(root, SERVICE_TASK_OPEN + TASK + "\"/>\n"
            + "    <bpmn:textAnnotation id=\"note\"><bpmn:text>Why</bpmn:text></bpmn:textAnnotation>\n"
            + "    <bpmn:association id=\"link\" sourceRef=\"" + TASK + "\" targetRef=\"note\"/>\n"
            + "    <bpmn:group id=\"group\" categoryValueRef=\"category\"/>\n"
            + "    <bpmn:dataObject id=\"data\"/>\n"
            + "    <bpmn:dataObjectReference id=\"dataReference\" dataObjectRef=\"data\"/>\n"
            + "    <bpmn:dataStoreReference id=\"store\"/>\n"
            + "    <bpmn:laneSet id=\"lanes\"><bpmn:lane id=\"lane\"/></bpmn:laneSet>\n");

        assertEquals(List.of(TASK),
            StreamSupport.stream(parsed.version().getChildNodeNames().spliterator(), false).toList());
        assertEquals(List.of(), parsed.problems());
    }

    @Test
    void recordsOnlyTheVocabularysDefectsInTheErrorLog() throws Exception
    {
        // The author learns of a mistake in the diagram from the stored line; only an administrator can fix the
        // vocabulary
        final NodeBuilder root = withSetState();

        try (RecordedProblems problems = new RecordedProblems()) {
            final Parsed parsed = parsed(root, serviceTask("<iap:setState/>")
                + "    <bpmn:scriptTask id=\"script1\"/>\n");

            assertEquals(3, parsed.problems().size(), () -> String.valueOf(parsed.problems()));
            assertEquals(List.of("no FlowNodeType configured for a BPMN element"), problems.names());
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
        bpmnParameter(timer, TIME_DURATION, Parameter.BPMN_ELEMENT_LOCATION, "bpmn:timerEventDefinition");
        bpmnParameter(timer, CANCEL_ACTIVITY, Parameter.BPMN_ATTRIBUTE_LOCATION, null)
            .setProperty("dataType", Parameter.BOOLEAN_DATA_TYPE);
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

    @Test
    void readsDeclarationsInTheOrderTheRepositoryKeepsForThem()
    {
        // A node state lists its children in storage order, which an orderable node's own order does not follow
        final NodeBuilder root = VocabularyFixture.repository();
        final NodeBuilder entry = VocabularyFixture.handler(root, "Ordered", "ordered");
        for (final String name : List.of("first", "second", "third")) {
            VocabularyFixture.parameter(entry, name, TEXT, 0, 1);
        }
        final NodeBuilder offering = entry.getChildNode("first");
        for (final String value : List.of("x", "y", "z")) {
            offering.child(value).setProperty(PRIMARY_TYPE, "wf:ParameterOption", Type.NAME)
                .setProperty("value", value);
        }
        // Recognized by its resource type alone, which the catalogue ignores as well
        offering.child("w").setProperty("sling:resourceType", Parameter.OPTION_RESOURCE_TYPE).setProperty("value", "w");
        // Each leaves one child unnamed, which follows the ones named, and names one that is gone
        entry.setProperty(CHILD_ORDER, List.of("third", "gone", "second"), Type.NAMES);
        offering.setProperty(CHILD_ORDER, List.of("z", "y"), Type.NAMES);

        final List<ParameterInfo> declared = FlowNodeTypeInfo.from("Ordered",
            entry.getNodeState(), NodeTypeUtils.registry(root.getNodeState())).parameters().declared();

        assertEquals(List.of("third", "second", "first"),
            declared.stream().map(ParameterInfo::name).toList());
        assertEquals(List.of("z", "y", "x"), List.copyOf(((ParameterInfo.ValueParameter) declared.get(2)).options()));
    }

    /** A generic service task giving the {@code urgent} parameter a value. */
    private static String booleanTask(final String id, final String value)
    {
        return SERVICE_TASK_OPEN + id + "\" iap:urgent=\"" + value + "\"/>\n";
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
        VocabularyFixture.parameter(type, TEMPLATE, Parameter.REFERENCE_DATA_TYPE, 1, 1)
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

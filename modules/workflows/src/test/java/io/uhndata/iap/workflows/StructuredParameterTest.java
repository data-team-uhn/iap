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
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.junit.jupiter.api.Test;

import io.uhndata.iap.workflows.ParseFixture.Parsed;
import io.uhndata.iap.workflows.models.Parameter;

import static io.uhndata.iap.workflows.ParseFixture.parsed;
import static io.uhndata.iap.workflows.ParseFixture.serviceTask;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for how {@link ParameterTranslation} reads a {@code structure} parameter into a subtree of the flow
 * node, driven through {@link WorkflowDefinitionUtils#parse}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class StructuredParameterTest
{
    private static final String PRIMARY_TYPE = "jcr:primaryType";

    private static final String TEXT = Parameter.TEXT_DATA_TYPE;

    private static final String NT_UNSTRUCTURED = "nt:unstructured";

    private static final String NT_BASE = "nt:base";

    private static final String CHILD_ORDER = ":childOrder";

    private static final String JCR_NODE_TYPE = "jcrNodeType";

    private static final String PARAMETERS_TYPE = "wf:Parameters";

    private static final String MANDATORY_PROPERTIES = "rep:mandatoryProperties";

    private static final String MANDATORY_CHILD_NODES = "rep:mandatoryChildNodes";

    private static final String CHILD_NODE_DEFINITIONS = "rep:namedChildNodeDefinitions";

    private static final String TASK = ParseFixture.TASK;

    private static final String UPDATE_CONTENT = "updateContent";

    private static final String FIELDS = "fields";

    private static final String NAME = "name";

    private static final String LABEL = "label";

    private static final String HELP = "help";

    private static final String REFERENCE_ROOT = "referenceRoot";

    private static final String CHOICES = "choices";

    private static final String VALUE = Parameter.OPTION_VALUE_PROPERTY;

    private static final String APPLIES_WHEN = "appliesWhen";

    private static final String PROPERTY = "property";

    private static final String VALUES = "values";

    private static final String CONFIGURE = "configure";

    private static final String TARGET = "target";

    private static final String PATH = "path";

    private static final String TAGS = "tags";

    private static final String DEPTH = "depth";

    private static final String WORKFLOW = "workflow";

    private static final String NOTE = "note";

    private static final String SINGLE = "single";

    private static final String KEPT = "Kept";

    private static final String ADVICE = ". Please contact an administrator";

    /** Two field declarations, the first with keyed choices and a condition holding a list. */
    private static final String UPDATE_CONTENT_FIELDS = "<iap:updateContent>"
        + "<iap:fields name=\"workflow\" label=\"Workflow\" referenceType=\"wf/WorkflowVersion\""
        + " referenceRoot=\"/Workflows\">"
        + "<iap:choices value=\"a\" label=\"A\"/><iap:choices value=\"b\"/>"
        + "<iap:appliesWhen property=\"dataType\"><iap:values>long</iap:values>"
        + "<iap:values>text</iap:values></iap:appliesWhen>"
        + "</iap:fields>"
        + "<iap:fields name=\"status\" label=\"Status\"/>"
        + "</iap:updateContent>";

    @Test
    void storesARepeatedStructureAsAContainerOfRecordsInDiagramOrder() throws Exception
    {
        final Parsed parsed = parsed(withUpdateContent(), serviceTask(UPDATE_CONTENT_FIELDS));

        final NodeState fields = parsed.version().getChildNode(TASK).getChildNode(FIELDS);
        assertEquals(NT_UNSTRUCTURED, fields.getName(PRIMARY_TYPE));
        assertEquals(List.of(WORKFLOW, "status"), names(fields.getProperty(CHILD_ORDER).getValue(Type.NAMES)));
        assertEquals(List.of(), parsed.problems());
    }

    @Test
    void storesEachRecordAsTheStructuresNodeTypeHoldingItsFields() throws Exception
    {
        final NodeState workflow = updateContentFields().getChildNode(WORKFLOW);

        assertEquals(NT_UNSTRUCTURED, workflow.getName(PRIMARY_TYPE));
        assertEquals("Workflow", workflow.getString(LABEL));
        assertEquals("wf/WorkflowVersion", workflow.getString(Parameter.REFERENCE_TYPE_PROPERTY));
        assertEquals("/Workflows", workflow.getString(REFERENCE_ROOT));
    }

    @Test
    void namesARecordAfterItsKeyRatherThanStoringIt() throws Exception
    {
        final NodeState fields = updateContentFields();

        assertTrue(fields.getChildNode(WORKFLOW).exists());
        assertFalse(fields.getChildNode(WORKFLOW).hasProperty(NAME));
    }

    @Test
    void storesARepeatedStructureNestedInARecord() throws Exception
    {
        final NodeState choices = updateContentFields().getChildNode(WORKFLOW).getChildNode(CHOICES);

        assertEquals(List.of("a", "b"), names(choices.getProperty(CHILD_ORDER).getValue(Type.NAMES)));
        assertEquals("A", choices.getChildNode("a").getString(LABEL));
        assertFalse(choices.getChildNode("a").hasProperty(VALUE));
        assertTrue(choices.getChildNode("b").exists());
    }

    @Test
    void storesASingleStructureNestedInARecord() throws Exception
    {
        final NodeState appliesWhen = updateContentFields().getChildNode(WORKFLOW).getChildNode(APPLIES_WHEN);

        assertEquals(Parameter.DATA_TYPE_PROPERTY, appliesWhen.getString(PROPERTY));
        assertEquals(List.of("long", "text"), names(appliesWhen.getProperty(VALUES).getValue(Type.STRINGS)));
    }

    @Test
    void storesNoContainerForAListTheElementLeavesOut() throws Exception
    {
        final NodeState status = updateContentFields().getChildNode("status");

        assertEquals("Status", status.getString(LABEL));
        assertFalse(status.hasChildNode(CHOICES));
    }

    @Test
    void storesASingleStructureAsAChildNodeOfItsDeclaredType() throws Exception
    {
        final Parsed parsed = parsed(withConfigure(), serviceTask(
            "<iap:configure><iap:target path=\" /a \" depth=\"3\"><iap:tags>x</iap:tags><iap:tags>y</iap:tags>"
            + "</iap:target></iap:configure>"));

        final NodeState target = parsed.version().getChildNode(TASK).getChildNode(TARGET);
        assertEquals("sling:Folder", target.getName(PRIMARY_TYPE));
        assertEquals("/a", target.getString(PATH));
        assertEquals(3L, target.getProperty(DEPTH).getValue(Type.LONG));
        assertEquals(List.of("x", "y"), names(target.getProperty(TAGS).getValue(Type.STRINGS)));
        assertFalse(target.hasProperty(CHILD_ORDER));
        assertEquals(List.of(), parsed.problems());
    }

    @Test
    void readsTheFirstOfMoreStructuresThanItAcceptsAndSaysSo() throws Exception
    {
        final Parsed parsed = parsed(withConfigure(), serviceTask(
            "<iap:configure><iap:target path=\"/a\"/><iap:target path=\"/b\"/></iap:configure>"));

        assertEquals("/a", parsed.version().getChildNode(TASK).getChildNode(TARGET).getString(PATH));
        assertEquals(List.of("a parameter has more values than it accepts (task1, parameter=target)"),
            parsed.problems());
    }

    @Test
    void reportsMarkupInsideAStructureThatNoFieldReads() throws Exception
    {
        final Parsed parsed = parsed(withConfigure(), serviceTask(
            "<iap:configure><iap:target path=\"/a\" colour=\"red\"><iap:extra/></iap:target></iap:configure>"));

        assertEquals(List.of(
            "an IAP extension attribute is read by no parameter of its entry (task1, attribute=target/colour,"
                + " flowNodeType=Configure)",
            "an IAP extension child is read by no parameter of its entry (task1, child=target/extra,"
                + " flowNodeType=Configure)"), parsed.problems());
    }

    @Test
    void reportsAMissingFieldByItsPathFromTheFlowNode() throws Exception
    {
        final Parsed parsed = parsed(withConfigure(), serviceTask("<iap:configure><iap:target/></iap:configure>"));

        assertEquals(List.of("a required parameter has no value (task1, parameter=target/path)"), parsed.problems());
    }

    @Test
    void dropsAFieldValueItsTypeCannotReadAndSaysSo() throws Exception
    {
        final Parsed parsed = parsed(withConfigure(), serviceTask(
            "<iap:configure><iap:target path=\"/a\" depth=\"deep\"/></iap:configure>"));

        assertFalse(parsed.version().getChildNode(TASK).getChildNode(TARGET).hasProperty(DEPTH));
        assertEquals(List.of(
            "a parameter has a value that is not a whole number (task1, parameter=target/depth, value=deep)"),
            parsed.problems());
    }

    @Test
    void namesARecordAfterItsKeyEscapedWhereANodeNameCannotHoldIt() throws Exception
    {
        final Parsed parsed = parsed(withUpdateContent(), serviceTask(
            "<iap:updateContent>"
            + "<iap:fields name=\"jcr:title\"><iap:choices value=\"sch:Section\"/></iap:fields>"
            + "<iap:fields name=\"a/b\"/>"
            + "<iap:fields name=\"..\"/>"
            + "</iap:updateContent>"));

        final NodeState fields = parsed.version().getChildNode(TASK).getChildNode(FIELDS);
        assertEquals(List.of("jcr%3Atitle", "a%2Fb", "%2E%2E"),
            names(fields.getProperty(CHILD_ORDER).getValue(Type.NAMES)));
        assertEquals(List.of("sch%3ASection"), names(fields.getChildNode("jcr%3Atitle").getChildNode(CHOICES)
            .getProperty(CHILD_ORDER).getValue(Type.NAMES)));
        assertEquals(List.of(), parsed.problems());
    }

    @Test
    void refusesAFieldTheRecordsNodeTypeCannotHold() throws Exception
    {
        final Parsed parsed = parsed(withDescribeRecords(), describedAge());

        assertFalse(parsed.version().getChildNode(TASK).getChildNode(FIELDS).getChildNode("age").hasProperty(HELP));
        assertEquals(List.of("a parameter is not allowed by the node type it is stored in (task1,"
            + " parameter=fields/age/help)" + ADVICE), parsed.problems());
    }

    @Test
    void storesARecordOfAStrictNodeTypeWithTheFieldsItCanHold() throws Exception
    {
        // A record whose type holds a label and nothing else, which is enough for the key, since that is its name
        final NodeState age = parsed(withDescribeRecords(), describedAge()).version().getChildNode(TASK)
            .getChildNode(FIELDS).getChildNode("age");

        assertEquals("x:Record", age.getName(PRIMARY_TYPE));
        assertFalse(age.hasProperty(NAME));
        assertEquals("Age", age.getString(LABEL));
    }

    @Test
    void refusesAStructureOfAnUnregisteredNodeTypeRatherThanFailingTheSave() throws Exception
    {
        final NodeBuilder root = VocabularyFixture.repository();
        final NodeBuilder type = VocabularyFixture.handler(root, "Misspelt", "misspelt");
        VocabularyFixture.parameter(fields(structure(type, SINGLE, 1).setProperty(JCR_NODE_TYPE, "x:Missing")),
            PATH, TEXT, 0, 1);
        VocabularyFixture.parameter(fields(structure(type, "repeated", 0)
            .setProperty(Parameter.KEY_PARAMETER_PROPERTY, NAME).setProperty(JCR_NODE_TYPE, "x:Missing")),
            NAME, TEXT, 1, 1);
        VocabularyFixture.parameter(type, LABEL, TEXT, 0, 1);

        final Parsed parsed = parsed(root, serviceTask("<iap:misspelt label=\"Kept\"><iap:single path=\"/a\"/>"
            + "<iap:repeated name=\"a\"/></iap:misspelt>"));

        final NodeState task = parsed.version().getChildNode(TASK);
        assertEquals(List.of(
            "a structured parameter has a node type that is not registered (task1, parameter=single)" + ADVICE,
            "a structured parameter has a node type that is not registered (task1, parameter=repeated)" + ADVICE),
            parsed.problems());
        assertFalse(task.hasChildNode(SINGLE) || task.hasChildNode("repeated"));
        assertEquals(KEPT, task.getString(LABEL));
    }

    @Test
    void refusesAStructureOfAnAbstractOrMixinNodeType() throws Exception
    {
        final Parsed parsed = parsed(withStrictRecords(), strictTask());

        assertEquals(Set.of(
            "a structured parameter has an abstract or mixin node type (task1, parameter=abstract)" + ADVICE,
            "a structured parameter has an abstract or mixin node type (task1, parameter=mixin)" + ADVICE),
            linesStartingWith(parsed, "a structured parameter has an abstract or mixin node type"));
        assertFalse(parsed.version().getChildNode(TASK).hasChildNode("abstract"));
    }

    @Test
    void refusesAStructureWhoseNodeTypeHasMandatoryItemsItDoesNotAutocreate() throws Exception
    {
        final Parsed parsed = parsed(withStrictRecords(), strictTask());

        assertEquals(Set.of(
            "a structured parameter has a node type with mandatory items (task1, parameter=file)" + ADVICE,
            "a structured parameter has a node type with mandatory items (task1, parameter=part)" + ADVICE,
            "a structured parameter has a node type with mandatory items (task1, parameter=single)" + ADVICE),
            linesStartingWith(parsed, "a structured parameter has a node type with mandatory items"));
        assertEquals(KEPT, parsed.version().getChildNode(TASK).getString(LABEL));
    }

    @Test
    void writesAStructureWhoseOnlyMandatoryItemIsItsPrimaryType() throws Exception
    {
        final Parsed parsed = parsed(withStrictRecords(), strictTask());

        final NodeState task = parsed.version().getChildNode(TASK);
        assertEquals(List.of("plain"), names(task.getChildNodeNames()));
        assertEquals("x:Plain", task.getChildNode("plain").getChildNode("a").getName(PRIMARY_TYPE));
    }

    @Test
    void writesAStructureWhoseMandatoryItemsItsTypeAutocreates() throws Exception
    {
        final NodeBuilder root = VocabularyFixture.repository();
        final NodeBuilder part = NodeTypesFixture.acceptingAnyProperty(NodeTypesFixture.type(root, "x:Part", NT_BASE)
            .setProperty(MANDATORY_PROPERTIES, List.of(PRIMARY_TYPE, "sling:resourceType"), Type.NAMES)
            .setProperty(MANDATORY_CHILD_NODES, List.of(NOTE), Type.NAMES));
        NodeTypesFixture.autoCreated(part, "sling:resourceType", "x/Part");
        part.child(CHILD_NODE_DEFINITIONS).child(NOTE).child(NT_UNSTRUCTURED)
            .setProperty("jcr:autoCreated", true).setProperty("jcr:defaultPrimaryType", NT_UNSTRUCTURED, Type.NAME);
        VocabularyFixture.parameter(fields(structure(VocabularyFixture.handler(root, "Strict", "strict"), SINGLE, 1)
            .setProperty(JCR_NODE_TYPE, "x:Part")), PATH, TEXT, 0, 1);

        final Parsed parsed = parsed(root, serviceTask("<iap:strict><iap:single path=\"/a\"/></iap:strict>"));

        final NodeState single = parsed.version().getChildNode(TASK).getChildNode(SINGLE);
        assertEquals("x/Part", single.getString("sling:resourceType"));
        assertTrue(single.hasChildNode(NOTE));
        assertEquals(List.of(), parsed.problems());
    }

    @Test
    void storesAStructureWhoseNodeTypeExtendsTheOneItsHolderDeclares() throws Exception
    {
        final Parsed parsed = parsed(withHolders(), heldTask());

        assertEquals("/a", parsed.version().getChildNode(TASK).getChildNode("holders").getChildNode("h")
            .getChildNode(TARGET).getString(PATH));
    }

    @Test
    void refusesAStructureItsHolderDeclaresAsAnotherNodeType() throws Exception
    {
        final Parsed parsed = parsed(withHolders(), heldTask());

        assertFalse(parsed.version().getChildNode(TASK).getChildNode("holders").getChildNode("h").hasChildNode(NOTE));
        assertEquals("a parameter disagrees with the node type it is stored in (task1, parameter=holders/h/note)"
            + ADVICE, parsed.problems().get(0));
    }

    @Test
    void refusesAStructureItsHolderHasNoDefinitionFor() throws Exception
    {
        final Parsed parsed = parsed(withHolders(), heldTask());

        assertFalse(parsed.version().getChildNode(TASK).getChildNode("holders").getChildNode("h")
            .hasChildNode("extras"));
        assertEquals("a parameter is not allowed by the node type it is stored in (task1,"
            + " parameter=holders/h/extras)" + ADVICE, parsed.problems().get(1));
    }

    @Test
    void reportsAnElementWithoutAKey() throws Exception
    {
        final Parsed parsed = parsed(withUpdateContent(), serviceTask(
            "<iap:updateContent><iap:fields label=\"Nameless\"/><iap:fields name=\"kept\"/></iap:updateContent>"));

        assertEquals(List.of("kept"), names(parsed.version().getChildNode(TASK).getChildNode(FIELDS)
            .getProperty(CHILD_ORDER).getValue(Type.NAMES)));
        assertEquals(List.of("an element of a structured parameter has no key (task1, parameter=fields)"),
            parsed.problems());
    }

    @Test
    void keepsTheFirstOfTwoElementsWithTheSameKeyAndSaysSo() throws Exception
    {
        final Parsed parsed = parsed(withUpdateContent(), serviceTask(
            "<iap:updateContent><iap:fields name=\"kept\" label=\"First\"/>"
            + "<iap:fields name=\" kept \" label=\"Second\"/></iap:updateContent>"));

        final NodeState fields = parsed.version().getChildNode(TASK).getChildNode(FIELDS);
        assertEquals(List.of("kept"), names(fields.getProperty(CHILD_ORDER).getValue(Type.NAMES)));
        assertEquals("First", fields.getChildNode("kept").getString(LABEL));
        assertEquals(List.of(
            "an element of a structured parameter has the same key as another (task1, parameter=fields, key=kept)"),
            parsed.problems());
    }

    @Test
    void refusesARepeatedStructureWithoutASingleValuedKeyField() throws Exception
    {
        // No key named, a key naming no field, a list as the key, and a structure as the key
        final NodeBuilder root = VocabularyFixture.repository();
        final NodeBuilder type = VocabularyFixture.handler(root, "Keyless", "keyless");
        structure(type, "none", 0);
        VocabularyFixture.parameter(fields(structure(type, "missing", 0)
            .setProperty(Parameter.KEY_PARAMETER_PROPERTY, "nothing")), NAME, TEXT, 0, 1);
        VocabularyFixture.parameter(fields(structure(type, "listed", 0)
            .setProperty(Parameter.KEY_PARAMETER_PROPERTY, NAME)), NAME, TEXT, 0, 0);
        structure(fields(structure(type, "nested", 0).setProperty(Parameter.KEY_PARAMETER_PROPERTY, NAME)), NAME, 1);

        final Parsed parsed = parsed(root, serviceTask("<iap:keyless><iap:none/><iap:missing name=\"a\"/>"
            + "<iap:listed><iap:name>a</iap:name></iap:listed><iap:nested><iap:name/></iap:nested>"
            + "</iap:keyless>"));

        assertEquals(Stream.of("none", "missing", "listed", "nested")
            .map(name -> "a repeated structured parameter declares no single-valued key (task1, parameter=" + name
                + ")" + ADVICE)
            .collect(Collectors.toSet()), Set.copyOf(parsed.problems()));
        assertEquals(List.of(), names(parsed.version().getChildNode(TASK).getChildNodeNames()));
    }

    @Test
    void refusesAStructureOutsideAnExtensionElement() throws Exception
    {
        final NodeBuilder root = VocabularyFixture.repository();
        structure(VocabularyFixture.handler(root, "Inline", "inline"), "elsewhere", 1)
            .setProperty(Parameter.XML_LOCATION_PROPERTY, Parameter.ATTRIBUTE_LOCATION);
        structure(VocabularyFixture.entry(root, "Bare", "bpmn:scriptTask", VocabularyFixture.ACTIVITY, 10), "loose",
            0);

        final Parsed parsed = parsed(root, serviceTask("<iap:inline/>") + "    <bpmn:scriptTask id=\"script1\"/>\n");

        assertEquals(List.of(
            "a structured parameter is not inside an extension element (task1, parameter=elsewhere)" + ADVICE,
            "a structured parameter is not inside an extension element (script1, parameter=loose)" + ADVICE),
            parsed.problems());
    }

    @Test
    void reportsAStructureWrittenAsAnAttribute() throws Exception
    {
        final Parsed parsed = parsed(withConfigure(), serviceTask("<iap:configure target=\"/a\"/>"));

        assertFalse(parsed.version().getChildNode(TASK).hasChildNode(TARGET));
        assertEquals(List.of("a structured parameter is written as an attribute instead (task1, parameter=target)",
            "a required parameter has no value (task1, parameter=target)"), parsed.problems());
    }

    @Test
    void acceptsAnOptionalStructureLeftOut() throws Exception
    {
        assertEquals(List.of(), parsed(withUpdateContent(), serviceTask("<iap:updateContent/>")).problems());
    }

    @Test
    void refusesAFieldDeclaredOutsideItsStructuresElement() throws Exception
    {
        final NodeBuilder root = withConfigure();
        fields(root.child(VocabularyFixture.WORKFLOW_TYPES).child("Configure").child(TARGET)).child("flag")
            .setProperty(PRIMARY_TYPE, Parameter.NODE_TYPE, Type.NAME)
            .setProperty(Parameter.DATA_TYPE_PROPERTY, TEXT)
            .setProperty(Parameter.XML_LOCATION_PROPERTY, Parameter.ATTRIBUTE_LOCATION);

        final Parsed parsed = parsed(root, serviceTask(
            "<iap:configure><iap:target path=\"/a\" flag=\"on\"/></iap:configure>"));

        assertEquals(List.of("a field of a structured parameter is not written inside it (task1,"
            + " parameter=target/flag)" + ADVICE), parsed.problems());
    }

    @Test
    void reportsAFieldDeclaredBesideItsStructuresFieldsNode() throws Exception
    {
        final Parsed parsed = parsed(withMisplacedFlag(), serviceTask(
            "<iap:configure><iap:target path=\"/a\"/></iap:configure>"));

        assertEquals(List.of("a vocabulary entry declares a field outside a structure (task1, flowNodeType=Configure,"
            + " parameter=target/flag)" + ADVICE), parsed.problems());
    }

    @Test
    void readsTheFieldsDeclaredProperlyBesideAMisplacedOne() throws Exception
    {
        final Parsed parsed = parsed(withMisplacedFlag(), serviceTask(
            "<iap:configure><iap:target path=\"/a\"/></iap:configure>"));

        assertEquals("/a", parsed.version().getChildNode(TASK).getChildNode(TARGET).getString(PATH));
    }

    @Test
    void namesAStrayDeclarationInsideANestedStructureByItsPath() throws Exception
    {
        final NodeBuilder root = withUpdateContent();
        final NodeBuilder fields = root.child(VocabularyFixture.WORKFLOW_TYPES).child("UpdateContent").child(FIELDS)
            .child(Parameter.FIELDS_CHILD);
        VocabularyFixture.parameter(fields.child(CHOICES), "hint", TEXT, 0, 1);
        fields.child(NOTE).setProperty(PRIMARY_TYPE, NT_UNSTRUCTURED, Type.NAME)
            .setProperty("sling:resourceType", Parameter.RESOURCE_TYPE);

        final Parsed parsed = parsed(root, serviceTask(
            "<iap:updateContent><iap:fields name=\"title\"/></iap:updateContent>"));

        assertEquals(List.of(
            "a vocabulary entry holds a parameter of the wrong node type (task1, flowNodeType=UpdateContent,"
                + " parameter=fields/parameters/note)" + ADVICE,
            "a vocabulary entry declares a field outside a structure (task1, flowNodeType=UpdateContent,"
                + " parameter=fields/parameters/choices/hint)" + ADVICE), parsed.problems());
    }

    @Test
    void readsFieldsNamedLikeTheSettingsOfAParameter() throws Exception
    {
        final NodeState stored = parsed(withDescribedFields(), describedFields()).version().getChildNode(TASK)
            .getChildNode(FIELDS);

        assertEquals("Age", stored.getChildNode("age").getString(LABEL));
        assertEquals("long", stored.getChildNode("age").getString(Parameter.DATA_TYPE_PROPERTY));
        assertEquals("Odd", stored.getChildNode("odd").getString(LABEL));
    }

    @Test
    void checksFieldsNamedLikeTheSettingsOfAParameterAsAnyOthers() throws Exception
    {
        final Parsed parsed = parsed(withDescribedFields(), describedFields());

        assertEquals(List.of(
            "a parameter has a value it does not offer (task1, parameter=fields/odd/dataType, value=structure)",
            "a required parameter has no value (task1, parameter=fields/bare/label)"), parsed.problems());
    }

    /** The records the field declarations of {@link #UPDATE_CONTENT_FIELDS} are stored as. */
    private NodeState updateContentFields() throws Exception
    {
        return parsed(withUpdateContent(), serviceTask(UPDATE_CONTENT_FIELDS)).version().getChildNode(TASK)
            .getChildNode(FIELDS);
    }

    /**
     * An entry shaped like the content-editing step's: a list of field declarations keyed by name, each with keyed
     * choices and an optional condition holding a list.
     */
    private NodeBuilder withUpdateContent()
    {
        final NodeBuilder root = VocabularyFixture.repository();
        final NodeBuilder type = VocabularyFixture.handler(root, "UpdateContent", UPDATE_CONTENT);
        final NodeBuilder fields = fields(keyed(structure(type, FIELDS, 0), NAME));
        VocabularyFixture.parameter(fields, NAME, TEXT, 1, 1);
        VocabularyFixture.parameter(fields, LABEL, TEXT, 0, 1);
        VocabularyFixture.parameter(fields, Parameter.REFERENCE_TYPE_PROPERTY, TEXT, 0, 1);
        VocabularyFixture.parameter(fields, REFERENCE_ROOT, TEXT, 0, 1);
        final NodeBuilder choices = fields(keyed(structure(fields, CHOICES, 0), VALUE));
        VocabularyFixture.parameter(choices, VALUE, TEXT, 1, 1);
        VocabularyFixture.parameter(choices, LABEL, TEXT, 0, 1);
        final NodeBuilder appliesWhen = fields(structure(fields, APPLIES_WHEN, 1));
        VocabularyFixture.parameter(appliesWhen, PROPERTY, TEXT, 1, 1);
        VocabularyFixture.parameter(appliesWhen, VALUES, TEXT, 1, 0);
        return root;
    }

    /** An empty vocabulary plus an entry taking one required target, stored as a folder. */
    private NodeBuilder withConfigure()
    {
        final NodeBuilder root = VocabularyFixture.repository();
        final NodeBuilder target = fields(structure(VocabularyFixture.handler(root, "Configure", CONFIGURE), TARGET, 1)
            .setProperty(Parameter.MIN_VALUES_PROPERTY, 1L).setProperty(JCR_NODE_TYPE, "sling:Folder"));
        VocabularyFixture.parameter(target, PATH, TEXT, 1, 1);
        VocabularyFixture.parameter(target, TAGS, TEXT, 0, 0);
        VocabularyFixture.parameter(target, DEPTH, Parameter.LONG_DATA_TYPE, 0, 1);
        return root;
    }

    /** {@link #withConfigure()} with a field declared beside the target's fields node rather than inside it. */
    private NodeBuilder withMisplacedFlag()
    {
        final NodeBuilder root = withConfigure();
        VocabularyFixture.parameter(root.child(VocabularyFixture.WORKFLOW_TYPES).child("Configure").child(TARGET),
            "flag", TEXT, 0, 1);
        return root;
    }

    /** Field declarations stored as records of a strict type, which holds a label and nothing else. */
    private NodeBuilder withDescribeRecords()
    {
        final NodeBuilder root = VocabularyFixture.repository();
        NodeTypesFixture.type(root, "x:Record", NT_BASE).child("rep:namedPropertyDefinitions").child(LABEL)
            .child("STRING");
        final NodeBuilder fields = fields(keyed(structure(VocabularyFixture.handler(root, "Describe", "describe"),
            FIELDS, 0), NAME).setProperty(JCR_NODE_TYPE, "x:Record"));
        VocabularyFixture.parameter(fields, NAME, TEXT, 1, 1);
        VocabularyFixture.parameter(fields, LABEL, TEXT, 0, 1);
        VocabularyFixture.parameter(fields, HELP, TEXT, 0, 1);
        return root;
    }

    private static String describedAge()
    {
        return serviceTask("<iap:describe><iap:fields name=\"age\" label=\"Age\" help=\"In years\"/></iap:describe>");
    }

    /** Field declarations whose fields are named like a parameter's settings, one of them offering options. */
    private NodeBuilder withDescribedFields()
    {
        final NodeBuilder root = VocabularyFixture.repository();
        final NodeBuilder fields = fields(keyed(structure(VocabularyFixture.handler(root, "Describe", "describe"),
            FIELDS, 0), NAME));
        VocabularyFixture.parameter(fields, NAME, TEXT, 1, 1);
        VocabularyFixture.parameter(fields, LABEL, TEXT, 1, 1);
        final NodeBuilder dataType = VocabularyFixture.parameter(fields, Parameter.DATA_TYPE_PROPERTY, TEXT, 0, 1);
        for (final String offered : List.of("long", "text")) {
            dataType.child(offered).setProperty(PRIMARY_TYPE, Parameter.OPTION_NODE_TYPE, Type.NAME)
                .setProperty(VALUE, offered);
        }
        return root;
    }

    private static String describedFields()
    {
        return serviceTask("<iap:describe>"
            + "<iap:fields name=\"age\" label=\"Age\" dataType=\"long\"/>"
            + "<iap:fields name=\"odd\" label=\"Odd\" dataType=\"structure\"/>"
            + "<iap:fields name=\"bare\"/>"
            + "</iap:describe>");
    }

    /**
     * Structures of node types no record can be written as, as Oak flattens each, then one of a type whose only
     * mandatory item is its primary type, then a plain parameter.
     */
    private NodeBuilder withStrictRecords()
    {
        final NodeBuilder root = VocabularyFixture.repository();
        NodeTypesFixture.type(root, "x:Abstract", NT_BASE).setProperty("jcr:isAbstract", true);
        NodeTypesFixture.type(root, "x:Mixin").setProperty("jcr:isMixin", true);
        NodeTypesFixture.type(root, "x:File", NT_BASE)
            .setProperty(MANDATORY_CHILD_NODES, List.of("jcr:content"), Type.NAMES);
        NodeTypesFixture.type(root, "x:Part", NT_BASE)
            .setProperty(MANDATORY_PROPERTIES, List.of(PRIMARY_TYPE, "sling:resourceType"), Type.NAMES);
        NodeTypesFixture.acceptingAnyProperty(NodeTypesFixture.type(root, "x:Plain", NT_BASE)
            .setProperty(MANDATORY_PROPERTIES, List.of(PRIMARY_TYPE), Type.NAMES));
        final NodeBuilder type = VocabularyFixture.handler(root, "Strict", "strict");
        for (final String name : List.of("abstract", "mixin", "file", "part", "plain")) {
            final String nodeType = "x:" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            VocabularyFixture.parameter(fields(keyed(structure(type, name, 0), NAME)
                .setProperty(JCR_NODE_TYPE, nodeType)), NAME, TEXT, 1, 1);
        }
        VocabularyFixture.parameter(fields(structure(type, SINGLE, 1).setProperty(JCR_NODE_TYPE, "x:Part")),
            PATH, TEXT, 0, 1);
        VocabularyFixture.parameter(type, LABEL, TEXT, 0, 1);
        return root;
    }

    private static String strictTask()
    {
        return serviceTask("<iap:strict label=\"Kept\"><iap:abstract name=\"a\"/>"
            + "<iap:mixin name=\"a\"/><iap:file name=\"a\"/><iap:part name=\"a\"/><iap:plain name=\"a\"/>"
            + "<iap:single path=\"/a\"/></iap:strict>");
    }

    /** Records of a strict holder type, which takes a folder as its target and an x:Note as its note. */
    private NodeBuilder withHolders()
    {
        final NodeBuilder root = VocabularyFixture.repository();
        final NodeBuilder holder = NodeTypesFixture.acceptingAnyProperty(
            NodeTypesFixture.type(root, "x:Holder", NT_BASE));
        holder.child(CHILD_NODE_DEFINITIONS).child(TARGET).child("nt:hierarchyNode");
        holder.child(CHILD_NODE_DEFINITIONS).child(NOTE).child("x:Note");
        final NodeBuilder holders = fields(keyed(structure(VocabularyFixture.handler(root, "Hold", "hold"), "holders",
            0), NAME).setProperty(JCR_NODE_TYPE, "x:Holder"));
        VocabularyFixture.parameter(holders, NAME, TEXT, 1, 1);
        VocabularyFixture.parameter(fields(structure(holders, TARGET, 1).setProperty(JCR_NODE_TYPE, "sling:Folder")),
            PATH, TEXT, 0, 1);
        VocabularyFixture.parameter(fields(structure(holders, NOTE, 1)), LABEL, TEXT, 0, 1);
        VocabularyFixture.parameter(fields(keyed(structure(holders, "extras", 0), NAME)), NAME, TEXT, 1, 1);
        return root;
    }

    private static String heldTask()
    {
        return serviceTask("<iap:hold><iap:holders name=\"h\">"
            + "<iap:target path=\"/a\"/><iap:note label=\"N\"/><iap:extras name=\"e\"/>"
            + "</iap:holders></iap:hold>");
    }

    private NodeBuilder structure(final NodeBuilder parent, final String name, final long maxValues)
    {
        return VocabularyFixture.parameter(parent, name, Parameter.STRUCTURE_DATA_TYPE, 0, maxValues);
    }

    private NodeBuilder keyed(final NodeBuilder structure, final String key)
    {
        return structure.setProperty(Parameter.KEY_PARAMETER_PROPERTY, key);
    }

    /** The node a structure's fields are declared under. */
    private NodeBuilder fields(final NodeBuilder structure)
    {
        return structure.child(Parameter.FIELDS_CHILD).setProperty(PRIMARY_TYPE, PARAMETERS_TYPE, Type.NAME);
    }

    /** The problems a parse reported under one phrase, in no particular order. */
    private static Set<String> linesStartingWith(final Parsed parsed, final String problem)
    {
        return parsed.problems().stream().filter(line -> line.startsWith(problem)).collect(Collectors.toSet());
    }

    private static List<String> names(final Iterable<String> values)
    {
        return StreamSupport.stream(values.spliterator(), false).toList();
    }
}

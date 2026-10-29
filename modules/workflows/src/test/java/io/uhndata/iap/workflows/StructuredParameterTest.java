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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.junit.jupiter.api.Test;

import io.uhndata.iap.workflows.models.Parameter;

import static io.uhndata.iap.workflows.ParseFixture.parse;
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

    private static final String TEXT = Parameter.TYPE_TEXT;

    private static final String NT_UNSTRUCTURED = "nt:unstructured";

    private static final String CHILD_ORDER = ":childOrder";

    private static final String WORKFLOW_TYPES = "WorkflowTypes";

    private static final String KEY_PARAMETER = "keyParameter";

    private static final String XML_LOCATION = "xmlLocation";

    private static final String PARAMETER_DETAIL = "parameter";

    private static final String KEY_DETAIL = "key";

    private static final String TASK = ParseFixture.TASK;

    private static final String UPDATE_CONTENT = "updateContent";

    private static final String FIELDS = "fields";

    private static final String NAME = "name";

    private static final String LABEL = "label";

    private static final String HELP = "help";

    private static final String REFERENCE_TYPE = "referenceType";

    private static final String REFERENCE_ROOT = "referenceRoot";

    private static final String CHOICES = "choices";

    private static final String VALUE = "value";

    private static final String DATA_TYPE = "dataType";

    private static final String APPLIES_WHEN = "appliesWhen";

    private static final String PROPERTY = "property";

    private static final String VALUES = "values";

    private static final String CONFIGURE = "configure";

    private static final String TARGET = "target";

    private static final String PATH = "path";

    private static final String TAGS = "tags";

    private static final String DEPTH = "depth";

    private static final String WORKFLOW = "workflow";

    private static final String JCR_NODE_TYPE = "jcrNodeType";

    private static final String MANDATORY_PROPERTIES = "rep:mandatoryProperties";

    private static final String MANDATORY_CHILD_NODES = "rep:mandatoryChildNodes";

    @Test
    void storesTheFieldDeclarationsOfAnUpdateContentStepAsTheSubtreeItsHandlerReads() throws Exception
    {
        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState task = parse(withUpdateContent(), serviceTask(
                "<iap:updateContent>"
                + "<iap:fields name=\"workflow\" label=\"Workflow\" referenceType=\"wf/WorkflowVersion\""
                + " referenceRoot=\"/Workflows\">"
                + "<iap:choices value=\"a\" label=\"A\"/><iap:choices value=\"b\"/>"
                + "<iap:appliesWhen property=\"dataType\"><iap:values>long</iap:values>"
                + "<iap:values>text</iap:values></iap:appliesWhen>"
                + "</iap:fields>"
                + "<iap:fields name=\"status\" label=\"Status\"/>"
                + "</iap:updateContent>")).getChildNode(TASK);

            assertEquals(List.of(), problems.messages());
            final NodeState fields = task.getChildNode(FIELDS);
            assertEquals(NT_UNSTRUCTURED, fields.getName(PRIMARY_TYPE));
            assertEquals(List.of(WORKFLOW, "status"), names(fields.getProperty(CHILD_ORDER).getValue(Type.NAMES)));
            final NodeState workflow = fields.getChildNode(WORKFLOW);
            assertEquals(NT_UNSTRUCTURED, workflow.getName(PRIMARY_TYPE));
            assertEquals("Workflow", workflow.getString(LABEL));
            assertEquals("wf/WorkflowVersion", workflow.getString(REFERENCE_TYPE));
            assertEquals("/Workflows", workflow.getString(REFERENCE_ROOT));
            assertFalse(workflow.hasProperty(NAME), "the key names the node instead");
            final NodeState choices = workflow.getChildNode(CHOICES);
            assertEquals(List.of("a", "b"), names(choices.getProperty(CHILD_ORDER).getValue(Type.NAMES)));
            assertEquals("A", choices.getChildNode("a").getString(LABEL));
            assertFalse(choices.getChildNode("a").hasProperty(VALUE));
            assertTrue(choices.getChildNode("b").exists());
            final NodeState appliesWhen = workflow.getChildNode(APPLIES_WHEN);
            assertEquals(DATA_TYPE, appliesWhen.getString(PROPERTY));
            assertEquals(List.of("long", "text"), names(appliesWhen.getProperty(VALUES).getValue(Type.STRINGS)));
            assertEquals("Status", fields.getChildNode("status").getString(LABEL));
            assertFalse(fields.getChildNode("status").hasChildNode(CHOICES), "an absent list stores no container");
        }
    }

    @Test
    void storesASingleStructureAsAChildNodeOfItsDeclaredType() throws Exception
    {
        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState target = parse(withConfigure(), serviceTask(
                "<iap:configure><iap:target path=\" /a \" depth=\"3\"><iap:tags>x</iap:tags><iap:tags>y</iap:tags>"
                + "</iap:target></iap:configure>")).getChildNode(TASK).getChildNode(TARGET);

            assertEquals(List.of(), problems.messages());
            assertEquals("sling:Folder", target.getName(PRIMARY_TYPE));
            assertEquals("/a", target.getString(PATH));
            assertEquals(3L, target.getProperty(DEPTH).getValue(Type.LONG));
            assertEquals(List.of("x", "y"), names(target.getProperty(TAGS).getValue(Type.STRINGS)));
            assertFalse(target.hasProperty(CHILD_ORDER));
        }
    }

    @Test
    void checksTheFieldsOfAStructureAsItChecksAnyParameter() throws Exception
    {
        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState target = parse(withConfigure(), serviceTask(
                "<iap:configure><iap:target depth=\"deep\"/><iap:target path=\"/b\"/></iap:configure>"))
                .getChildNode(TASK).getChildNode(TARGET);

            assertEquals(List.of(
                "a parameter has more values than it accepts",
                "a required parameter has no value",
                "a parameter has a value that is not a whole number"),
                problems.names());
            assertEquals(TARGET, problems.all().get(0).details().get(PARAMETER_DETAIL));
            assertEquals("target/path", problems.all().get(1).details().get(PARAMETER_DETAIL));
            assertEquals("target/depth", problems.all().get(2).details().get(PARAMETER_DETAIL));
            assertTrue(target.exists(), "the first element is read");
            assertFalse(target.hasProperty(PATH));
        }
    }

    @Test
    void namesARecordAfterItsKeyEscapedWhereANodeNameCannotHoldIt() throws Exception
    {
        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState fields = parse(withUpdateContent(), serviceTask(
                "<iap:updateContent>"
                + "<iap:fields name=\"jcr:title\"><iap:choices value=\"sch:Section\"/></iap:fields>"
                + "<iap:fields name=\"a/b\"/>"
                + "<iap:fields name=\"..\"/>"
                + "</iap:updateContent>")).getChildNode(TASK).getChildNode(FIELDS);

            assertEquals(List.of(), problems.messages());
            assertEquals(List.of("jcr%3Atitle", "a%2Fb", "%2E%2E"),
                names(fields.getProperty(CHILD_ORDER).getValue(Type.NAMES)));
            assertEquals(List.of("sch%3ASection"), names(fields.getChildNode("jcr%3Atitle").getChildNode(CHOICES)
                .getProperty(CHILD_ORDER).getValue(Type.NAMES)));
        }
    }

    @Test
    void storesARecordWhoseNodeTypeCannotHoldItsKeyButReportsAFieldItCannotHold() throws Exception
    {
        // Strict: a record may hold a label and nothing else, which is enough for the key, since that is its name
        final NodeBuilder root = VocabularyFixture.repository();
        NodeTypesFixture.type(root, "x:Record", "nt:base").child("rep:namedPropertyDefinitions").child(LABEL)
            .child("STRING");
        final NodeBuilder fields = fields(structure(VocabularyFixture.handler(root, "Describe", "describe"), FIELDS, 0)
            .setProperty(KEY_PARAMETER, NAME).setProperty(JCR_NODE_TYPE, "x:Record"));
        VocabularyFixture.parameter(fields, NAME, TEXT, 1, 1);
        VocabularyFixture.parameter(fields, LABEL, TEXT, 0, 1);
        VocabularyFixture.parameter(fields, HELP, TEXT, 0, 1);

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState age = parse(root, serviceTask("<iap:describe><iap:fields name=\"age\" label=\"Age\""
                + " help=\"In years\"/></iap:describe>")).getChildNode(TASK).getChildNode(FIELDS).getChildNode("age");

            assertEquals(List.of("a parameter is not allowed by the node type it is stored in"), problems.names());
            assertEquals("fields/age/help", problems.all().get(0).details().get(PARAMETER_DETAIL));
            assertEquals("x:Record", age.getName(PRIMARY_TYPE));
            assertFalse(age.hasProperty(NAME));
            assertFalse(age.hasProperty(HELP));
            assertEquals("Age", age.getString(LABEL));
        }
    }

    @Test
    void reportsAStructureOfAnUnregisteredNodeTypeRatherThanFailingTheSave() throws Exception
    {
        final NodeBuilder root = VocabularyFixture.repository();
        final NodeBuilder type = VocabularyFixture.handler(root, "Misspelt", "misspelt");
        VocabularyFixture.parameter(fields(structure(type, "single", 1).setProperty(JCR_NODE_TYPE, "x:Missing")),
            PATH, TEXT, 0, 1);
        VocabularyFixture.parameter(fields(structure(type, "repeated", 0).setProperty(KEY_PARAMETER, NAME)
            .setProperty(JCR_NODE_TYPE, "x:Missing")), NAME, TEXT, 1, 1);
        VocabularyFixture.parameter(type, LABEL, TEXT, 0, 1);

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState task = parse(root, serviceTask("<iap:misspelt label=\"Kept\"><iap:single path=\"/a\"/>"
                + "<iap:repeated name=\"a\"/></iap:misspelt>")).getChildNode(TASK);

            assertEquals(List.of("a structured parameter is stored as a node type that is not registered",
                "a structured parameter is stored as a node type that is not registered"), problems.names());
            assertFalse(task.hasChildNode("single") || task.hasChildNode("repeated"));
            assertEquals("Kept", task.getString(LABEL));
        }
    }

    @Test
    void reportsAStructureOfANodeTypeNoRecordCanBeWrittenAsRatherThanFailingTheSave() throws Exception
    {
        // Oak's own flattening of each: what TypeEditor would refuse a node builder's record for
        final NodeBuilder root = VocabularyFixture.repository();
        NodeTypesFixture.type(root, "x:Abstract", "nt:base").setProperty("jcr:isAbstract", true);
        NodeTypesFixture.type(root, "x:Mixin").setProperty("jcr:isMixin", true);
        NodeTypesFixture.type(root, "x:File", "nt:base")
            .setProperty(MANDATORY_CHILD_NODES, List.of("jcr:content"), Type.NAMES);
        NodeTypesFixture.type(root, "x:Part", "nt:base")
            .setProperty(MANDATORY_PROPERTIES, List.of(PRIMARY_TYPE, "sling:resourceType"), Type.NAMES);
        NodeTypesFixture.acceptingAnyProperty(NodeTypesFixture.type(root, "x:Plain", "nt:base")
            .setProperty(MANDATORY_PROPERTIES, List.of(PRIMARY_TYPE), Type.NAMES));
        final NodeBuilder type = VocabularyFixture.handler(root, "Strict", "strict");
        for (final String nodeType : List.of("x:Abstract", "x:Mixin", "x:File", "x:Part", "x:Plain")) {
            final String name = nodeType.substring(2).toLowerCase(Locale.ROOT);
            VocabularyFixture.parameter(fields(structure(type, name, 0).setProperty(KEY_PARAMETER, NAME)
                .setProperty(JCR_NODE_TYPE, nodeType)), NAME, TEXT, 1, 1);
        }
        VocabularyFixture.parameter(fields(structure(type, "single", 1).setProperty(JCR_NODE_TYPE, "x:Part")),
            PATH, TEXT, 0, 1);
        VocabularyFixture.parameter(type, LABEL, TEXT, 0, 1);

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState task = parse(root, serviceTask("<iap:strict label=\"Kept\"><iap:abstract name=\"a\"/>"
                + "<iap:mixin name=\"a\"/><iap:file name=\"a\"/><iap:part name=\"a\"/><iap:plain name=\"a\"/>"
                + "<iap:single path=\"/a\"/></iap:strict>")).getChildNode(TASK);

            final String uninstantiable = "a structured parameter is stored as an abstract or mixin node type";
            final String mandatory = "a structured parameter is stored as a node type with mandatory items";
            assertEquals(Map.of("abstract", uninstantiable, "mixin", uninstantiable, "file", mandatory,
                "part", mandatory, "single", mandatory), problems.all().stream().collect(Collectors.toMap(
                    problem -> problem.details().get(PARAMETER_DETAIL), RecordedProblems.Problem::problem)));
            assertEquals(List.of("plain"), names(task.getChildNodeNames()));
            assertEquals("x:Plain", task.getChildNode("plain").getChildNode("a").getName(PRIMARY_TYPE),
                "a primary type is the one mandatory property the parse always writes");
            assertEquals("Kept", task.getString(LABEL));
        }
    }

    @Test
    void writesAStructureWhoseMandatoryItemsItsTypeAutocreates() throws Exception
    {
        final NodeBuilder root = VocabularyFixture.repository();
        final NodeBuilder part = NodeTypesFixture.acceptingAnyProperty(NodeTypesFixture.type(root, "x:Part", "nt:base")
            .setProperty(MANDATORY_PROPERTIES, List.of(PRIMARY_TYPE, "sling:resourceType"), Type.NAMES)
            .setProperty(MANDATORY_CHILD_NODES, List.of("note"), Type.NAMES));
        part.child("rep:namedPropertyDefinitions").child("sling:resourceType").child("STRING")
            .setProperty("jcr:autoCreated", true).setProperty("jcr:defaultValues", List.of("x/Part"), Type.STRINGS);
        part.child("rep:namedChildNodeDefinitions").child("note").child("nt:unstructured")
            .setProperty("jcr:autoCreated", true).setProperty("jcr:defaultPrimaryType", "nt:unstructured", Type.NAME);
        final NodeBuilder type = VocabularyFixture.handler(root, "Strict", "strict");
        VocabularyFixture.parameter(fields(structure(type, "single", 1).setProperty(JCR_NODE_TYPE, "x:Part")),
            PATH, TEXT, 0, 1);

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState single = parse(root, serviceTask("<iap:strict><iap:single path=\"/a\"/></iap:strict>"))
                .getChildNode(TASK).getChildNode("single");

            assertEquals(List.of(), problems.names());
            assertEquals("x/Part", single.getString("sling:resourceType"));
            assertTrue(single.hasChildNode("note"));
        }
    }

    @Test
    void reportsAStructureTheNodeHoldingItDoesNotAdmitRatherThanFailingTheSave() throws Exception
    {
        // Strict: a holder takes a folder as its target and an x:Note as its note, and no other child
        final NodeBuilder root = VocabularyFixture.repository();
        final NodeBuilder holder = NodeTypesFixture.acceptingAnyProperty(
            NodeTypesFixture.type(root, "x:Holder", "nt:base"));
        holder.child("rep:namedChildNodeDefinitions").child(TARGET).child("nt:hierarchyNode");
        holder.child("rep:namedChildNodeDefinitions").child("note").child("x:Note");
        final NodeBuilder holders = fields(structure(VocabularyFixture.handler(root, "Hold", "hold"), "holders", 0)
            .setProperty(KEY_PARAMETER, NAME).setProperty(JCR_NODE_TYPE, "x:Holder"));
        VocabularyFixture.parameter(holders, NAME, TEXT, 1, 1);
        VocabularyFixture.parameter(fields(structure(holders, TARGET, 1).setProperty(JCR_NODE_TYPE, "sling:Folder")),
            PATH, TEXT, 0, 1);
        VocabularyFixture.parameter(fields(structure(holders, "note", 1)), LABEL, TEXT, 0, 1);
        VocabularyFixture.parameter(fields(structure(holders, "extras", 0).setProperty(KEY_PARAMETER, NAME)), NAME,
            TEXT, 1, 1);

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState held = parse(root, serviceTask("<iap:hold><iap:holders name=\"h\">"
                + "<iap:target path=\"/a\"/><iap:note label=\"N\"/><iap:extras name=\"e\"/>"
                + "</iap:holders></iap:hold>")).getChildNode(TASK).getChildNode("holders").getChildNode("h");

            assertEquals(List.of("a parameter is declared differently by the node type it is stored in",
                "a parameter is not allowed by the node type it is stored in"), problems.names());
            assertEquals("holders/h/note", problems.all().get(0).details().get(PARAMETER_DETAIL));
            assertEquals("holders/h/extras", problems.all().get(1).details().get(PARAMETER_DETAIL));
            assertEquals("/a", held.getChildNode(TARGET).getString(PATH), "a supertype satisfies a definition");
            assertFalse(held.hasChildNode("note") || held.hasChildNode("extras"));
        }
    }

    @Test
    void reportsElementsWithoutAKeyOrWithTheSameKeyAsAnother() throws Exception
    {
        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState fields = parse(withUpdateContent(), serviceTask(
                "<iap:updateContent>"
                + "<iap:fields label=\"Nameless\"/>"
                + "<iap:fields name=\"kept\" label=\"First\"/>"
                + "<iap:fields name=\" kept \" label=\"Second\"/>"
                + "</iap:updateContent>")).getChildNode(TASK).getChildNode(FIELDS);

            assertEquals(List.of("an element of a structured parameter has no key",
                "an element of a structured parameter has the same key as another"), problems.names());
            assertEquals(FIELDS, problems.all().get(0).details().get(PARAMETER_DETAIL));
            assertEquals("kept", problems.all().get(1).details().get(KEY_DETAIL));
            assertEquals(List.of("kept"), names(fields.getProperty(CHILD_ORDER).getValue(Type.NAMES)));
            assertEquals("First", fields.getChildNode("kept").getString(LABEL));
        }
    }

    @Test
    void reportsARepeatedStructureWithoutAUsableKeyField() throws Exception
    {
        final NodeBuilder root = VocabularyFixture.repository();
        final NodeBuilder type = VocabularyFixture.handler(root, "Keyless", "keyless");
        structure(type, "none", 0);
        VocabularyFixture.parameter(fields(structure(type, "missing", 0).setProperty(KEY_PARAMETER, "nothing")), NAME,
            TEXT, 0, 1);
        VocabularyFixture.parameter(fields(structure(type, "listed", 0).setProperty(KEY_PARAMETER, NAME)), NAME,
            TEXT, 0, 0);
        structure(fields(structure(type, "nested", 0).setProperty(KEY_PARAMETER, NAME)), NAME, 1);

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState task = parse(root, serviceTask("<iap:keyless><iap:none/><iap:missing name=\"a\"/>"
                + "<iap:listed><iap:name>a</iap:name></iap:listed><iap:nested><iap:name/></iap:nested>"
                + "</iap:keyless>")).getChildNode(TASK);

            assertEquals(4, problems.all().size(), () -> String.valueOf(problems.messages()));
            problems.all().forEach(problem -> assertEquals(
                "a repeated structured parameter declares no single-valued key field", problem.problem()));
            assertFalse(task.hasChildNode("none") || task.hasChildNode("missing") || task.hasChildNode("listed")
                || task.hasChildNode("nested"));
        }
    }

    @Test
    void reportsAStructureOutsideAnExtensionElement() throws Exception
    {
        final NodeBuilder root = VocabularyFixture.repository();
        structure(VocabularyFixture.handler(root, "Inline", "inline"), "elsewhere", 1)
            .setProperty(XML_LOCATION, "attribute");
        final NodeBuilder bare =
            VocabularyFixture.entry(root, "Bare", "bpmn:scriptTask", VocabularyFixture.ACTIVITY, 10);
        structure(bare, "loose", 0);

        try (RecordedProblems problems = new RecordedProblems()) {
            parse(root, serviceTask("<iap:inline/>") + "    <bpmn:scriptTask id=\"script1\"/>\n");

            assertEquals(List.of("a structured parameter is not written inside an extension element",
                "a structured parameter is not written inside an extension element"), problems.names());
        }
    }

    @Test
    void reportsAStructureWrittenAsAnAttributeOrLeftOut() throws Exception
    {
        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState task = parse(withConfigure(), serviceTask("<iap:configure target=\"/a\"/>"))
                .getChildNode(TASK);

            assertEquals(List.of("a structured parameter is written as an attribute instead",
                "a required parameter has no value"), problems.names());
            assertFalse(task.hasChildNode(TARGET));
        }
        try (RecordedProblems problems = new RecordedProblems()) {
            parse(withUpdateContent(), serviceTask("<iap:updateContent/>"));

            assertEquals(List.of(), problems.messages(), "an optional structure may be left out");
        }
    }

    @Test
    void reportsAFieldDeclaredOutsideItsStructure() throws Exception
    {
        final NodeBuilder root = withConfigure();
        fields(root.child(WORKFLOW_TYPES).child("Configure").child(TARGET)).child("flag")
            .setProperty(PRIMARY_TYPE, "wf:Parameter", Type.NAME)
            .setProperty(DATA_TYPE, Parameter.TYPE_TEXT)
            .setProperty(XML_LOCATION, "attribute");

        try (RecordedProblems problems = new RecordedProblems()) {
            parse(root, serviceTask("<iap:configure><iap:target path=\"/a\" flag=\"on\"/></iap:configure>"));

            assertEquals(List.of("a field of a structured parameter is not written inside it"),
                problems.names());
            assertEquals("target/flag", problems.all().get(0).details().get(PARAMETER_DETAIL));
        }
    }

    @Test
    void reportsAFieldDeclaredBesideItsStructuresParametersNode() throws Exception
    {
        final NodeBuilder root = withConfigure();
        VocabularyFixture.parameter(root.child(WORKFLOW_TYPES).child("Configure").child(TARGET), "flag", TEXT, 0, 1);

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState stored = parse(root, serviceTask("<iap:configure><iap:target path=\"/a\"/>"
                + "</iap:configure>")).getChildNode(TASK).getChildNode(TARGET);

            assertEquals(List.of("a vocabulary entry declares a field outside its structure's parameters node"),
                problems.names());
            assertEquals("target/flag", problems.all().get(0).details().get(PARAMETER_DETAIL));
            assertEquals("/a", stored.getString(PATH), "the fields that are declared properly are still read");
        }
    }

    @Test
    void namesAStrayDeclarationInsideANestedStructureByItsPath() throws Exception
    {
        final NodeBuilder root = withUpdateContent();
        final NodeBuilder fields = root.child(WORKFLOW_TYPES).child("UpdateContent").child(FIELDS)
            .child(Parameter.FIELDS_NODE);
        VocabularyFixture.parameter(fields.child(CHOICES), "hint", TEXT, 0, 1);
        fields.child("note").setProperty(PRIMARY_TYPE, NT_UNSTRUCTURED, Type.NAME)
            .setProperty("sling:resourceType", Parameter.RESOURCE_TYPE);

        try (RecordedProblems problems = new RecordedProblems()) {
            parse(root, serviceTask("<iap:updateContent><iap:fields name=\"title\"/></iap:updateContent>"));

            assertEquals(List.of("a vocabulary entry holds a parameter that is not a wf:Parameter node",
                "a vocabulary entry declares a field outside its structure's parameters node"),
                problems.names());
            assertEquals("fields/parameters/note", problems.all().get(0).details().get(PARAMETER_DETAIL));
            assertEquals("fields/parameters/choices/hint", problems.all().get(1).details().get(PARAMETER_DETAIL));
        }
    }

    @Test
    void readsFieldsNamedLikeThePropertiesOfTheirStructure() throws Exception
    {
        final NodeBuilder root = VocabularyFixture.repository();
        final NodeBuilder fields = fields(structure(VocabularyFixture.handler(root, "Describe", "describe"), FIELDS, 0)
            .setProperty(KEY_PARAMETER, NAME));
        VocabularyFixture.parameter(fields, NAME, TEXT, 1, 1);
        VocabularyFixture.parameter(fields, LABEL, TEXT, 1, 1);
        final NodeBuilder dataType = VocabularyFixture.parameter(fields, DATA_TYPE, TEXT, 0, 1);
        for (final String offered : List.of("long", "text")) {
            dataType.child(offered).setProperty(PRIMARY_TYPE, "wf:ParameterOption", Type.NAME)
                .setProperty(VALUE, offered);
        }

        try (RecordedProblems problems = new RecordedProblems()) {
            final NodeState stored = parse(root, serviceTask("<iap:describe>"
                + "<iap:fields name=\"age\" label=\"Age\" dataType=\"long\"/>"
                + "<iap:fields name=\"odd\" label=\"Odd\" dataType=\"structure\"/>"
                + "<iap:fields name=\"bare\"/>"
                + "</iap:describe>")).getChildNode(TASK).getChildNode(FIELDS);

            assertEquals(List.of("a parameter has a value it does not offer", "a required parameter has no value"),
                problems.names());
            assertEquals("fields/odd/dataType", problems.all().get(0).details().get(PARAMETER_DETAIL));
            assertEquals("fields/bare/label", problems.all().get(1).details().get(PARAMETER_DETAIL));
            assertEquals("Age", stored.getChildNode("age").getString(LABEL));
            assertEquals("long", stored.getChildNode("age").getString(DATA_TYPE));
            assertEquals("Odd", stored.getChildNode("odd").getString(LABEL));
        }
    }

    /**
     * An entry shaped like the content-editing step's: a list of field declarations keyed by name, each with keyed
     * choices and an optional condition holding a list.
     */
    private NodeBuilder withUpdateContent()
    {
        final NodeBuilder root = VocabularyFixture.repository();
        final NodeBuilder type = VocabularyFixture.handler(root, "UpdateContent", UPDATE_CONTENT);
        final NodeBuilder fields = fields(structure(type, FIELDS, 0).setProperty(KEY_PARAMETER, NAME));
        VocabularyFixture.parameter(fields, NAME, TEXT, 1, 1);
        VocabularyFixture.parameter(fields, LABEL, TEXT, 0, 1);
        VocabularyFixture.parameter(fields, REFERENCE_TYPE, TEXT, 0, 1);
        VocabularyFixture.parameter(fields, REFERENCE_ROOT, TEXT, 0, 1);
        final NodeBuilder choices = fields(structure(fields, CHOICES, 0).setProperty(KEY_PARAMETER, VALUE));
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
            .setProperty("minValues", 1L).setProperty(JCR_NODE_TYPE, "sling:Folder"));
        VocabularyFixture.parameter(target, PATH, TEXT, 1, 1);
        VocabularyFixture.parameter(target, TAGS, TEXT, 0, 0);
        VocabularyFixture.parameter(target, DEPTH, Parameter.TYPE_LONG, 0, 1);
        return root;
    }

    private NodeBuilder structure(final NodeBuilder parent, final String name, final long maxValues)
    {
        return VocabularyFixture.parameter(parent, name, Parameter.TYPE_STRUCTURE, 0, maxValues);
    }

    /** The node a structure's fields are declared under. */
    private NodeBuilder fields(final NodeBuilder structure)
    {
        return structure.child(Parameter.FIELDS_NODE).setProperty(PRIMARY_TYPE, "wf:Parameters", Type.NAME);
    }

    private static List<String> names(final Iterable<String> values)
    {
        final List<String> result = new ArrayList<>();
        values.forEach(result::add);
        return result;
    }
}

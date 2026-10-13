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
package io.uhndata.iap.workflows.models;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import jakarta.json.JsonObject;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Parameter}, the declaration the properties panel builds a field from.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ParameterTest
{
    private static final String ENTRY_PATH = "/WorkflowTypes/SetState";

    private static final String VALUE = "value";

    private static final String LABEL = "label";

    private static final String OPTIONS = "options";

    private static final String PARAMETERS = "parameters";

    private static final String PRIMARY_TYPE = "jcr:primaryType";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
        WorkflowFixture.answerNodeTypes(this.context, Map.of("x:Field", List.of(Parameter.NODE_TYPE),
            "x:Choice", List.of(Parameter.OPTION_NODE_TYPE)));
        this.context.create().resource(ENTRY_PATH, TYPE, ActivityType.RESOURCE_TYPE);
    }

    @Test
    void exposesWhatAFieldNeeds()
    {
        final Parameter parameter = this.createParameter("toState", Map.of(
            LABEL, "New state",
            "description", "The state the version is moved to.",
            "dataType", Parameter.TYPE_TEXT,
            "minValues", 1L,
            "maxValues", 1L,
            "xmlLocation", Parameter.LOCATION_EXTENSION_ELEMENT));

        assertNotNull(parameter);
        assertEquals("toState", parameter.getName());
        assertEquals("New state", parameter.getLabel());
        assertEquals("The state the version is moved to.", parameter.getDescription());
        assertEquals(Parameter.TYPE_TEXT, parameter.getDataType());
        assertEquals(1L, parameter.getMinValues());
        assertEquals(1L, parameter.getMaxValues());
        assertNull(parameter.getReferenceType());
        assertEquals(Parameter.LOCATION_EXTENSION_ELEMENT, parameter.getXmlLocation());
        assertNull(parameter.getXmlPath());
        assertTrue(parameter.getOptions().isEmpty());
    }

    @Test
    void readsWhatARestrictedReferenceRequires()
    {
        final Parameter parameter = this.createParameter("template", Map.of(
            LABEL, "Template", "dataType", Parameter.TYPE_REFERENCE, "referenceType", "mail:Template",
            "xmlLocation", Parameter.LOCATION_ATTRIBUTE));

        assertEquals(Parameter.TYPE_REFERENCE, parameter.getDataType());
        assertEquals("mail:Template", parameter.getReferenceType());
        assertEquals(Parameter.LOCATION_ATTRIBUTE, parameter.getXmlLocation());
    }

    @Test
    void fallsBackOnWhatTheNodeTypeWouldHaveAutocreated()
    {
        final Parameter parameter = this.createParameter("recipients", Map.of());

        assertEquals("recipients", parameter.getLabel());
        assertNull(parameter.getDescription());
        assertEquals(Parameter.TYPE_TEXT, parameter.getDataType());
        assertEquals(0L, parameter.getMinValues());
        assertEquals(1L, parameter.getMaxValues());
        assertEquals(Parameter.LOCATION_EXTENSION_ELEMENT, parameter.getXmlLocation());
    }

    @Test
    void fallsBackOnTheNameWhenTheLabelIsEmpty()
    {
        assertEquals("fromStates", this.createParameter("fromStates", Map.of(LABEL, "")).getLabel());
    }

    @Test
    void readsTheValuesItOffers()
    {
        this.createParameter("toState", Map.of(LABEL, "New state"));
        this.createOption("toState", "active", Map.of(VALUE, "ACTIVE", LABEL, "Active"));
        // Labelled by its own value, so an option that needs only one string declares only that one
        this.createOption("toState", "retired", Map.of(VALUE, "RETIRED"));
        this.createOption("toState", "unnamed", Map.of(LABEL, "Nothing"));
        // Read by node type, subtypes included, and not by resource type
        this.context.create().resource(ENTRY_PATH + "/toState/byResourceType",
            Map.of(TYPE, Parameter.OPTION_RESOURCE_TYPE, VALUE, "BY_RESOURCE_TYPE"));
        this.context.create().resource(ENTRY_PATH + "/toState/bySubtype",
            Map.of(PRIMARY_TYPE, "x:Choice", VALUE, "BY_SUBTYPE"));
        // A child that is not an option at all is walked past
        this.context.create().resource(ENTRY_PATH + "/toState/note", TYPE, "nt/unstructured");
        final Parameter parameter = this.parameter("toState");

        assertEquals(List.of(new Parameter.Option("ACTIVE", "Active"),
            new Parameter.Option("RETIRED", "RETIRED"), new Parameter.Option("BY_SUBTYPE", "BY_SUBTYPE")),
            parameter.getOptions());
    }

    @Test
    void serializesEverythingThePanelNeeds()
    {
        this.createParameter("fromStates", Map.of(
            LABEL, "Applies to", "description", "The states this move is for.",
            "dataType", Parameter.TYPE_TEXT, "minValues", 1L, "maxValues", 0L,
            "xmlLocation", Parameter.LOCATION_BPMN_ELEMENT, "xmlPath", "timerEventDefinition"));
        this.createOption("fromStates", "draft", Map.of(VALUE, "DRAFT", LABEL, "Draft"));

        final JsonObject json = this.parameter("fromStates").toJsonBuilder().build();

        assertEquals("fromStates", json.getString("name"));
        assertEquals("Applies to", json.getString(LABEL));
        assertEquals("The states this move is for.", json.getString("description"));
        assertEquals(Parameter.TYPE_TEXT, json.getString("dataType"));
        assertEquals(1, json.getInt("minValues"));
        assertEquals(0, json.getInt("maxValues"));
        assertEquals(Parameter.LOCATION_BPMN_ELEMENT, json.getString("xmlLocation"));
        assertEquals("timerEventDefinition", json.getString("xmlPath"));
        assertEquals(1, json.getJsonArray(OPTIONS).size());
        assertEquals("DRAFT", json.getJsonArray(OPTIONS).getJsonObject(0).getString(VALUE));
        assertEquals("Draft", json.getJsonArray(OPTIONS).getJsonObject(0).getString(LABEL));
    }

    @Test
    void leavesOutWhatAParameterDoesNotDeclare()
    {
        final Parameter parameter = this.createParameter("recipients", Map.of(
            LABEL, "Recipients", "dataType", Parameter.TYPE_PRINCIPAL));

        final JsonObject json = parameter.toJsonBuilder().build();

        assertFalse(json.containsKey("description"));
        assertFalse(json.containsKey("referenceType"));
        assertFalse(json.containsKey(OPTIONS));
        assertFalse(json.containsKey("xmlPath"));
    }

    @Test
    void serializesTheTypeAReferenceIsRestrictedTo()
    {
        final Parameter parameter = this.createParameter("workflowFrom", Map.of(
            LABEL, "Workflow", "dataType", Parameter.TYPE_REFERENCE, "referenceType", "wf:WorkflowDefinition"));

        assertEquals("wf:WorkflowDefinition", parameter.toJsonBuilder().build().getString("referenceType"));
    }

    @Test
    void declaresTheFieldsAndKeyOfAStructure()
    {
        final Map<String, Object> structure = Map.of(LABEL, "Fields", "dataType", Parameter.TYPE_STRUCTURE,
            "maxValues", 0L, "keyParameter", "name", "jcrNodeType", "sling:Folder");
        final Parameter parameter = this.createParameter("fields", structure);
        this.createParameter("fields/parameters/name", Map.of(LABEL, "Name", "minValues", 1L));
        this.createParameter("fields/parameters/choices",
            Map.of(LABEL, "Choices", "dataType", Parameter.TYPE_STRUCTURE));
        this.createParameter("fields/parameters/choices/parameters/value", Map.of(LABEL, "Value"));
        this.createOption("fields/parameters", "notAField", Map.of(VALUE, "x"));
        this.context.create().resource(ENTRY_PATH + "/fields/parameters/byResourceType",
            Map.of(TYPE, Parameter.RESOURCE_TYPE, LABEL, "By resource type"));
        this.context.create().resource(ENTRY_PATH + "/fields/parameters/bySubtype",
            Map.of(PRIMARY_TYPE, "x:Field", LABEL, "By subtype"));
        this.createParameter("fields/outside", Map.of(LABEL, "Outside"));

        assertEquals("name", parameter.getKeyParameter());
        assertEquals(List.of("name", "choices", "bySubtype"),
            parameter.getParameters().stream().map(Parameter::getName).toList());
        final JsonObject json = parameter.toJsonBuilder().build();
        assertEquals("name", json.getString("keyParameter"));
        assertEquals("choices", json.getJsonArray(PARAMETERS).getJsonObject(1).getString("name"));
        assertEquals("value", json.getJsonArray(PARAMETERS).getJsonObject(1).getJsonArray(PARAMETERS)
            .getJsonObject(0).getString("name"));
    }

    @Test
    void readsFieldsNamedLikeThePropertiesOfTheirStructureFromJsonContent()
    {
        final String json = "{\"jcr:primaryType\": \"wf:Parameter\", \"label\": \"Field\","
            + " \"dataType\": \"structure\", \"parameters\": {"
            + " \"label\": {\"jcr:primaryType\": \"wf:Parameter\", \"label\": \"Label\"},"
            + " \"dataType\": {\"jcr:primaryType\": \"wf:Parameter\", \"label\": \"Data type\"}}}";
        this.context.load().json(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
            ENTRY_PATH + "/fields");
        final Parameter parameter = this.parameter("fields");

        assertEquals("Field", parameter.getLabel());
        assertEquals(Parameter.TYPE_STRUCTURE, parameter.getDataType());
        assertEquals(List.of("Label", "Data type"),
            parameter.getParameters().stream().map(Parameter::getLabel).toList());
        assertEquals(List.of(LABEL, "dataType"),
            parameter.getParameters().stream().map(Parameter::getName).toList());
    }

    @Test
    void declaresNoFieldsOrKeyByDefault()
    {
        final Parameter parameter = this.createParameter("target", Map.of(LABEL, "Target"));

        assertNull(parameter.getKeyParameter());
        assertTrue(parameter.getParameters().isEmpty());
        assertFalse(parameter.toJsonBuilder().build().containsKey(PARAMETERS));
        assertFalse(parameter.toJsonBuilder().build().containsKey("keyParameter"));
    }

    @Test
    void walksPastAChildThatCannotSayWhatNodeTypeItIs() throws RepositoryException
    {
        final Node broken = Mockito.mock(Node.class);
        Mockito.when(broken.isNodeType(Mockito.anyString())).thenThrow(new RepositoryException("Gone"));
        final Resource unreadable = Mockito.mock(Resource.class);
        Mockito.when(unreadable.adaptTo(Node.class)).thenReturn(broken);
        final Resource unstored = Mockito.mock(Resource.class);
        final Resource parent = Mockito.mock(Resource.class);
        Mockito.when(parent.getChildren()).thenReturn(List.of(unreadable, unstored));

        assertTrue(Parameter.declaredIn(parent).isEmpty());
    }

    private Parameter parameter(final String name)
    {
        return this.context.resourceResolver().getResource(ENTRY_PATH + "/" + name).adaptTo(Parameter.class);
    }

    private Parameter createParameter(final String name, final Map<String, Object> properties)
    {
        final Map<String, Object> all = new HashMap<>(properties);
        all.put(TYPE, Parameter.RESOURCE_TYPE);
        all.put(PRIMARY_TYPE, Parameter.NODE_TYPE);
        return this.context.create().resource(ENTRY_PATH + "/" + name, all).adaptTo(Parameter.class);
    }

    private void createOption(final String parameter, final String name, final Map<String, Object> properties)
    {
        final Map<String, Object> all = new HashMap<>(properties);
        all.put(TYPE, Parameter.OPTION_RESOURCE_TYPE);
        all.put(PRIMARY_TYPE, Parameter.OPTION_NODE_TYPE);
        this.context.create().resource(ENTRY_PATH + "/" + parameter + "/" + name, all);
    }
}

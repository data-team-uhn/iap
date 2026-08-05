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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.JsonObject;
import jakarta.json.JsonString;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FlowNodeType} and its concrete subtypes, including the JSON the visual editor reads.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class FlowNodeTypeTest
{
    private static final String TYPES_PATH = "/WorkflowTypes";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
        this.context.create().resource(TYPES_PATH, TYPE, WorkflowTypesHomepage.RESOURCE_TYPE);
    }

    @Test
    void exposesTheParserTable()
    {
        final Resource resource = this.context.create().resource(TYPES_PATH + "/MessageStartEvent", Map.of(
            TYPE, CatchingEventType.RESOURCE_TYPE,
            "label", "Message Start Event",
            "description", "Started by a named message.",
            "priority", 10L,
            "xmlElement", "bpmn:startEvent",
            "xmlChildElement", "bpmn:messageEventDefinition",
            "jcrNodeType", "wf:StartEvent",
            "properties", new String[] {"messageRef"}));
        final FlowNodeType type = resource.adaptTo(FlowNodeType.class);

        assertNotNull(type);
        assertEquals("MessageStartEvent", type.getName());
        assertEquals("Message Start Event", type.getDocumentationLabel());
        assertEquals("Started by a named message.", type.getDescription());
        assertEquals(10L, type.getPriority());
        assertEquals("bpmn:startEvent", type.getXmlElement());
        assertEquals("bpmn:messageEventDefinition", type.getXmlChildElement());
        assertEquals("wf:StartEvent", type.getJcrNodeType());
        assertEquals(List.of("messageRef"), type.getProperties());
    }

    @Test
    void toleratesAnEntryWithOnlyWhatItMustHave()
    {
        final FlowNodeType type = this.createType("StartEvent", CatchingEventType.RESOURCE_TYPE, Map.of(
            "label", "Start Event", "priority", 0L, "xmlElement", "bpmn:startEvent",
            "jcrNodeType", "wf:StartEvent"));

        assertNull(type.getDescription());
        assertNull(type.getXmlChildElement());
        assertNull(type.getJcrProperties());
        assertTrue(type.getProperties().isEmpty());
    }

    @Test
    void parsesTheFixedPropertiesAsJson()
    {
        final FlowNodeType type = this.createType("IntermediateCatchEvent", CatchingEventType.RESOURCE_TYPE, Map.of(
            "label", "Intermediate Catch Event", "priority", 0L, "xmlElement", "bpmn:intermediateCatchEvent",
            "jcrNodeType", "wf:IntermediateCatchingEvent", "jcrProperties", "{\"catching\": true}"));

        final JsonObject fixed = type.getJcrProperties();

        assertNotNull(fixed);
        assertTrue(fixed.getBoolean("catching"));
    }

    @Test
    void ignoresFixedPropertiesThatAreNotValidJson()
    {
        // One badly authored vocabulary entry must not take the whole catalogue down with it
        final FlowNodeType type = this.createType("Broken", ActivityType.RESOURCE_TYPE, Map.of(
            "label", "Broken", "priority", 0L, "xmlElement", "bpmn:task", "jcrNodeType", "wf:Activity",
            "jcrProperties", "{catching: true}"));

        assertNull(type.getJcrProperties());
    }

    @Test
    void fallsBackOnTheCategoryImpliedByTheKindOfEntry()
    {
        assertEquals(List.of("Events"),
            this.createType("A", CatchingEventType.RESOURCE_TYPE, Map.of()).getDocumentationCategories());
        assertEquals(List.of("Events"),
            this.createType("B", ThrowingEventType.RESOURCE_TYPE, Map.of()).getDocumentationCategories());
        assertEquals(List.of("Activities"),
            this.createType("C", ActivityType.RESOURCE_TYPE, Map.of()).getDocumentationCategories());
        assertEquals(List.of("Gateways"),
            this.createType("D", GatewayType.RESOURCE_TYPE, Map.of()).getDocumentationCategories());
        // An entry that declares an empty list falls back too, rather than ending up in no toolbar group at all
        assertEquals(List.of("Events"), this.createType("E", CatchingEventType.RESOURCE_TYPE,
            Map.of("category", new String[0])).getDocumentationCategories());
    }

    @Test
    void prefersTheDeclaredCategories()
    {
        final FlowNodeType type = this.createType("StartEvent", CatchingEventType.RESOURCE_TYPE,
            Map.of("category", new String[] {"Start Events", "Favourites"}));

        assertEquals(List.of("Start Events", "Favourites"), type.getDocumentationCategories());
    }

    @Test
    void serializesEverythingTheEditorNeeds()
    {
        final FlowNodeType type = this.createType("MessageStartEvent", CatchingEventType.RESOURCE_TYPE, Map.of(
            "label", "Message Start Event",
            "description", "Started by a named message.",
            "category", new String[] {"Start Events"},
            "priority", 10L,
            "xmlElement", "bpmn:startEvent",
            "xmlChildElement", "bpmn:messageEventDefinition",
            "jcrNodeType", "wf:StartEvent",
            "jcrProperties", "{\"catching\": true}",
            "properties", new String[] {"messageRef"}));

        final JsonObject json = type.toDocumentationJson();

        assertEquals("MessageStartEvent", json.getString("name"));
        assertEquals("Message Start Event", json.getString("label"));
        assertEquals("Started by a named message.", json.getString("description"));
        assertEquals(List.of("Start Events"),
            json.getJsonArray("category").getValuesAs(JsonString.class).stream()
                .map(JsonString::getString).toList());
        assertEquals(10, json.getInt("priority"));
        assertEquals("bpmn:startEvent", json.getString("xmlElement"));
        assertEquals("bpmn:messageEventDefinition", json.getString("xmlChildElement"));
        assertEquals("wf:StartEvent", json.getString("jcrNodeType"));
        assertTrue(json.getJsonObject("jcrProperties").getBoolean("catching"));
        assertEquals(1, json.getJsonArray("properties").size());
    }

    @Test
    void leavesOutTheFieldsAnEntryDoesNotHave()
    {
        final FlowNodeType type = this.createType("StartEvent", CatchingEventType.RESOURCE_TYPE, Map.of(
            "label", "Start Event", "priority", 0L, "xmlElement", "bpmn:startEvent",
            "jcrNodeType", "wf:StartEvent"));

        final JsonObject json = type.toDocumentationJson();

        assertFalse(json.containsKey("description"));
        assertFalse(json.containsKey("xmlChildElement"));
        assertFalse(json.containsKey("jcrProperties"));
        assertFalse(json.containsKey("properties"));
        // The category is never left out: it falls back on the one the kind of entry implies
        assertEquals(1, json.getJsonArray("category").size());
    }

    @Test
    void adaptsEveryConcreteEntryKindToItsOwnModel()
    {
        assertEquals(CatchingEventType.class,
            this.createType("A", CatchingEventType.RESOURCE_TYPE, Map.of()).getClass());
        assertEquals(ThrowingEventType.class,
            this.createType("B", ThrowingEventType.RESOURCE_TYPE, Map.of()).getClass());
        assertEquals(ActivityType.class,
            this.createType("C", ActivityType.RESOURCE_TYPE, Map.of()).getClass());
        assertEquals(GatewayType.class,
            this.createType("D", GatewayType.RESOURCE_TYPE, Map.of()).getClass());
    }

    private FlowNodeType createType(final String name, final String resourceType,
        final Map<String, Object> properties)
    {
        final Map<String, Object> all = new HashMap<>(properties);
        all.put(TYPE, resourceType);
        return this.context.create().resource(TYPES_PATH + "/" + name, all).adaptTo(FlowNodeType.class);
    }
}

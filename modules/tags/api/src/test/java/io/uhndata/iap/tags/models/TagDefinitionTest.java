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
package io.uhndata.iap.tags.models;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.json.JsonObject;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TagDefinition}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class TagDefinitionTest
{
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, TagDefinition.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/Tags/draft",
            "sling:resourceType", "iap/TagDefinition");
        assertNotNull(resource.adaptTo(TagDefinition.class));
    }

    @Test
    void exposesDefinitionProperties()
    {
        final Resource resource = this.context.create().resource("/Tags/incomplete", Map.of(
            "sling:resourceType", "iap/TagDefinition",
            "label", "Incomplete",
            "description", "Some required answers are missing",
            "category", new String[] { "lifecycle", "validation" },
            "aggregated", true,
            "targetResources", new String[] { "iap/Entity", "iap/EntityPart" },
            "color", "#ff9800",
            "order", 25L,
            "system", true));
        final TagDefinition definition = resource.adaptTo(TagDefinition.class);

        assertEquals("incomplete", definition.getName());
        assertEquals("Incomplete", definition.getLabel());
        assertEquals("Some required answers are missing", definition.getDescription());
        assertEquals(List.of("lifecycle", "validation"), definition.getCategories());
        assertFalse(definition.isInheritable());
        assertTrue(definition.isAggregated());
        assertEquals(List.of("iap/Entity", "iap/EntityPart"), definition.getTargetResources());
        assertEquals("#ff9800", definition.getColor());
        assertEquals(25L, definition.getOrder());
        assertTrue(definition.isSystem());
    }

    @Test
    void toleratesMissingOptionalProperties()
    {
        final Resource resource = this.context.create().resource("/Tags/bare",
            "sling:resourceType", "iap/TagDefinition");
        final TagDefinition definition = resource.adaptTo(TagDefinition.class);

        assertEquals("bare", definition.getName());
        // The label falls back to the tag name
        assertEquals("bare", definition.getLabel());
        assertNull(definition.getDescription());
        assertEquals(List.of(), definition.getCategories());
        assertFalse(definition.isInheritable());
        assertFalse(definition.isAggregated());
        assertEquals(List.of(), definition.getTargetResources());
        assertNull(definition.getColor());
        assertNull(definition.getOrder());
        assertFalse(definition.isSystem());
    }

    @Test
    void explicitNameOverridesNodeName()
    {
        // An explicit name property allows tag strings that would be awkward as node names
        final Resource resource = this.context.create().resource("/Tags/patientSurvey", Map.of(
            "sling:resourceType", "iap/TagDefinition",
            "name", "PATIENT SURVEY"));
        final TagDefinition definition = resource.adaptTo(TagDefinition.class);

        assertEquals("PATIENT SURVEY", definition.getName());
        assertEquals("PATIENT SURVEY", definition.getLabel());
    }

    @Test
    void unrestrictedDefinitionAppliesToAnyResource()
    {
        final Resource resource = this.context.create().resource("/Tags/draft",
            "sling:resourceType", "iap/TagDefinition");
        final TagDefinition definition = resource.adaptTo(TagDefinition.class);
        final Resource target = this.context.create().resource("/data/whatever",
            "sling:resourceType", "iap/Content");

        assertTrue(definition.appliesTo(target));
    }

    @Test
    void restrictedDefinitionChecksResourceType()
    {
        final Resource resource = this.context.create().resource("/Tags/sensitive", Map.of(
            "sling:resourceType", "iap/TagDefinition",
            "targetResources", new String[] { "iap/Entity" }));
        final TagDefinition definition = resource.adaptTo(TagDefinition.class);
        final Resource entity = this.context.create().resource("/data/entity",
            "sling:resourceType", "iap/Entity");
        final Resource other = this.context.create().resource("/data/other",
            "sling:resourceType", "iap/Content");

        assertTrue(definition.appliesTo(entity));
        assertFalse(definition.appliesTo(other));
    }

    @Test
    void restrictedDefinitionAcceptsResourceSubtypes()
    {
        final Resource resource = this.context.create().resource("/Tags/sensitive", Map.of(
            "sling:resourceType", "iap/TagDefinition",
            "targetResources", new String[] { "iap/Entity" }));
        final TagDefinition definition = resource.adaptTo(TagDefinition.class);
        // A resource of a more specific type declaring iap/Entity as its supertype is accepted too
        final Resource subtype = this.context.create().resource("/data/submission", Map.of(
            "sling:resourceType", "sub/Submission",
            "sling:resourceSuperType", "iap/Entity"));

        assertTrue(definition.appliesTo(subtype));
    }

    @Test
    void displayOrderSortsByOrderThenName()
    {
        final TagDefinition first = this.context.create().resource("/Tags/zebra", Map.of(
            "sling:resourceType", "iap/TagDefinition", "order", 1L)).adaptTo(TagDefinition.class);
        final TagDefinition second = this.context.create().resource("/Tags/aardvark", Map.of(
            "sling:resourceType", "iap/TagDefinition", "order", 2L)).adaptTo(TagDefinition.class);
        final TagDefinition unorderedA = this.context.create().resource("/Tags/alpha",
            "sling:resourceType", "iap/TagDefinition").adaptTo(TagDefinition.class);
        final TagDefinition unorderedB = this.context.create().resource("/Tags/beta",
            "sling:resourceType", "iap/TagDefinition").adaptTo(TagDefinition.class);

        final List<TagDefinition> sorted = new ArrayList<>(List.of(unorderedB, second, unorderedA, first));
        sorted.sort(TagDefinition.DISPLAY_ORDER);

        // Explicitly ordered definitions first, then the unordered ones sorted by name
        assertEquals(List.of(first, second, unorderedA, unorderedB), sorted);
    }

    @Test
    void documentsItsBehaviors()
    {
        final Resource resource = this.context.create().resource("/Tags/sensitive", Map.of(
            "sling:resourceType", "iap/TagDefinition",
            "label", "Sensitive",
            "description", "Contains confidential data",
            "category", new String[] { "privacy" },
            "inheritable", true,
            "aggregated", true,
            "system", true,
            "targetResources", new String[] { "iap/Entity", "iap/EntityPart" }));
        final TagDefinition definition = resource.adaptTo(TagDefinition.class);

        // The generic documentation contract delegates to the tag-specific accessors
        assertEquals("Sensitive", definition.getDocumentationLabel());
        assertEquals(List.of("privacy"), definition.getDocumentationCategories());
        assertEquals(List.of(
            "**Inheritable**: implicitly carried by everything inside a tagged resource",
            "**Aggregated**: implicitly carried by a resource when anything inside it is tagged",
            "**System**: managed by the platform, cannot be manually added or removed",
            "**May only be placed on**: `iap/Entity`, `iap/EntityPart`"),
            definition.getDocumentationDetails());
    }

    @Test
    void plainTagsHaveNothingToCallOut()
    {
        final Resource resource = this.context.create().resource("/Tags/plain",
            "sling:resourceType", "iap/TagDefinition");
        assertTrue(resource.adaptTo(TagDefinition.class).getDocumentationDetails().isEmpty());
    }

    @Test
    void serializesTheFullDefinitionAsJson()
    {
        final Resource resource = this.context.create().resource("/Tags/sensitive", Map.of(
            "sling:resourceType", "iap/TagDefinition",
            "label", "Sensitive",
            "description", "Contains confidential data",
            "category", new String[] { "privacy" },
            "inheritable", true,
            "targetResources", new String[] { "iap/Entity" },
            "color", "#f44336",
            "order", 10L,
            "system", true));
        final JsonObject json = resource.adaptTo(TagDefinition.class).toDocumentationJson();

        assertEquals("sensitive", json.getString("name"));
        assertEquals("Sensitive", json.getString("label"));
        assertEquals("Contains confidential data", json.getString("description"));
        assertEquals("privacy", json.getJsonArray("category").getString(0));
        assertTrue(json.getBoolean("inheritable"));
        assertFalse(json.getBoolean("aggregated"));
        assertTrue(json.getBoolean("system"));
        assertEquals("iap/Entity", json.getJsonArray("targetResources").getString(0));
        assertEquals("#f44336", json.getString("color"));
        assertEquals(10, json.getJsonNumber("order").longValue());
        assertEquals("/Tags/sensitive", json.getString("path"));
    }

    @Test
    void jsonLeavesUnsetOptionalFieldsOut()
    {
        final Resource resource = this.context.create().resource("/Tags/plain",
            "sling:resourceType", "iap/TagDefinition");
        final JsonObject json = resource.adaptTo(TagDefinition.class).toDocumentationJson();

        assertEquals("plain", json.getString("name"));
        assertFalse(json.containsKey("description"));
        assertFalse(json.containsKey("category"));
        assertFalse(json.containsKey("targetResources"));
        assertFalse(json.containsKey("color"));
        assertFalse(json.containsKey("order"));
        assertEquals("/Tags/plain", json.getString("path"));
    }
}

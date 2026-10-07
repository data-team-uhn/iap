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
package io.uhndata.iap.links.models;

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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LinkDefinition} and its two kinds, {@link InternalLinkDefinition} and
 * {@link ExternalLinkDefinition}. The base is abstract and only reachable through a kind, so the settings it
 * holds are exercised through whichever one is convenient.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class LinkDefinitionTest
{
    private static final String SLING_RESOURCE_TYPE = "sling:resourceType";

    private static final String INTERNAL = InternalLinkDefinition.RESOURCE_TYPE;

    private static final String EXTERNAL = ExternalLinkDefinition.RESOURCE_TYPE;

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, InternalLinkDefinition.class,
            ExternalLinkDefinition.class);
    }

    @Test
    void adaptingToTheBaseYieldsTheActualKind()
    {
        final Resource internal = this.context.create().resource("/LinkTypes/references",
            SLING_RESOURCE_TYPE, INTERNAL);
        final Resource external = this.context.create().resource("/LinkTypes/ehrChart",
            SLING_RESOURCE_TYPE, EXTERNAL);

        assertInstanceOf(InternalLinkDefinition.class, internal.adaptTo(LinkDefinition.class));
        assertInstanceOf(ExternalLinkDefinition.class, external.adaptTo(LinkDefinition.class));
    }

    @Test
    void exposesTheConfiguredSettings()
    {
        final Resource resource = this.context.create().resource("/LinkTypes/references", Map.of(
            SLING_RESOURCE_TYPE, INTERNAL,
            "label", "References",
            "weak", true,
            "requiredSourceTypes", new String[]{ "sub:Submission" },
            "requiredDestinationTypes", new String[]{ "sub:Submission", "sch:Schema" },
            "targetLabelTemplate", "{typeLabel}: {name}",
            "onDelete", "RECURSIVE_DELETE"));
        final InternalLinkDefinition definition = resource.adaptTo(InternalLinkDefinition.class);

        assertEquals("References", definition.getLabel());
        assertTrue(definition.isDisplayed());
        assertTrue(definition.isWeak());
        assertArrayEquals(new String[]{ "sub:Submission" }, definition.getRequiredSourceTypes());
        assertEquals(2, definition.getRequiredDestinationTypes().length);
        assertEquals("{typeLabel}: {name}", definition.getTargetLabelTemplate());
        assertEquals(InternalLinkDefinition.OnDelete.RECURSIVE_DELETE, definition.getOnDeletePolicy());
        assertFalse(definition.hasBacklink());
        assertNull(definition.getBacklink());
        assertFalse(definition.isBacklinkOnly());
    }

    @Test
    void appliesDefaults()
    {
        final Resource resource = this.context.create().resource("/LinkTypes/bare",
            SLING_RESOURCE_TYPE, INTERNAL);
        final InternalLinkDefinition definition = resource.adaptTo(InternalLinkDefinition.class);

        // With no explicit label, the node name identifies the type
        assertEquals("bare", definition.getLabel());
        // Types are displayed unless they explicitly opt out
        assertTrue(definition.isDisplayed());
        assertFalse(definition.isWeak());
        assertNull(definition.getRequiredSourceTypes());
        assertNull(definition.getRequiredDestinationTypes());
        assertNull(definition.getTargetLabelTemplate());
        assertEquals(InternalLinkDefinition.OnDelete.REMOVE_LINK, definition.getOnDeletePolicy());
    }

    @Test
    void appliesExternalDefaults()
    {
        final ExternalLinkDefinition definition = this.context.create()
            .resource("/LinkTypes/bareExternal", SLING_RESOURCE_TYPE, EXTERNAL)
            .adaptTo(ExternalLinkDefinition.class);

        assertEquals("bareExternal", definition.getLabel());
        assertNull(definition.getValuePattern());
        assertNull(definition.getUrlTemplate());
    }

    @Test
    void fallsBackToRemoveLinkOnUnknownDeletePolicies()
    {
        final Resource resource = this.context.create().resource("/LinkTypes/odd", Map.of(
            SLING_RESOURCE_TYPE, INTERNAL,
            "onDelete", "EXPLODE"));

        assertEquals(InternalLinkDefinition.OnDelete.REMOVE_LINK,
            resource.adaptTo(InternalLinkDefinition.class).getOnDeletePolicy());
    }

    @Test
    void resolvesTheBacklinkDefinition()
    {
        this.context.create().resource("/LinkTypes/referencedBy", Map.of(
            SLING_RESOURCE_TYPE, INTERNAL,
            "backlinkOnly", true));
        final Resource resource = this.context.create().resource("/LinkTypes/references", Map.of(
            SLING_RESOURCE_TYPE, INTERNAL,
            "backlink", "/LinkTypes/referencedBy"));
        final InternalLinkDefinition definition = resource.adaptTo(InternalLinkDefinition.class);

        assertTrue(definition.hasBacklink());
        final InternalLinkDefinition backlink = definition.getBacklink();
        assertNotNull(backlink);
        assertEquals("/LinkTypes/referencedBy", backlink.getPath());
        assertTrue(backlink.isBacklinkOnly());
    }

    @Test
    void toleratesDanglingBacklinkPaths()
    {
        final Resource resource = this.context.create().resource("/LinkTypes/references", Map.of(
            SLING_RESOURCE_TYPE, INTERNAL,
            "backlink", "/LinkTypes/missing"));
        final InternalLinkDefinition definition = resource.adaptTo(InternalLinkDefinition.class);

        assertTrue(definition.hasBacklink());
        assertNull(definition.getBacklink());
    }

    @Test
    void ignoresABacklinkNamingAnExternalType()
    {
        // A backlink is itself a reference to content, so an external type cannot serve as one; the caller sees
        // the same unresolvable backlink it would for a dangling path
        this.context.create().resource("/LinkTypes/ehrChart", SLING_RESOURCE_TYPE, EXTERNAL);
        final Resource resource = this.context.create().resource("/LinkTypes/references", Map.of(
            SLING_RESOURCE_TYPE, INTERNAL,
            "backlink", "/LinkTypes/ehrChart"));

        assertNull(resource.adaptTo(InternalLinkDefinition.class).getBacklink());
    }

    @Test
    void typesCanOptOutOfDisplay()
    {
        final Resource resource = this.context.create().resource("/LinkTypes/plumbing", Map.of(
            SLING_RESOURCE_TYPE, INTERNAL,
            "displayed", false));

        assertFalse(resource.adaptTo(LinkDefinition.class).isDisplayed());
    }

    @Test
    void exposesExternalSettings()
    {
        final Resource resource = this.context.create().resource("/LinkTypes/ehrChart", Map.of(
            SLING_RESOURCE_TYPE, EXTERNAL,
            "valuePattern", "[0-9]+",
            "urlTemplate", "https://ehr.example.org/chart/{value}"));
        final ExternalLinkDefinition definition = resource.adaptTo(ExternalLinkDefinition.class);

        assertEquals("[0-9]+", definition.getValuePattern());
        assertEquals("https://ehr.example.org/chart/{value}", definition.getUrlTemplate());
    }

    @Test
    void documentsItsReferenceBehaviors()
    {
        final Resource resource = this.context.create().resource("/LinkTypes/references", Map.of(
            SLING_RESOURCE_TYPE, INTERNAL,
            "label", "References",
            "description", "A generic pointer to related material.",
            "weak", true,
            "backlink", "/LinkTypes/referencedBy",
            "onDelete", "IGNORE",
            "requiredSourceTypes", new String[]{ "sub:Submission" },
            "requiredDestinationTypes", new String[]{ "sub:Submission", "sch:Schema" },
            "displayed", false));
        final LinkDefinition definition = resource.adaptTo(LinkDefinition.class);

        assertEquals("References", definition.getDocumentationLabel());
        assertEquals("A generic pointer to related material.", definition.getDescription());
        assertEquals(List.of(
            "**Weak**: the link may break when the linked resource is deleted,"
                + " instead of preventing the deletion",
            "**Backlink**: a reverse `/LinkTypes/referencedBy` link is automatically added"
                + " on the linked content",
            "**On delete**: kept as a broken reference when the linked resource is deleted",
            "**May only be placed on**: `sub:Submission`",
            "**May only point at**: `sub:Submission`, `sch:Schema`",
            "**Hidden**: not shown in the user-facing UI"), definition.getDocumentationDetails());
    }

    @Test
    void documentsBacklinkOnlyAndRecursiveDeletion()
    {
        final Resource resource = this.context.create().resource("/LinkTypes/referencedBy", Map.of(
            SLING_RESOURCE_TYPE, INTERNAL,
            "backlinkOnly", true,
            "onDelete", "RECURSIVE_DELETE"));

        assertEquals(List.of(
            "**Backlink only**: never created directly, only as the automatic reverse of another link",
            "**On delete**: the linking resource is deleted together with the linked resource"),
            resource.adaptTo(LinkDefinition.class).getDocumentationDetails());
    }

    @Test
    void documentsItsExternalBehaviors()
    {
        final Resource resource = this.context.create().resource("/LinkTypes/ehrChart", Map.of(
            SLING_RESOURCE_TYPE, EXTERNAL,
            "valuePattern", "[0-9]+",
            "urlTemplate", "https://ehr.example.org/chart/{value}"));

        assertEquals(List.of(
            "**External**: records a value pointing outside the repository",
            "**Value pattern**: `[0-9]+`",
            "**URL template**: `https://ehr.example.org/chart/{value}`"),
            resource.adaptTo(LinkDefinition.class).getDocumentationDetails());
    }

    @Test
    void plainTypesHaveOnlyTheirKindToCallOut()
    {
        final Resource internal = this.context.create().resource("/LinkTypes/bare",
            SLING_RESOURCE_TYPE, INTERNAL);
        final Resource external = this.context.create().resource("/LinkTypes/bareExternal",
            SLING_RESOURCE_TYPE, EXTERNAL);

        // An internal type with no settings has nothing worth saying about it
        assertTrue(internal.adaptTo(LinkDefinition.class).getDocumentationDetails().isEmpty());
        assertNull(internal.adaptTo(LinkDefinition.class).getDescription());
        // An external one always says so, since where its target lives is the whole distinction
        assertEquals(List.of("**External**: records a value pointing outside the repository"),
            external.adaptTo(LinkDefinition.class).getDocumentationDetails());
    }

    @Test
    void emptyTypeRestrictionsAreNotDocumented()
    {
        final Resource resource = this.context.create().resource("/LinkTypes/references", Map.of(
            SLING_RESOURCE_TYPE, INTERNAL,
            "requiredSourceTypes", new String[0],
            "requiredDestinationTypes", new String[0]));
        final LinkDefinition definition = resource.adaptTo(LinkDefinition.class);

        assertTrue(definition.getDocumentationDetails().isEmpty());
        final JsonObject json = definition.toDocumentationJson();
        assertFalse(json.containsKey("requiredSourceTypes"));
        assertFalse(json.containsKey("requiredDestinationTypes"));
    }

    @Test
    void serializesTheFullInternalDefinitionAsJson()
    {
        final Resource resource = this.context.create().resource("/LinkTypes/references", Map.of(
            SLING_RESOURCE_TYPE, INTERNAL,
            "label", "References",
            "description", "A generic pointer to related material.",
            "weak", true,
            "backlink", "/LinkTypes/referencedBy",
            "requiredSourceTypes", new String[]{ "sub:Submission" },
            "requiredDestinationTypes", new String[]{ "sub:Submission", "sch:Schema" },
            "onDelete", "RECURSIVE_DELETE"));

        final JsonObject json = resource.adaptTo(LinkDefinition.class).toDocumentationJson();

        assertEquals("references", json.getString("name"));
        assertEquals("References", json.getString("label"));
        assertEquals("A generic pointer to related material.", json.getString("description"));
        assertEquals("internal", json.getString("kind"));
        assertTrue(json.getBoolean("weak"));
        assertFalse(json.getBoolean("backlinkOnly"));
        assertTrue(json.getBoolean("displayed"));
        assertEquals("RECURSIVE_DELETE", json.getString("onDelete"));
        assertEquals("/LinkTypes/referencedBy", json.getString("backlink"));
        assertEquals(1, json.getJsonArray("requiredSourceTypes").size());
        assertEquals(2, json.getJsonArray("requiredDestinationTypes").size());
        assertEquals("/LinkTypes/references", json.getString("path"));
    }

    @Test
    void serializesTheFullExternalDefinitionAsJson()
    {
        final Resource resource = this.context.create().resource("/LinkTypes/ehrChart", Map.of(
            SLING_RESOURCE_TYPE, EXTERNAL,
            "valuePattern", "[0-9]+",
            "urlTemplate", "https://ehr.example.org/chart/{value}"));

        final JsonObject json = resource.adaptTo(LinkDefinition.class).toDocumentationJson();

        assertEquals("external", json.getString("kind"));
        assertEquals("[0-9]+", json.getString("valuePattern"));
        assertEquals("https://ehr.example.org/chart/{value}", json.getString("urlTemplate"));
        // Reference-only settings have no place on an external type, so they are absent rather than defaulted
        assertFalse(json.containsKey("weak"));
        assertFalse(json.containsKey("onDelete"));
        assertFalse(json.containsKey("backlinkOnly"));
    }

    @Test
    void jsonLeavesUnsetOptionalFieldsOut()
    {
        final JsonObject bareExternal = this.context.create()
            .resource("/LinkTypes/bareExternal", SLING_RESOURCE_TYPE, EXTERNAL)
            .adaptTo(LinkDefinition.class).toDocumentationJson();

        assertFalse(bareExternal.containsKey("description"));
        assertFalse(bareExternal.containsKey("requiredSourceTypes"));
        assertFalse(bareExternal.containsKey("category"));
        assertFalse(bareExternal.containsKey("valuePattern"));
        assertFalse(bareExternal.containsKey("urlTemplate"));

        final JsonObject bare = this.context.create().resource("/LinkTypes/bare",
            SLING_RESOURCE_TYPE, INTERNAL)
            .adaptTo(LinkDefinition.class).toDocumentationJson();
        assertFalse(bare.containsKey("backlink"));
        assertFalse(bare.containsKey("requiredDestinationTypes"));
    }
}

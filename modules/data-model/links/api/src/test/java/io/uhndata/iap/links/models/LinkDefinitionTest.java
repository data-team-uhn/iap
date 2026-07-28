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

import java.util.Map;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LinkDefinition}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class LinkDefinitionTest
{
    private static final String SLING_RESOURCE_TYPE = "sling:resourceType";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, LinkDefinition.class);
    }

    @Test
    void exposesTheConfiguredSettings()
    {
        final Resource resource = this.context.create().resource("/LinkTypes/references", Map.of(
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE,
            "label", "References",
            "weak", true,
            "requiredSourceTypes", new String[]{ "sub:Submission" },
            "requiredDestinationTypes", new String[]{ "sub:Submission", "sch:Schema" },
            "resourceLabelTemplate", "{typeLabel}: {name}",
            "onDelete", "RECURSIVE_DELETE"));
        final LinkDefinition definition = resource.adaptTo(LinkDefinition.class);

        assertEquals("References", definition.getLabel());
        assertTrue(definition.isDisplayed());
        assertFalse(definition.isExternal());
        assertTrue(definition.isWeak());
        assertArrayEquals(new String[]{ "sub:Submission" }, definition.getRequiredSourceTypes());
        assertEquals(2, definition.getRequiredDestinationTypes().length);
        assertEquals("{typeLabel}: {name}", definition.getResourceLabelTemplate());
        assertEquals(LinkDefinition.OnDelete.RECURSIVE_DELETE, definition.getOnDeletePolicy());
        assertFalse(definition.hasBacklink());
        assertNull(definition.getBacklink());
        assertFalse(definition.isBacklinkOnly());
    }

    @Test
    void appliesDefaults()
    {
        final Resource resource = this.context.create().resource("/LinkTypes/bare",
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE);
        final LinkDefinition definition = resource.adaptTo(LinkDefinition.class);

        // With no explicit label, the node name identifies the type
        assertEquals("bare", definition.getLabel());
        // Types are displayed unless they explicitly opt out
        assertTrue(definition.isDisplayed());
        assertFalse(definition.isWeak());
        assertNull(definition.getRequiredSourceTypes());
        assertNull(definition.getResourceLabelTemplate());
        assertEquals(LinkDefinition.OnDelete.REMOVE_LINK, definition.getOnDeletePolicy());
        assertNull(definition.getValuePattern());
        assertNull(definition.getUrlTemplate());
    }

    @Test
    void fallsBackToRemoveLinkOnUnknownDeletePolicies()
    {
        final Resource resource = this.context.create().resource("/LinkTypes/odd", Map.of(
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE,
            "onDelete", "EXPLODE"));

        assertEquals(LinkDefinition.OnDelete.REMOVE_LINK,
            resource.adaptTo(LinkDefinition.class).getOnDeletePolicy());
    }

    @Test
    void resolvesTheBacklinkDefinition()
    {
        this.context.create().resource("/LinkTypes/referencedBy", Map.of(
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE,
            "backlinkOnly", true));
        final Resource resource = this.context.create().resource("/LinkTypes/references", Map.of(
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE,
            "backlink", "/LinkTypes/referencedBy"));
        final LinkDefinition definition = resource.adaptTo(LinkDefinition.class);

        assertTrue(definition.hasBacklink());
        final LinkDefinition backlink = definition.getBacklink();
        assertNotNull(backlink);
        assertEquals("/LinkTypes/referencedBy", backlink.getPath());
        assertTrue(backlink.isBacklinkOnly());
    }

    @Test
    void toleratesDanglingBacklinkPaths()
    {
        final Resource resource = this.context.create().resource("/LinkTypes/references", Map.of(
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE,
            "backlink", "/LinkTypes/missing"));
        final LinkDefinition definition = resource.adaptTo(LinkDefinition.class);

        assertTrue(definition.hasBacklink());
        assertNull(definition.getBacklink());
    }

    @Test
    void typesCanOptOutOfDisplay()
    {
        final Resource resource = this.context.create().resource("/LinkTypes/plumbing", Map.of(
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE,
            "displayed", false));

        assertFalse(resource.adaptTo(LinkDefinition.class).isDisplayed());
    }

    @Test
    void exposesExternalSettings()
    {
        final Resource resource = this.context.create().resource("/LinkTypes/ehrChart", Map.of(
            SLING_RESOURCE_TYPE, LinkDefinition.RESOURCE_TYPE,
            "external", true,
            "valuePattern", "[0-9]+",
            "urlTemplate", "https://ehr.example.org/chart/{value}"));
        final LinkDefinition definition = resource.adaptTo(LinkDefinition.class);

        assertTrue(definition.isExternal());
        assertEquals("[0-9]+", definition.getValuePattern());
        assertEquals("https://ehr.example.org/chart/{value}", definition.getUrlTemplate());
    }
}

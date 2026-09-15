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
package io.uhndata.iap.profiles.models;

import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ProfileFieldsHomepage}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ProfileFieldsHomepageTest
{
    private static final String RESOURCE_TYPE = "sling:resourceType";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, ProfileFieldsHomepage.class,
            ProfileFieldDefinition.class);
        this.context.create().resource(ProfileFieldsHomepage.PATH, Map.of(
            RESOURCE_TYPE, ProfileFieldsHomepage.RESOURCE_TYPE,
            "title", "Profile fields",
            "description", "What we record about a person."));
    }

    private void field(final String nodeName, final Map<String, Object> properties)
    {
        final Map<String, Object> all = new java.util.HashMap<>(properties);
        all.put(RESOURCE_TYPE, ProfileFieldDefinition.RESOURCE_TYPE);
        this.context.create().resource(ProfileFieldsHomepage.PATH + "/" + nodeName, all);
    }

    private ProfileFieldsHomepage homepage()
    {
        final Resource resource = this.context.resourceResolver().getResource(ProfileFieldsHomepage.PATH);
        return resource.adaptTo(ProfileFieldsHomepage.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        assertNotNull(homepage());
    }

    @Test
    void listsTheDefinitionsInDisplayOrder()
    {
        field("later", Map.of("order", 30L));
        field("earlier", Map.of("order", 10L));

        assertEquals(List.of("earlier", "later"),
            homepage().getDefinitions().stream().map(ProfileFieldDefinition::getName).toList());
    }

    @Test
    void ignoresChildrenThatAreNotDefinitions()
    {
        field("real", Map.of());
        this.context.create().resource(ProfileFieldsHomepage.PATH + "/stray", Map.of("jcr:primaryType",
            "nt:unstructured"));

        assertEquals(List.of("real"),
            homepage().getDefinitions().stream().map(ProfileFieldDefinition::getName).toList());
    }

    @Test
    void hasNoDefinitionsOnABarePlatform()
    {
        assertEquals(List.of(), homepage().getDefinitions());
    }

    @Test
    void findsOneDefinitionByName()
    {
        field("email", Map.of());
        field("language", Map.of("name", "preferred_language"));

        assertTrue(homepage().getDefinition("email").isPresent());
        assertEquals("preferred_language", homepage().getDefinition("preferred_language").get().getName());
        assertTrue(homepage().getDefinition("nothing-like-it").isEmpty());
    }

    @Test
    void documentsTheWholeCatalogue()
    {
        field("email", Map.of());
        final ProfileFieldsHomepage catalogue = homepage();

        assertEquals("Profile fields", catalogue.getDocumentationTitle());
        assertEquals("What we record about a person.", catalogue.getDocumentationIntro());
        assertEquals(1, catalogue.getDocumentedItems().size());
    }
}

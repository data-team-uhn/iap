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

import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.documentation.api.SelfDocumenting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TagsHomepage}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class TagsHomepageTest
{
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, TagDefinition.class, TagsHomepage.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/Tags",
            "sling:resourceType", "iap/TagsHomepage");
        assertNotNull(resource.adaptTo(TagsHomepage.class));
    }

    @Test
    void listsDefinitionsInDisplayOrder()
    {
        final Resource resource = this.context.create().resource("/Tags",
            "sling:resourceType", "iap/TagsHomepage");
        this.context.create().resource("/Tags/second", Map.of(
            "sling:resourceType", "iap/TagDefinition", "order", 20L));
        this.context.create().resource("/Tags/first", Map.of(
            "sling:resourceType", "iap/TagDefinition", "order", 10L));
        this.context.create().resource("/Tags/last",
            "sling:resourceType", "iap/TagDefinition");

        final List<TagDefinition> definitions = resource.adaptTo(TagsHomepage.class).getDefinitions();

        assertEquals(List.of("first", "second", "last"),
            definitions.stream().map(TagDefinition::getName).toList());
    }

    @Test
    void skipsChildrenThatAreNotDefinitions()
    {
        final Resource resource = this.context.create().resource("/Tags",
            "sling:resourceType", "iap/TagsHomepage");
        this.context.create().resource("/Tags/draft",
            "sling:resourceType", "iap/TagDefinition");
        // Extensibility children of other types are not tag definitions
        this.context.create().resource("/Tags/config",
            "sling:resourceType", "iap/Content");

        final List<TagDefinition> definitions = resource.adaptTo(TagsHomepage.class).getDefinitions();

        assertEquals(1, definitions.size());
        assertEquals("draft", definitions.get(0).getName());
    }

    @Test
    void emptyHomepageListsNoDefinitions()
    {
        final Resource resource = this.context.create().resource("/Tags",
            "sling:resourceType", "iap/TagsHomepage");
        assertTrue(resource.adaptTo(TagsHomepage.class).getDefinitions().isEmpty());
    }

    @Test
    void documentsTheVocabulary()
    {
        final Resource resource = this.context.create().resource("/Tags",
            "sling:resourceType", "iap/TagsHomepage");
        this.context.create().resource("/Tags/draft", Map.of(
            "sling:resourceType", "iap/TagDefinition",
            "label", "Draft",
            "description", "Work in progress",
            "category", new String[] { "lifecycle" }));
        this.context.create().resource("/Tags/loose",
            "sling:resourceType", "iap/TagDefinition");

        // The homepage is the SelfDocumenting adapter for the whole vocabulary
        final SelfDocumenting documentation = resource.adaptTo(SelfDocumenting.class);
        assertNotNull(documentation);
        assertEquals("Tags", documentation.getDocumentationTitle());
        assertEquals("All the tags defined in this instance, grouped by category.",
            documentation.getDocumentationIntro());
        assertEquals(2, documentation.getDocumentedItems().size());

        assertEquals("# Tags\n"
            + "\n"
            + "All the tags defined in this instance, grouped by category.\n"
            + "\n"
            + "## lifecycle\n"
            + "\n"
            + "### Draft (`draft`)\n"
            + "\n"
            + "Work in progress\n"
            + "\n"
            + "## uncategorized\n"
            + "\n"
            + "### loose (`loose`)\n", documentation.toMarkdown());
    }

    @Test
    void headingsCanBeReworded()
    {
        final Resource resource = this.context.create().resource("/Tags", Map.of(
            "sling:resourceType", "iap/TagsHomepage",
            "title", "Labels",
            "description", "The labels available here."));

        final SelfDocumenting documentation = resource.adaptTo(SelfDocumenting.class);
        assertEquals("Labels", documentation.getDocumentationTitle());
        assertEquals("The labels available here.", documentation.getDocumentationIntro());
    }

    @Test
    void blankHeadingsFallBackToTheDefaults()
    {
        final Resource resource = this.context.create().resource("/Tags", Map.of(
            "sling:resourceType", "iap/TagsHomepage",
            "title", "",
            "description", ""));

        final SelfDocumenting documentation = resource.adaptTo(SelfDocumenting.class);
        assertEquals("Tags", documentation.getDocumentationTitle());
        assertEquals("All the tags defined in this instance, grouped by category.",
            documentation.getDocumentationIntro());
    }
}

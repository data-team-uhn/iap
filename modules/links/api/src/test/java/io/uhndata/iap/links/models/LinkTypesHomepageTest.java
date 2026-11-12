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

import io.uhndata.iap.autodoc.api.AutoDocumentable;
import io.uhndata.iap.content.models.Content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LinkTypesHomepage}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class LinkTypesHomepageTest
{
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, InternalLinkDefinition.class,
            ExternalLinkDefinition.class, LinkTypesHomepage.class);
        LinkTypesFixture.registerDefinitionTypes(this.context);
    }

    @Test
    void listsTheDefinedLinkTypes()
    {
        final Resource resource = this.context.create().resource("/LinkTypes",
            "sling:resourceType", LinkTypesHomepage.RESOURCE_TYPE);
        this.context.create().resource("/LinkTypes/references",
            "sling:resourceType", InternalLinkDefinition.RESOURCE_TYPE);
        this.context.create().resource("/LinkTypes/ehrChart",
            "sling:resourceType", InternalLinkDefinition.RESOURCE_TYPE);
        final LinkTypesHomepage homepage = resource.adaptTo(LinkTypesHomepage.class);

        assertNotNull(homepage);
        assertEquals(2, homepage.getDefinitions().size());
        assertEquals("references", homepage.getDefinitions().get(0).getLabel());
    }

    @Test
    void listsNothingWhenEmpty()
    {
        final Resource resource = this.context.create().resource("/LinkTypes",
            "sling:resourceType", LinkTypesHomepage.RESOURCE_TYPE);

        assertTrue(resource.adaptTo(LinkTypesHomepage.class).getDefinitions().isEmpty());
    }

    @Test
    void documentsTheLinkTypes()
    {
        // The mock repository does not autocreate properties, so the test sets the defaults the CND declares
        final Resource resource = this.context.create().resource("/LinkTypes", Map.of(
            "sling:resourceType", LinkTypesHomepage.RESOURCE_TYPE,
            "title", "Link types",
            "description", "All the link types defined in this instance."));
        this.context.create().resource("/LinkTypes/references", Map.of(
            "sling:resourceType", InternalLinkDefinition.RESOURCE_TYPE,
            "label", "References",
            "description", "A generic pointer to related material.",
            "backlink", "/LinkTypes/referencedBy"));
        this.context.create().resource("/LinkTypes/ehrChart", Map.of(
            "sling:resourceType", ExternalLinkDefinition.RESOURCE_TYPE));

        final AutoDocumentable documentation = resource.adaptTo(AutoDocumentable.class);
        assertNotNull(documentation);
        assertEquals("Link types", documentation.getDocumentationTitle());
        assertEquals("All the link types defined in this instance.", documentation.getDocumentationIntro());
        assertEquals(2, documentation.getDocumentedItems().size());

        // Link types have no categories, so the catalogue has no section headings
        assertEquals("# Link types\n"
            + "\n"
            + "All the link types defined in this instance.\n"
            + "\n"
            + "### References (`references`)\n"
            + "\n"
            + "A generic pointer to related material.\n"
            + "\n"
            + "- **Backlink**: a reverse `/LinkTypes/referencedBy` link is automatically added"
            + " on the linked content\n"
            + "\n"
            + "### ehrChart (`ehrChart`)\n"
            + "\n"
            + "- **External**: records a value pointing outside the repository\n", documentation.toMarkdown());
    }

    @Test
    void headingsCanBeReworded()
    {
        final Resource resource = this.context.create().resource("/LinkTypes", Map.of(
            "sling:resourceType", LinkTypesHomepage.RESOURCE_TYPE,
            "title", "Connections",
            "description", "How the things stored here relate to each other."));

        final AutoDocumentable documentation = resource.adaptTo(AutoDocumentable.class);
        assertEquals("Connections", documentation.getDocumentationTitle());
        assertEquals("How the things stored here relate to each other.", documentation.getDocumentationIntro());
    }
}

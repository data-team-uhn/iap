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
package io.uhndata.iap.categories.models;

import java.util.Calendar;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.schemas.models.SchemaVersion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Category}, including the properties it inherits from
 * {@link io.uhndata.iap.entities.models.Entity}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class CategoryTest
{
    private static final String SLING_RESOURCE_TYPE = "sling:resourceType";

    private static final String SCHEMA_VERSION_ID = "schema-version-uuid";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, Category.class, SchemaVersion.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/Categories/Prospective",
            SLING_RESOURCE_TYPE, Category.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(Category.class));
    }

    @Test
    void exposesCategoryProperties()
    {
        final Resource resource = this.context.create().resource("/Categories/Prospective", Map.of(
            SLING_RESOURCE_TYPE, Category.RESOURCE_TYPE,
            "label", "Prospective studies",
            "description", "Studies collecting new data or specimens."));
        final Category category = resource.adaptTo(Category.class);

        assertEquals("Prospective studies", category.getLabel());
        assertEquals("Studies collecting new data or specimens.", category.getDescription());
    }

    @Test
    void reportsTheRetirementItCarries()
    {
        final Resource resource = this.context.create().resource("/Categories/Paper",
            SLING_RESOURCE_TYPE, Category.RESOURCE_TYPE);
        Retirement.retire(this.context, "/Categories/Paper");
        final Category category = resource.adaptTo(Category.class);

        assertTrue(category.isRetired());
        assertTrue(category.isRetiredHere());
    }

    @Test
    void reportsTheRetirementOfAnAncestor()
    {
        this.context.create().resource("/Categories/Legacy", SLING_RESOURCE_TYPE, Category.RESOURCE_TYPE);
        final Resource child = this.context.create().resource("/Categories/Legacy/LegacyData",
            SLING_RESOURCE_TYPE, Category.RESOURCE_TYPE);
        Retirement.retire(this.context, "/Categories/Legacy");
        final Category category = child.adaptTo(Category.class);

        // Retired, but not by its own doing: only the ancestor carrying the tag can take it off again
        assertTrue(category.isRetired());
        assertFalse(category.isRetiredHere());
    }

    @Test
    void isNotRetiredWithoutTheTagsService()
    {
        final Resource resource = this.context.create().resource("/Categories/Prospective",
            SLING_RESOURCE_TYPE, Category.RESOURCE_TYPE);
        final Category category = resource.adaptTo(Category.class);

        // Nothing registers the Taggable view here, which is what a repository without the tags bundle looks like
        assertFalse(category.isRetired());
        assertFalse(category.isRetiredHere());
    }

    @Test
    void toleratesMissingOptionalProperties()
    {
        final Resource resource = this.context.create().resource("/Categories/bare",
            SLING_RESOURCE_TYPE, Category.RESOURCE_TYPE);
        final Category category = resource.adaptTo(Category.class);

        assertNotNull(category);
        assertNull(category.getLabel());
        assertNull(category.getDescription());
        assertNull(category.getSchemaVersion());
    }

    @Test
    void resolvesSchemaVersionReference()
        throws RepositoryException
    {
        this.context.create().resource("/Schemas/schema/1.0",
            SLING_RESOURCE_TYPE, SchemaVersion.RESOURCE_TYPE, "version", "1.0");
        final Node targetNode = Mockito.mock(Node.class);
        Mockito.when(targetNode.getPath()).thenReturn("/Schemas/schema/1.0");
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getNodeByIdentifier(SCHEMA_VERSION_ID)).thenReturn(targetNode);
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);

        final Resource resource = this.context.create().resource("/Categories/Prospective", Map.of(
            SLING_RESOURCE_TYPE, Category.RESOURCE_TYPE,
            "label", "Prospective studies",
            "schemaVersion", SCHEMA_VERSION_ID));
        final Category category = resource.adaptTo(Category.class);

        assertEquals("1.0", category.getSchemaVersion().getVersion());
    }

    @Test
    void listsSubcategoriesInOrderSkippingOtherChildren()
    {
        final Resource resource = this.context.create().resource("/Categories/Prospective",
            SLING_RESOURCE_TYPE, Category.RESOURCE_TYPE);
        this.context.create().resource("/Categories/Prospective/Observational",
            SLING_RESOURCE_TYPE, Category.RESOURCE_TYPE, "label", "Prospective Observational Studies");
        this.context.create().resource("/Categories/Prospective/notes",
            SLING_RESOURCE_TYPE, "data/Content");
        this.context.create().resource("/Categories/Prospective/Interventional",
            SLING_RESOURCE_TYPE, Category.RESOURCE_TYPE, "label", "Prospective Interventional Studies");
        final Category category = resource.adaptTo(Category.class);

        final List<Category> subcategories = category.getSubcategories();
        assertEquals(2, subcategories.size());
        assertEquals("Observational", subcategories.get(0).getName());
        assertEquals("Interventional", subcategories.get(1).getName());
        assertFalse(category.isLeaf());
    }

    @Test
    void categoryWithoutSubcategoriesIsALeaf()
    {
        final Resource resource = this.context.create().resource("/Categories/Retrospective/RetrospectiveData",
            SLING_RESOURCE_TYPE, Category.RESOURCE_TYPE);
        final Category category = resource.adaptTo(Category.class);

        assertTrue(category.getSubcategories().isEmpty());
        assertTrue(category.isLeaf());
    }

    @Test
    void inheritsEntityAndContentProperties()
    {
        final Calendar created = Calendar.getInstance();
        created.set(2026, Calendar.JULY, 27, 12, 0, 0);
        final Resource resource = this.context.create().resource("/Categories/Prospective", Map.of(
            SLING_RESOURCE_TYPE, Category.RESOURCE_TYPE,
            "jcr:uuid", "9c8b7a65-4d3e-2f10-b1a2-0123456789ab",
            "jcr:created", created,
            "jcr:createdBy", "admin"));
        final Category category = resource.adaptTo(Category.class);

        assertEquals("/Categories/Prospective", category.getPath());
        assertEquals("Prospective", category.getName());
        assertEquals(Category.RESOURCE_TYPE, category.getType());
        assertEquals("9c8b7a65-4d3e-2f10-b1a2-0123456789ab", category.getIdentifier());
        assertEquals(created, category.getCreated());
        assertEquals("admin", category.getCreatedBy());
    }
}

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
package io.uhndata.iap.schemas.models;

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.models.EntityHomepage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SchemasHomepage}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SchemasHomepageTest
{
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityHomepage.class, Schema.class,
            SchemasHomepage.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/Schemas",
            "sling:resourceType", SchemasHomepage.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(SchemasHomepage.class));
    }

    @Test
    void listsSchemas()
    {
        final Resource resource = this.context.create().resource("/Schemas",
            "sling:resourceType", SchemasHomepage.RESOURCE_TYPE);
        this.context.create().resource("/Schemas/first", "sling:resourceType", Schema.RESOURCE_TYPE);
        this.context.create().resource("/Schemas/second", "sling:resourceType", Schema.RESOURCE_TYPE);
        final SchemasHomepage homepage = resource.adaptTo(SchemasHomepage.class);

        final List<Schema> schemas = homepage.getSchemas();

        assertEquals(2, schemas.size());
        assertEquals("first", schemas.get(0).getName());
        assertEquals("second", schemas.get(1).getName());
    }

    @Test
    void listsNoSchemasWhenNoneExist()
    {
        final Resource resource = this.context.create().resource("/Schemas",
            "sling:resourceType", SchemasHomepage.RESOURCE_TYPE);
        final SchemasHomepage homepage = resource.adaptTo(SchemasHomepage.class);

        assertTrue(homepage.getSchemas().isEmpty());
    }
}

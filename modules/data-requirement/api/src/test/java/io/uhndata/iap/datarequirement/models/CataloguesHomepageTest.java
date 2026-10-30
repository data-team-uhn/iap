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
package io.uhndata.iap.datarequirement.models;

import java.util.List;
import java.util.Map;

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
 * Unit tests for {@link CataloguesHomepage}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class CataloguesHomepageTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String PATH = "/Catalogues";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityHomepage.class,
            CataloguesHomepage.class, Catalogue.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        assertNotNull(this.context.create().resource(PATH, TYPE, CataloguesHomepage.RESOURCE_TYPE)
            .adaptTo(CataloguesHomepage.class));
    }

    @Test
    void listsTheCataloguesUnderIt()
    {
        this.context.create().resource(PATH, TYPE, CataloguesHomepage.RESOURCE_TYPE);
        this.context.create().resource(PATH + "/clinical", Map.of(
            TYPE, Catalogue.RESOURCE_TYPE, "title", "Clinical data"));
        this.context.create().resource(PATH + "/administrative", Map.of(
            TYPE, Catalogue.RESOURCE_TYPE, "title", "Administrative data"));
        final CataloguesHomepage homepage = this.context.resourceResolver().getResource(PATH)
            .adaptTo(CataloguesHomepage.class);

        final List<Catalogue> catalogues = homepage.getCatalogues();

        assertEquals(2, catalogues.size());
        assertEquals("Clinical data", catalogues.get(0).getTitle());
        assertEquals("Administrative data", catalogues.get(1).getTitle());
    }

    @Test
    void listsNoCataloguesBeforeAnyArePublished()
    {
        final CataloguesHomepage homepage = this.context.create()
            .resource(PATH, TYPE, CataloguesHomepage.RESOURCE_TYPE).adaptTo(CataloguesHomepage.class);

        assertTrue(homepage.getCatalogues().isEmpty());
    }
}

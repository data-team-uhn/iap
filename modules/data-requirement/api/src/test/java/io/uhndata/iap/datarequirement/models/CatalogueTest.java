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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Catalogue}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class CatalogueTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String PATH = "/Catalogues/clinical";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, Catalogue.class, CatalogueVersion.class);
    }

    @Test
    void exposesWhatItSaysAboutItself()
    {
        final Catalogue catalogue = this.context.create().resource(PATH, Map.of(
            TYPE, Catalogue.RESOURCE_TYPE,
            "title", "Clinical data",
            "description", "Everything the clinic records",
            "active", true)).adaptTo(Catalogue.class);

        assertEquals("Clinical data", catalogue.getTitle());
        assertEquals("Everything the clinic records", catalogue.getDescription());
        assertTrue(catalogue.isActive());
    }

    @Test
    void toleratesMissingOptionalProperties()
    {
        final Catalogue catalogue = this.context.create().resource(PATH, TYPE, Catalogue.RESOURCE_TYPE)
            .adaptTo(Catalogue.class);

        assertNotNull(catalogue);
        assertNull(catalogue.getTitle());
        assertNull(catalogue.getDescription());
        assertFalse(catalogue.isActive());
        assertTrue(catalogue.getVersions().isEmpty());
    }

    @Test
    void listsItsVersions()
    {
        this.context.create().resource(PATH, TYPE, Catalogue.RESOURCE_TYPE);
        this.context.create().resource(PATH + "/v1", Map.of(
            TYPE, CatalogueVersion.RESOURCE_TYPE, "version", "2026-02"));
        this.context.create().resource(PATH + "/v2", Map.of(
            TYPE, CatalogueVersion.RESOURCE_TYPE, "version", "2026-08"));
        final Catalogue catalogue = this.context.resourceResolver().getResource(PATH).adaptTo(Catalogue.class);

        final List<CatalogueVersion> versions = catalogue.getVersions();

        assertEquals(2, versions.size());
        assertEquals("2026-02", versions.get(0).getVersion());
        assertEquals("2026-08", versions.get(1).getVersion());
    }

    // What a requirement resolves through: it names the catalogue, and this decides what a new selection sees
    @Test
    void findsTheVersionANewSelectionWouldUse()
    {
        this.context.create().resource(PATH, TYPE, Catalogue.RESOURCE_TYPE);
        this.context.create().resource(PATH + "/v1", Map.of(
            TYPE, CatalogueVersion.RESOURCE_TYPE, "version", "2026-02", "active", false));
        this.context.create().resource(PATH + "/v2", Map.of(
            TYPE, CatalogueVersion.RESOURCE_TYPE, "version", "2026-08", "active", true));
        final Catalogue catalogue = this.context.resourceResolver().getResource(PATH).adaptTo(Catalogue.class);

        final CatalogueVersion active = catalogue.getActiveVersion();

        assertNotNull(active);
        assertEquals("2026-08", active.getVersion());
    }

    @Test
    void hasNoActiveVersionWhileNothingHasBeenPublished()
    {
        this.context.create().resource(PATH, TYPE, Catalogue.RESOURCE_TYPE);
        this.context.create().resource(PATH + "/v1", Map.of(
            TYPE, CatalogueVersion.RESOURCE_TYPE, "version", "2026-02", "active", false));
        final Catalogue catalogue = this.context.resourceResolver().getResource(PATH).adaptTo(Catalogue.class);

        assertNull(catalogue.getActiveVersion());
    }
}

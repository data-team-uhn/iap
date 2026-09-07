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
import io.uhndata.iap.entities.models.EntityPart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CatalogueVersion}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class CatalogueVersionTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String CATALOGUE_PATH = "/Catalogues/clinical";

    private static final String PATH = CATALOGUE_PATH + "/v1";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Catalogue.class,
            CatalogueVersion.class, Database.class, Collection.class, Field.class);
        this.context.create().resource(CATALOGUE_PATH, Map.of(
            TYPE, Catalogue.RESOURCE_TYPE, "title", "Clinical data"));
    }

    /** A version holding one field, {@code records/Patient/birthDate}. */
    private CatalogueVersion populated()
    {
        this.context.create().resource(PATH, Map.of(
            TYPE, CatalogueVersion.RESOURCE_TYPE, "version", "2026-08"));
        this.context.create().resource(PATH + "/records", Map.of(
            TYPE, Database.RESOURCE_TYPE, "identifier", "records"));
        this.context.create().resource(PATH + "/records/patient", Map.of(
            TYPE, Collection.RESOURCE_TYPE, "identifier", "Patient"));
        this.context.create().resource(PATH + "/records/patient/birthDate", Map.of(
            TYPE, Field.RESOURCE_TYPE, "identifier", "birthDate"));
        return this.context.resourceResolver().getResource(PATH).adaptTo(CatalogueVersion.class);
    }

    @Test
    void exposesWhatTheCatalogueSaysAboutAVersion()
    {
        final CatalogueVersion version = this.context.create().resource(PATH, Map.of(
            TYPE, CatalogueVersion.RESOURCE_TYPE,
            "version", "2026-08",
            "description", "The August re-export",
            "active", true)).adaptTo(CatalogueVersion.class);

        assertEquals("2026-08", version.getVersion());
        assertEquals("The August re-export", version.getDescription());
        assertTrue(version.isActive());
    }

    @Test
    void toleratesMissingOptionalProperties()
    {
        final CatalogueVersion version = this.context.create().resource(PATH, Map.of(
            TYPE, CatalogueVersion.RESOURCE_TYPE)).adaptTo(CatalogueVersion.class);

        assertNotNull(version);
        assertNull(version.getVersion());
        assertNull(version.getDescription());
        assertFalse(version.isActive());
        assertTrue(version.getDatabases().isEmpty());
        assertTrue(version.getFields().isEmpty());
    }

    @Test
    void listsItsDatabases()
    {
        final List<Database> databases = populated().getDatabases();

        assertEquals(1, databases.size());
        assertEquals("records", databases.get(0).getIdentifier());
    }

    @Test
    void flattensEveryFieldOutOfTheTree()
    {
        final List<Field> fields = populated().getFields();

        assertEquals(1, fields.size());
        assertEquals("birthDate", fields.get(0).getIdentifier());
    }

    // How a stored selection is read back: it holds keys, and this is what turns one into a field
    @Test
    void findsAFieldByTheKeyASelectionRecordsItAs()
    {
        final Field field = populated().getField("records/Patient/birthDate");

        assertNotNull(field);
        assertEquals("birthDate", field.getIdentifier());
    }

    // Not an error: a key this version does not offer is worth telling a reader about, not repairing
    @Test
    void findsNoFieldForAKeyThisVersionDoesNotOffer()
    {
        assertNull(populated().getField("records/Patient/gender"));
    }

    @Test
    void findsTheCatalogueItBelongsTo()
    {
        final Catalogue catalogue = populated().getCatalogue();

        assertNotNull(catalogue);
        assertEquals("Clinical data", catalogue.getTitle());
    }

    @Test
    void hasNoCatalogueWhenStoredOutsideOne()
    {
        final CatalogueVersion loose = this.context.create().resource("/loose", Map.of(
            TYPE, CatalogueVersion.RESOURCE_TYPE, "version", "1")).adaptTo(CatalogueVersion.class);

        assertNull(loose.getCatalogue());
    }
}

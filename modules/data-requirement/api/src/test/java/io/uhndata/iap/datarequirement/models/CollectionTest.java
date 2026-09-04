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

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityPart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Collection}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class CollectionTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String DATABASE_PATH = "/Catalogues/clinical/v1/records";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Database.class, Collection.class,
            Field.class);
        this.context.create().resource(DATABASE_PATH, Map.of(
            TYPE, Database.RESOURCE_TYPE, "identifier", "records"));
    }

    @Test
    void exposesWhatTheCatalogueSaysAboutACollection()
    {
        final Resource resource = this.context.create().resource(DATABASE_PATH + "/patient", Map.of(
            TYPE, Collection.RESOURCE_TYPE, "identifier", "Patient", "label", "Patients"));
        final Collection collection = resource.adaptTo(Collection.class);

        assertEquals("Patient", collection.getIdentifier());
        assertEquals("Patients", collection.getLabel());
    }

    @Test
    void fallsBackOnTheIdentifierWhenNothingCuratedALabel()
    {
        final Collection collection = this.context.create().resource(DATABASE_PATH + "/patient", Map.of(
            TYPE, Collection.RESOURCE_TYPE, "identifier", "Patient")).adaptTo(Collection.class);

        assertEquals("Patient", collection.getLabel());
    }

    @Test
    void fallsBackOnTheIdentifierWhenTheLabelIsBlank()
    {
        final Collection collection = this.context.create().resource(DATABASE_PATH + "/patient", Map.of(
            TYPE, Collection.RESOURCE_TYPE, "identifier", "Patient", "label", "")).adaptTo(Collection.class);

        assertEquals("Patient", collection.getLabel());
    }

    @Test
    void listsItsFieldsInTheOrderTheCatalogueGivesThem()
    {
        this.context.create().resource(DATABASE_PATH + "/patient", Map.of(
            TYPE, Collection.RESOURCE_TYPE, "identifier", "Patient"));
        this.context.create().resource(DATABASE_PATH + "/patient/birthDate", Map.of(
            TYPE, Field.RESOURCE_TYPE, "identifier", "birthDate"));
        this.context.create().resource(DATABASE_PATH + "/patient/gender", Map.of(
            TYPE, Field.RESOURCE_TYPE, "identifier", "gender"));
        final Collection collection = this.context.resourceResolver().getResource(DATABASE_PATH + "/patient")
            .adaptTo(Collection.class);

        final List<Field> fields = collection.getFields();

        assertEquals(2, fields.size());
        assertEquals("birthDate", fields.get(0).getIdentifier());
        assertEquals("gender", fields.get(1).getIdentifier());
    }

    @Test
    void listsNoFieldsWhenItHoldsNone()
    {
        final Collection collection = this.context.create().resource(DATABASE_PATH + "/empty", Map.of(
            TYPE, Collection.RESOURCE_TYPE, "identifier", "Empty")).adaptTo(Collection.class);

        assertTrue(collection.getFields().isEmpty());
    }

    @Test
    void findsTheDatabaseItBelongsTo()
    {
        final Collection collection = this.context.create().resource(DATABASE_PATH + "/patient", Map.of(
            TYPE, Collection.RESOURCE_TYPE, "identifier", "Patient")).adaptTo(Collection.class);

        final Database database = collection.getDatabase();

        assertNotNull(database);
        assertEquals("records", database.getIdentifier());
    }

    @Test
    void hasNoDatabaseWhenStoredOutsideOne()
    {
        final Collection loose = this.context.create().resource("/loose", Map.of(
            TYPE, Collection.RESOURCE_TYPE, "identifier", "Patient")).adaptTo(Collection.class);

        assertNull(loose.getDatabase());
    }
}

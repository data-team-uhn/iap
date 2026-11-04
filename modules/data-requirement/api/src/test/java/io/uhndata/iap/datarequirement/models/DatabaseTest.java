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
import io.uhndata.iap.entities.models.EntityPart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Database}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class DatabaseTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String PATH = "/Catalogues/clinical/v1/records";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Database.class, Collection.class);
    }

    @Test
    void exposesWhatTheCatalogueSaysAboutASourceSystem()
    {
        final Database database = this.context.create().resource(PATH, Map.of(
            TYPE, Database.RESOURCE_TYPE,
            "identifier", "records",
            "label", "Hospital records",
            "description", "Everything the clinic recorded")).adaptTo(Database.class);

        assertEquals("records", database.getIdentifier());
        assertEquals("Hospital records", database.getLabel());
        assertEquals("Everything the clinic recorded", database.getDescription());
    }

    @Test
    void fallsBackOnTheIdentifierWhenNothingCuratedALabel()
    {
        final Database database = this.context.create().resource(PATH, Map.of(
            TYPE, Database.RESOURCE_TYPE, "identifier", "records")).adaptTo(Database.class);

        assertEquals("records", database.getLabel());
        assertNull(database.getDescription());
    }

    @Test
    void fallsBackOnTheIdentifierWhenTheLabelIsBlank()
    {
        final Database database = this.context.create().resource(PATH, Map.of(
            TYPE, Database.RESOURCE_TYPE, "identifier", "records", "label", "")).adaptTo(Database.class);

        assertEquals("records", database.getLabel());
    }

    @Test
    void listsItsCollectionsInTheOrderTheCatalogueGivesThem()
    {
        this.context.create().resource(PATH, Map.of(TYPE, Database.RESOURCE_TYPE, "identifier", "records"));
        this.context.create().resource(PATH + "/patient", Map.of(
            TYPE, Collection.RESOURCE_TYPE, "identifier", "Patient"));
        this.context.create().resource(PATH + "/encounter", Map.of(
            TYPE, Collection.RESOURCE_TYPE, "identifier", "Encounter"));
        final Database database = this.context.resourceResolver().getResource(PATH).adaptTo(Database.class);

        final List<Collection> collections = database.getCollections();

        assertEquals(2, collections.size());
        assertEquals("Patient", collections.get(0).getIdentifier());
        assertEquals("Encounter", collections.get(1).getIdentifier());
    }

    @Test
    void listsNoCollectionsWhenItHoldsNone()
    {
        final Database database = this.context.create().resource(PATH, Map.of(
            TYPE, Database.RESOURCE_TYPE, "identifier", "records")).adaptTo(Database.class);

        assertTrue(database.getCollections().isEmpty());
    }
}

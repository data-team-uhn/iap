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

import java.util.Map;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityPart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Field}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class FieldTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String VERSION_PATH = "/Catalogues/clinical/v1";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Database.class, Collection.class,
            Field.class);
        this.context.create().resource(VERSION_PATH, TYPE, CatalogueVersion.RESOURCE_TYPE);
        this.context.create().resource(VERSION_PATH + "/records", Map.of(
            TYPE, Database.RESOURCE_TYPE, "identifier", "records"));
        this.context.create().resource(VERSION_PATH + "/records/patient", Map.of(
            TYPE, Collection.RESOURCE_TYPE, "identifier", "Patient"));
    }

    private Field field(final Map<String, Object> properties)
    {
        return this.context.create().resource(VERSION_PATH + "/records/patient/f", properties)
            .adaptTo(Field.class);
    }

    @Test
    void exposesWhatTheCatalogueSaysAboutAField()
    {
        final Field field = field(Map.of(
            TYPE, Field.RESOURCE_TYPE,
            "identifier", "birthDate",
            "label", "Date of birth",
            "description", "The day the patient was born",
            "cardinality", "0..1",
            "dataType", "date",
            "phi", true,
            "example", "1970-01-01"));

        assertEquals("birthDate", field.getIdentifier());
        assertEquals("Date of birth", field.getLabel());
        assertEquals("The day the patient was born", field.getDescription());
        assertEquals("0..1", field.getCardinality());
        assertEquals("date", field.getDataType());
        assertTrue(field.getPhi());
        assertEquals("1970-01-01", field.getExample());
    }

    @Test
    void fallsBackOnTheIdentifierWhenNothingCuratedALabel()
    {
        assertEquals("birthDate", field(Map.of(TYPE, Field.RESOURCE_TYPE, "identifier", "birthDate")).getLabel());
    }

    @Test
    void fallsBackOnTheIdentifierWhenTheLabelIsBlank()
    {
        assertEquals("birthDate", field(Map.of(
            TYPE, Field.RESOURCE_TYPE, "identifier", "birthDate", "label", "")).getLabel());
    }

    // Three answers, not two: a catalogue nobody has assessed must not read as one assessed and found clear
    @Test
    void saysNothingAboutIdentifiabilityWhenNobodyAssessedTheField()
    {
        final Field field = field(Map.of(TYPE, Field.RESOURCE_TYPE, "identifier", "code"));

        assertNull(field.getPhi());
        assertNull(field.getDescription());
        assertNull(field.getCardinality());
        assertNull(field.getDataType());
        assertNull(field.getExample());
    }

    @Test
    void reportsAFieldAssessedAndFoundClear()
    {
        assertFalse(field(Map.of(TYPE, Field.RESOURCE_TYPE, "identifier", "code", "phi", false)).getPhi());
    }

    @Test
    void findsTheCollectionItBelongsTo()
    {
        final Collection collection = field(Map.of(TYPE, Field.RESOURCE_TYPE, "identifier", "x")).getCollection();

        assertNotNull(collection);
        assertEquals("Patient", collection.getIdentifier());
    }

    // The key is what every selection ever made against this catalogue records, so its shape is a contract
    @Test
    void buildsItsKeyFromTheThreeSourceIdentifiers()
    {
        assertEquals("records/Patient/birthDate",
            field(Map.of(TYPE, Field.RESOURCE_TYPE, "identifier", "birthDate")).getKey());
    }

    @Test
    void hasNoKeyWhenStoredOutsideACollection()
    {
        final Field loose = this.context.create().resource("/loose", Map.of(
            TYPE, Field.RESOURCE_TYPE, "identifier", "birthDate")).adaptTo(Field.class);

        assertNull(loose.getCollection());
        assertNull(loose.getKey());
    }

    @Test
    void hasNoKeyWhenItsCollectionIsStoredOutsideADatabase()
    {
        this.context.create().resource("/stray", Map.of(TYPE, Collection.RESOURCE_TYPE, "identifier", "Patient"));
        final Field field = this.context.create().resource("/stray/f", Map.of(
            TYPE, Field.RESOURCE_TYPE, "identifier", "birthDate")).adaptTo(Field.class);

        assertNotNull(field.getCollection());
        assertNull(field.getKey());
    }
}

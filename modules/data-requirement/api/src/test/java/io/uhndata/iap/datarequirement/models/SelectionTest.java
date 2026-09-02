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

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.Requirement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Selection}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SelectionTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String SUPER_TYPE = "sling:resourceSuperType";

    private static final String VERSION_ID = "6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345";

    private static final String REQUIREMENT_ID = "11111111-2222-3333-4444-555555555555";

    private static final String CATALOGUE_PATH = "/Catalogues/clinical";

    private static final String V1 = CATALOGUE_PATH + "/v1";

    private static final String V2 = CATALOGUE_PATH + "/v2";

    private static final String REQUIREMENT_PATH = "/Schemas/study/v1/data";

    private static final String PATH = "/Submissions/ab/cd/study/selection";

    private static final String BIRTH_DATE = "records/Patient/birthDate";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Requirement.class,
            DataRequirement.class, Catalogue.class, CatalogueVersion.class, Database.class, Collection.class,
            Field.class, Selection.class);
        this.context.create().resource(CATALOGUE_PATH, Map.of(
            TYPE, Catalogue.RESOURCE_TYPE, "title", "Clinical data"));
        this.context.create().resource(REQUIREMENT_PATH, Map.of(
            TYPE, DataRequirement.RESOURCE_TYPE, SUPER_TYPE, Requirement.RESOURCE_TYPE, "label", "Which data?"));
    }

    /** A catalogue version at {@code path} holding {@code records/Patient/<field>} for each name given. */
    private void version(final String path, final String version, final String... fields)
    {
        this.context.create().resource(path, Map.of(TYPE, CatalogueVersion.RESOURCE_TYPE, "version", version));
        this.context.create().resource(path + "/records", Map.of(
            TYPE, Database.RESOURCE_TYPE, "identifier", "records"));
        this.context.create().resource(path + "/records/patient", Map.of(
            TYPE, Collection.RESOURCE_TYPE, "identifier", "Patient"));
        for (final String field : fields) {
            this.context.create().resource(path + "/records/patient/" + field, Map.of(
                TYPE, Field.RESOURCE_TYPE, "identifier", field));
        }
    }

    /** Resolves both references the way a real repository would. */
    private void referenceable() throws RepositoryException
    {
        final Node versionNode = Mockito.mock(Node.class);
        Mockito.when(versionNode.getPath()).thenReturn(V1);
        final Node requirementNode = Mockito.mock(Node.class);
        Mockito.when(requirementNode.getPath()).thenReturn(REQUIREMENT_PATH);
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getNodeByIdentifier(VERSION_ID)).thenReturn(versionNode);
        Mockito.when(session.getNodeByIdentifier(REQUIREMENT_ID)).thenReturn(requirementNode);
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);
    }

    private Selection selection(final String... fields)
    {
        return this.context.create().resource(PATH, Map.of(
            TYPE, Selection.RESOURCE_TYPE,
            "fulfills", REQUIREMENT_ID,
            "catalogueVersion", VERSION_ID,
            "fields", fields)).adaptTo(Selection.class);
    }

    @Test
    void holdsTheKeysItWasGivenInOrder()
    {
        final Selection selection = selection(BIRTH_DATE, "records/Patient/gender");

        assertEquals(List.of(BIRTH_DATE, "records/Patient/gender"), selection.getFieldKeys());
    }

    @Test
    void meetsItsRequirementByHoldingSomething()
    {
        assertTrue(selection(BIRTH_DATE).isFulfilling());
    }

    // Clearing leaves the node where it is, so that the version it was bound to survives: an empty selection is a
    // real state, and it is the state of not having chosen yet
    @Test
    void doesNotMeetItsRequirementWhileItHoldsNothing()
    {
        assertFalse(selection().isFulfilling());
    }

    @Test
    void holdsNoKeysBeforeAnythingIsChosen()
    {
        final Selection selection = this.context.create().resource(PATH, Map.of(TYPE, Selection.RESOURCE_TYPE))
            .adaptTo(Selection.class);

        assertNotNull(selection);
        assertTrue(selection.getFieldKeys().isEmpty());
        assertNull(selection.getFulfills());
        assertNull(selection.getCatalogueVersion());
        // Without a version there is nothing to read the keys against, so there are no fields to report
        assertTrue(selection.getFields().isEmpty());
    }

    @Test
    void namesTheRequirementItAnswersAndTheVersionItWasMadeAgainst() throws RepositoryException
    {
        version(V1, "2026-02", "birthDate");
        referenceable();

        final Selection selection = selection(BIRTH_DATE);

        assertNotNull(selection.getFulfills());
        assertEquals("Which data?", selection.getFulfills().getLabel());
        assertNotNull(selection.getCatalogueVersion());
        assertEquals("2026-02", selection.getCatalogueVersion().getVersion());
    }

    @Test
    void resolvesItsKeysAgainstTheVersionItWasMadeAgainst() throws RepositoryException
    {
        version(V1, "2026-02", "birthDate");
        referenceable();

        final List<Field> fields = selection(BIRTH_DATE).getFields();

        assertEquals(1, fields.size());
        assertEquals("birthDate", fields.get(0).getIdentifier());
    }

    // Content is editable, and a catalogue somebody broke should not make a filed submission unreadable
    @Test
    void passesOverAKeyItsOwnVersionNoLongerOffers() throws RepositoryException
    {
        version(V1, "2026-02", "birthDate");
        referenceable();

        final List<Field> fields = selection(BIRTH_DATE, "records/Patient/gone").getFields();

        assertEquals(1, fields.size());
        assertEquals("birthDate", fields.get(0).getIdentifier());
    }

    // Information for a reviewer looking at an older submission, never a repair: what was chosen stays chosen
    @Test
    void saysWhichOfItsFieldsALaterVersionNoLongerOffers() throws RepositoryException
    {
        version(V1, "2026-02", "birthDate", "gender");
        version(V2, "2026-08", "birthDate");
        referenceable();
        final CatalogueVersion later = this.context.resourceResolver().getResource(V2)
            .adaptTo(CatalogueVersion.class);

        final Selection selection = selection(BIRTH_DATE, "records/Patient/gender");

        assertEquals(List.of("records/Patient/gender"), selection.getMissingFields(later));
    }

    @Test
    void saysNothingIsMissingWhenTheLaterVersionStillOffersEverything() throws RepositoryException
    {
        version(V1, "2026-02", "birthDate");
        version(V2, "2026-08", "birthDate", "gender");
        referenceable();
        final CatalogueVersion later = this.context.resourceResolver().getResource(V2)
            .adaptTo(CatalogueVersion.class);

        assertTrue(selection(BIRTH_DATE).getMissingFields(later).isEmpty());
    }
}

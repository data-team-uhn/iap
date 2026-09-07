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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link DataRequirement}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class DataRequirementTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String SUPER_TYPE = "sling:resourceSuperType";

    private static final String CATALOGUE_ID = "6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345";

    private static final String CATALOGUE_PATH = "/Catalogues/clinical";

    private static final String PATH = "/Schemas/study/v1/data";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Requirement.class,
            DataRequirement.class, Catalogue.class, CatalogueVersion.class);
    }

    /** Points the catalogue reference at {@link #CATALOGUE_PATH}, the way a real repository would resolve it. */
    private void referenceable() throws RepositoryException
    {
        final Node target = Mockito.mock(Node.class);
        Mockito.when(target.getPath()).thenReturn(CATALOGUE_PATH);
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getNodeByIdentifier(CATALOGUE_ID)).thenReturn(target);
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);
    }

    private DataRequirement requirement()
    {
        return this.context.create().resource(PATH, Map.of(
            TYPE, DataRequirement.RESOURCE_TYPE,
            SUPER_TYPE, Requirement.RESOURCE_TYPE,
            "label", "Which data do you need?",
            "catalogue", CATALOGUE_ID)).adaptTo(DataRequirement.class);
    }

    @Test
    void resolvesTheCatalogueItOffers() throws RepositoryException
    {
        this.context.create().resource(CATALOGUE_PATH, Map.of(
            TYPE, Catalogue.RESOURCE_TYPE, "title", "Clinical data"));
        referenceable();

        final Catalogue catalogue = requirement().getCatalogue();

        assertNotNull(catalogue);
        assertEquals("Clinical data", catalogue.getTitle());
    }

    @Test
    void offersNoCatalogueWhenTheReferenceCannotBeResolved()
    {
        final DataRequirement requirement = requirement();

        assertNull(requirement.getCatalogue());
        assertNull(requirement.getCurrentVersion());
    }

    // The requirement names the catalogue, so what a new selection sees is decided at the moment of asking
    @Test
    void reportsTheVersionASelectionMadeNowWouldUse() throws RepositoryException
    {
        this.context.create().resource(CATALOGUE_PATH, Map.of(
            TYPE, Catalogue.RESOURCE_TYPE, "title", "Clinical data"));
        this.context.create().resource(CATALOGUE_PATH + "/v1", Map.of(
            TYPE, CatalogueVersion.RESOURCE_TYPE, "version", "2026-02", "active", false));
        this.context.create().resource(CATALOGUE_PATH + "/v2", Map.of(
            TYPE, CatalogueVersion.RESOURCE_TYPE, "version", "2026-08", "active", true));
        referenceable();

        final CatalogueVersion current = requirement().getCurrentVersion();

        assertNotNull(current);
        assertEquals("2026-08", current.getVersion());
    }

    @Test
    void reportsNoCurrentVersionWhileTheCatalogueHasPublishedNone() throws RepositoryException
    {
        this.context.create().resource(CATALOGUE_PATH, Map.of(
            TYPE, Catalogue.RESOURCE_TYPE, "title", "Clinical data"));
        referenceable();

        assertNull(requirement().getCurrentVersion());
    }

    // It is one of the schema's requirements, and the form projection reaches every kind through that base
    @Test
    void readsAsTheRequirementItIs()
    {
        final Requirement requirement = this.context.create().resource(PATH, Map.of(
            TYPE, DataRequirement.RESOURCE_TYPE,
            SUPER_TYPE, Requirement.RESOURCE_TYPE,
            "label", "Which data do you need?")).adaptTo(Requirement.class);

        assertNotNull(requirement);
        assertEquals(DataRequirement.class, requirement.getClass());
        assertEquals("Which data do you need?", requirement.getLabel());
    }
}

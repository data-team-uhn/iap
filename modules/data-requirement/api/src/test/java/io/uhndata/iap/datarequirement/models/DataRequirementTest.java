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
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    private static final String V1_ID = "22222222-9d2b-4a7e-8c3f-abcdef012345";

    private static final String REQUIREMENT_ID = "11111111-2222-3333-4444-555555555555";

    private static final String CATALOGUE_PATH = "/Catalogues/clinical";

    private static final String SUBMISSION_PATH = "/Submissions/ab/cd/study";

    private static final String PATH = "/Schemas/study/v1/data";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Requirement.class,
            DataRequirement.class, Catalogue.class, CatalogueVersion.class, Selection.class);
    }

    /** Makes the references to the catalogue and to its first version resolve, as a real repository would. */
    private void resolveReferences() throws RepositoryException
    {
        final Node catalogueNode = Mockito.mock(Node.class);
        Mockito.when(catalogueNode.getPath()).thenReturn(CATALOGUE_PATH);
        final Node versionNode = Mockito.mock(Node.class);
        Mockito.when(versionNode.getPath()).thenReturn(CATALOGUE_PATH + "/v1");
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getNodeByIdentifier(CATALOGUE_ID)).thenReturn(catalogueNode);
        Mockito.when(session.getNodeByIdentifier(V1_ID)).thenReturn(versionNode);
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);
    }

    /** The catalogue the requirement's version belongs to, with that version. */
    private void createCatalogue() throws RepositoryException
    {
        this.context.create().resource(CATALOGUE_PATH, Map.of(
            TYPE, Catalogue.RESOURCE_TYPE, "title", "Clinical data"));
        this.context.create().resource(CATALOGUE_PATH + "/v1", Map.of(
            TYPE, CatalogueVersion.RESOURCE_TYPE, "version", "2026-02"));
        resolveReferences();
    }

    /** A data requirement whose {@code catalogue} reference holds the given identifier. */
    private DataRequirement requirementNaming(final String identifier)
    {
        return this.context.create().resource(PATH, Map.of(
            TYPE, DataRequirement.RESOURCE_TYPE,
            SUPER_TYPE, Requirement.RESOURCE_TYPE,
            "label", "Which data do you need?",
            "jcr:uuid", REQUIREMENT_ID,
            "catalogue", identifier)).adaptTo(DataRequirement.class);
    }

    private DataRequirement requirement()
    {
        return requirementNaming(V1_ID);
    }

    @Test
    void resolvesTheCatalogueVersionItOffers() throws RepositoryException
    {
        createCatalogue();

        final CatalogueVersion version = requirement().getCatalogueVersion();

        assertEquals("2026-02", version.getVersion());
    }

    @Test
    void refusesToReadACatalogueVersionItCannotReach()
    {
        final DataRequirement requirement = requirement();

        assertNull(requirement.findCatalogueVersion());
        assertThrows(NullPointerException.class, requirement::getCatalogueVersion);
    }

    @Test
    void refusesAReferenceToSomethingThatIsNotACatalogueVersion() throws RepositoryException
    {
        createCatalogue();
        final DataRequirement requirement = requirementNaming(CATALOGUE_ID);

        assertNull(requirement.findCatalogueVersion());
        assertThrows(NullPointerException.class, requirement::getCatalogueVersion);
    }

    @Test
    void findsTheSelectionAnsweringItAmongASubmissionsParts()
    {
        final DataRequirement requirement = requirement();
        final Content submission = this.context.create().resource(SUBMISSION_PATH, Map.of(
            TYPE, "sub/Submission")).adaptTo(Content.class);
        // An answer naming the same requirement, which must not be taken for a selection
        this.context.create().resource(SUBMISSION_PATH + "/answer", Map.of(
            TYPE, "sub/Answer", "fulfills", REQUIREMENT_ID));
        this.context.create().resource(SUBMISSION_PATH + "/elsewhere", Map.of(
            TYPE, Selection.RESOURCE_TYPE, "fulfills", "99999999-2222-3333-4444-555555555555"));
        this.context.create().resource(SUBMISSION_PATH + "/mine", Map.of(
            TYPE, Selection.RESOURCE_TYPE, "fulfills", REQUIREMENT_ID));

        final Selection found = requirement.findSelectionIn(submission);

        assertNotNull(found);
        assertEquals("mine", found.getName());
    }

    @Test
    void findsNoSelectionInASubmissionThatHasNone()
    {
        final DataRequirement requirement = requirement();
        final Content submission = this.context.create().resource(SUBMISSION_PATH, Map.of(
            TYPE, "sub/Submission")).adaptTo(Content.class);

        assertNull(requirement.findSelectionIn(submission));
    }

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

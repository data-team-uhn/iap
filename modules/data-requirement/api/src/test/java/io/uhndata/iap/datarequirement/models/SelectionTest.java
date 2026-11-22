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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    private static final String REQUIREMENT_ID = "11111111-2222-3333-4444-555555555555";

    private static final String REQUIREMENT_PATH = "/Schemas/study/v1/data";

    private static final String PATH = "/Submissions/ab/cd/study/selection";

    private static final String BIRTH_DATE = "records/Patient/birthDate";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Requirement.class,
            DataRequirement.class, Selection.class);
        this.context.create().resource(REQUIREMENT_PATH, Map.of(
            TYPE, DataRequirement.RESOURCE_TYPE, SUPER_TYPE, Requirement.RESOURCE_TYPE, "label", "Which data?",
            "jcr:uuid", REQUIREMENT_ID));
    }

    /** Makes the reference to the requirement resolve, as a real repository would. */
    private void resolveReferences() throws RepositoryException
    {
        final Node requirementNode = Mockito.mock(Node.class);
        Mockito.when(requirementNode.getPath()).thenReturn(REQUIREMENT_PATH);
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getNodeByIdentifier(REQUIREMENT_ID)).thenReturn(requirementNode);
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);
    }

    private Selection selection(final String... fields)
    {
        return this.context.create().resource(PATH, Map.of(
            TYPE, Selection.RESOURCE_TYPE,
            "fulfills", REQUIREMENT_ID,
            "fields", fields)).adaptTo(Selection.class);
    }

    @Test
    void holdsTheKeysItWasGivenInOrder()
    {
        final Selection selection = selection(BIRTH_DATE, "records/Patient/gender");

        assertEquals(List.of(BIRTH_DATE, "records/Patient/gender"), selection.getFieldKeys());
    }

    @Test
    void holdsNoKeysBeforeAnythingIsChosen()
    {
        final Selection selection = this.context.create().resource(PATH, Map.of(TYPE, Selection.RESOURCE_TYPE))
            .adaptTo(Selection.class);

        assertNotNull(selection);
        assertTrue(selection.getFieldKeys().isEmpty());
    }

    @Test
    void refusesToReadARequirementItCannotReach()
    {
        final Selection selection = selection(BIRTH_DATE);

        assertThrows(NullPointerException.class, selection::getFulfills);
    }

    @Test
    void namesTheRequirementItAnswers() throws RepositoryException
    {
        resolveReferences();

        final Selection selection = selection(BIRTH_DATE);

        assertEquals("Which data?", selection.getFulfills().getLabel());
    }

    @Test
    void knowsWhichRequirementItAnswers()
    {
        final Requirement answered = this.context.resourceResolver().getResource(REQUIREMENT_PATH)
            .adaptTo(Requirement.class);
        final Requirement other = this.context.create().resource("/Schemas/study/v1/other", Map.of(
            TYPE, DataRequirement.RESOURCE_TYPE, SUPER_TYPE, Requirement.RESOURCE_TYPE,
            "jcr:uuid", "99999999-2222-3333-4444-555555555555")).adaptTo(Requirement.class);

        final Selection selection = selection(BIRTH_DATE);

        assertTrue(selection.fulfills(answered));
        assertFalse(selection.fulfills(other));
    }
}

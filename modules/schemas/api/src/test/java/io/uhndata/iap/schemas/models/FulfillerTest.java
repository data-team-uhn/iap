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
package io.uhndata.iap.schemas.models;

import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityPart;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Fulfiller}, through the stand-in kind in these test sources.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class FulfillerTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String REQUIREMENT_ID = "6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345";

    private static final String OTHER_ID = "11111111-2222-3333-4444-555555555555";

    private static final String REQUIREMENT_PATH = "/Schemas/schema/v1/consent";

    private static final String OTHER_PATH = "/Schemas/schema/v1/reb";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp() throws RepositoryException
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, DocumentRequirement.class,
            ApprovalRequirement.class, TestFulfiller.class);
        this.context.create().resource(REQUIREMENT_PATH, Map.of(
            TYPE, DocumentRequirement.RESOURCE_TYPE, "sling:resourceSuperType", Requirement.RESOURCE_TYPE,
            "label", "Consent"));
        this.context.create().resource(OTHER_PATH, Map.of(
            TYPE, ApprovalRequirement.RESOURCE_TYPE, "sling:resourceSuperType", Requirement.RESOURCE_TYPE,
            "label", "Approval"));

        final Session session = Mockito.mock(Session.class);
        this.mockNode(session, REQUIREMENT_ID, REQUIREMENT_PATH);
        this.mockNode(session, OTHER_ID, OTHER_PATH);
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);
    }

    private void mockNode(final Session session, final String identifier, final String path)
        throws RepositoryException
    {
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.getPath()).thenReturn(path);
        Mockito.when(session.getNodeByIdentifier(identifier)).thenReturn(node);
    }

    private TestFulfiller filed(final String against)
    {
        final Resource resource = this.context.create().resource("/Submissions/one/part", Map.of(
            TYPE, TestFulfiller.RESOURCE_TYPE, "fulfills", against));
        return resource.adaptTo(TestFulfiller.class);
    }

    private Requirement requirement(final String path)
    {
        return this.context.resourceResolver().getResource(path).adaptTo(Requirement.class);
    }

    @Test
    void namesTheRequirementItWasFiledAgainst()
    {
        final TestFulfiller part = filed(REQUIREMENT_ID);

        assertNotNull(part.getFulfills());
        assertTrue(part.answers(requirement(REQUIREMENT_PATH)));
    }

    // Compared by path, so a requirement that was replaced does not collect what answered the one before it
    @Test
    void answersOnlyTheRequirementItNames()
    {
        assertFalse(filed(REQUIREMENT_ID).answers(requirement(OTHER_PATH)));
    }

    @Test
    void answersNothingWhenItNamesNothing()
    {
        final Resource loose = this.context.create().resource("/Submissions/one/loose",
            TYPE, TestFulfiller.RESOURCE_TYPE);
        final TestFulfiller part = loose.adaptTo(TestFulfiller.class);

        assertNull(part.getFulfills());
        assertFalse(part.answers(requirement(REQUIREMENT_PATH)));
    }

    // Filing something is usually meeting it, which is what a kind that says nothing else gets
    @Test
    void meetsWhatItNamesUnlessItSaysOtherwise()
    {
        assertTrue(filed(REQUIREMENT_ID).isFulfilling());
    }
}

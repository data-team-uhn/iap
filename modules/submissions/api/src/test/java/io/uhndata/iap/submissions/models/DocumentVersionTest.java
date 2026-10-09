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
package io.uhndata.iap.submissions.models;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
import io.uhndata.iap.schemas.models.SectionRequirement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link DocumentVersion}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class DocumentVersionTest
{
    private static final String DOCUMENT_PATH = "/Submissions/submission/consent";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, DocumentVersion.class, Document.class,
            File.class, Section.class, SectionRequirement.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource(DOCUMENT_PATH + "/v0",
            "sling:resourceType", DocumentVersion.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(DocumentVersion.class));
    }

    @Test
    void exposesTheOneFileItConsistsOf()
    {
        final Resource resource = this.context.create().resource(DOCUMENT_PATH + "/v0",
            "sling:resourceType", DocumentVersion.RESOURCE_TYPE);
        this.context.create().resource(DOCUMENT_PATH + "/v0/file", Map.of(
            "sling:resourceType", File.RESOURCE_TYPE, "parseStatus", "completed"));
        final DocumentVersion version = resource.adaptTo(DocumentVersion.class);

        assertEquals("completed", version.getFile().getParseStatus());
    }

    @Test
    void hasNoFileBeforeTheUploadLands()
    {
        final Resource resource = this.context.create().resource(DOCUMENT_PATH + "/v0",
            "sling:resourceType", DocumentVersion.RESOURCE_TYPE);
        final DocumentVersion version = resource.adaptTo(DocumentVersion.class);

        assertNull(version.getFile());
    }

    @Test
    void numbersItselfByItsPositionAmongTheDocumentsVersions()
    {
        this.context.create().resource(DOCUMENT_PATH, "sling:resourceType", Document.RESOURCE_TYPE);
        final Resource first = this.context.create().resource(DOCUMENT_PATH + "/v0",
            "sling:resourceType", DocumentVersion.RESOURCE_TYPE);
        final Resource second = this.context.create().resource(DOCUMENT_PATH + "/v1",
            "sling:resourceType", DocumentVersion.RESOURCE_TYPE);
        final Resource third = this.context.create().resource(DOCUMENT_PATH + "/v2",
            "sling:resourceType", DocumentVersion.RESOURCE_TYPE);

        assertEquals(1, first.adaptTo(DocumentVersion.class).getNumber());
        assertEquals(2, second.adaptTo(DocumentVersion.class).getNumber());
        assertEquals(3, third.adaptTo(DocumentVersion.class).getNumber());
    }

    @Test
    void hasNoNumberOutsideADocument()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/loose",
            "sling:resourceType", DocumentVersion.RESOURCE_TYPE);
        final DocumentVersion version = resource.adaptTo(DocumentVersion.class);

        assertEquals(0, version.getNumber());
    }

    @Test
    void listsTheSectionsLookedForInIt()
    {
        final String version = DOCUMENT_PATH + "/v1";
        final Resource resource = this.context.create().resource(version,
            "sling:resourceType", DocumentVersion.RESOURCE_TYPE);
        this.context.create().resource(version + "/funding", "sling:resourceType", Section.RESOURCE_TYPE);
        this.context.create().resource(version + "/file", "sling:resourceType", File.RESOURCE_TYPE);

        assertEquals(List.of(version + "/funding"), resource.adaptTo(DocumentVersion.class).getSections().stream()
            .map(Section::getPath).collect(Collectors.toList()));
    }

    @Test
    void findsTheSectionFulfillingARequirement() throws RepositoryException
    {
        final String schema = "/Schemas/study/1.0/protocol";
        final SectionRequirement funding = this.context.create().resource(schema + "/funding",
            "sling:resourceType", SectionRequirement.RESOURCE_TYPE).adaptTo(SectionRequirement.class);
        final SectionRequirement ethics = this.context.create().resource(schema + "/ethics",
            "sling:resourceType", SectionRequirement.RESOURCE_TYPE).adaptTo(SectionRequirement.class);
        this.context.create().resource(schema + "/budget", "sling:resourceType", SectionRequirement.RESOURCE_TYPE);
        final Session session = Mockito.mock(Session.class);
        for (final String name : new String[] {"funding", "budget"}) {
            final Node node = Mockito.mock(Node.class);
            Mockito.when(node.getPath()).thenReturn(schema + "/" + name);
            Mockito.when(session.getNodeByIdentifier(name + "-id")).thenReturn(node);
        }
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);
        final String version = DOCUMENT_PATH + "/v1";
        final Resource resource = this.context.create().resource(version,
            "sling:resourceType", DocumentVersion.RESOURCE_TYPE);
        // One whose requirement is gone, then one for another requirement, then the one asked for
        this.context.create().resource(version + "/orphan", "sling:resourceType", Section.RESOURCE_TYPE);
        this.context.create().resource(version + "/budget",
            "sling:resourceType", Section.RESOURCE_TYPE, "fulfills", "budget-id");
        this.context.create().resource(version + "/funding",
            "sling:resourceType", Section.RESOURCE_TYPE, "fulfills", "funding-id");
        final DocumentVersion revision = resource.adaptTo(DocumentVersion.class);

        assertEquals(version + "/funding", revision.getSection(funding).getPath());
        assertNull(revision.getSection(ethics));
    }
}

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
import io.uhndata.iap.schemas.models.DocumentRequirement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Document}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class DocumentTest
{
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Document.class, DocumentRequirement.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/consent",
            "sling:resourceType", Document.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(Document.class));
    }

    @Test
    void exposesDocumentProperties()
        throws RepositoryException
    {
        this.context.create().resource("/Schemas/schema/1.0/consent",
            "sling:resourceType", DocumentRequirement.RESOURCE_TYPE, "label", "Signed consent form");
        final Node targetNode = Mockito.mock(Node.class);
        Mockito.when(targetNode.getPath()).thenReturn("/Schemas/schema/1.0/consent");
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getNodeByIdentifier("6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345")).thenReturn(targetNode);
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);

        final Resource resource = this.context.create().resource("/Submissions/submission/consent", Map.of(
            "sling:resourceType", Document.RESOURCE_TYPE,
            "title", "Signed consent",
            "description", "Patient consent form",
            "fulfills", "6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345"));
        final Document document = resource.adaptTo(Document.class);

        assertEquals("Signed consent", document.getTitle());
        assertEquals("Patient consent form", document.getDescription());
        assertEquals(DocumentRequirement.class, document.getFulfills().getClass());
        assertEquals("Signed consent form", document.getFulfills().getLabel());
    }

    @Test
    void listsAttachments()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/consent",
            "sling:resourceType", Document.RESOURCE_TYPE);
        this.context.create().resource("/Submissions/submission/consent/page1",
            "sling:resourceType", "nt:file");
        this.context.create().resource("/Submissions/submission/consent/page2",
            "sling:resourceType", "nt:file");
        final Document document = resource.adaptTo(Document.class);

        final List<Resource> attachments = document.getAttachments();

        assertEquals(2, attachments.size());
        assertEquals("page1", attachments.get(0).getName());
        assertEquals("page2", attachments.get(1).getName());
    }

    @Test
    void listsNoAttachmentsWhenNoneExist()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/empty",
            "sling:resourceType", Document.RESOURCE_TYPE);
        final Document document = resource.adaptTo(Document.class);

        assertTrue(document.getAttachments().isEmpty());
    }
}

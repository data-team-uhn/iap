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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link Evidence}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class EvidenceTest
{
    private static final String EVIDENCE_PATH = "/Submissions/submission/answer/evidence0";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Evidence.class, Chunk.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource(EVIDENCE_PATH,
            "sling:resourceType", Evidence.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(Evidence.class));
    }

    @Test
    void exposesEvidenceProperties()
        throws RepositoryException
    {
        this.context.create().resource("/Submissions/submission/consent/v0/file/chunks/chunk001", Map.of(
            "sling:resourceType", Chunk.RESOURCE_TYPE, "summary", "The recruitment plan"));
        final Node targetNode = Mockito.mock(Node.class);
        Mockito.when(targetNode.getPath()).thenReturn("/Submissions/submission/consent/v0/file/chunks/chunk001");
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getNodeByIdentifier("6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345")).thenReturn(targetNode);
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);

        final Resource resource = this.context.create().resource(EVIDENCE_PATH, Map.of(
            "sling:resourceType", Evidence.RESOURCE_TYPE,
            "chunk", "6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345",
            "quote", "42 participants will be recruited",
            "page", 7L));
        final Evidence evidence = resource.adaptTo(Evidence.class);

        assertEquals("42 participants will be recruited", evidence.getQuote());
        assertEquals(7L, evidence.getPage());
        assertEquals("chunk001", evidence.getChunk().getName());
        assertEquals("The recruitment plan", evidence.getChunk().getSummary());
    }

    @Test
    void toleratesMissingOptionalProperties()
    {
        final Resource resource = this.context.create().resource(EVIDENCE_PATH,
            "sling:resourceType", Evidence.RESOURCE_TYPE);
        final Evidence evidence = resource.adaptTo(Evidence.class);

        assertNotNull(evidence);
        assertNull(evidence.getChunk());
        assertNull(evidence.getQuote());
        // A quote from a source with no page markers, e.g. anything that came in as DOCX
        assertNull(evidence.getPage());
    }
}

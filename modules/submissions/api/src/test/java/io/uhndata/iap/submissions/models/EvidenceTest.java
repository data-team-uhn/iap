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

    private static final String VERSION_PATH = "/Submissions/submission/preamble/v1";

    private static final String VERSION_ID = "6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Evidence.class, DocumentVersion.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource(EVIDENCE_PATH,
            "sling:resourceType", Evidence.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(Evidence.class));
    }

    @Test
    void carriesTheQuoteWithWhereItSits()
    {
        final Resource resource = this.context.create().resource(EVIDENCE_PATH, Map.of(
            "sling:resourceType", Evidence.RESOURCE_TYPE,
            "quote", "Participants are followed for 52 weeks.",
            "header", "Study design",
            "page", 9L));

        final Evidence evidence = resource.adaptTo(Evidence.class);

        assertEquals("Participants are followed for 52 weeks.", evidence.getQuote());
        assertEquals("Study design", evidence.getHeader());
        assertEquals(9L, evidence.getPage());
    }

    // A DOCX carries no page markers, and nothing titles a quote in a document with no headings
    @Test
    void saysNothingAboutAPageOrHeadingItDoesNotHave()
    {
        final Resource resource = this.context.create().resource(EVIDENCE_PATH, Map.of(
            "sling:resourceType", Evidence.RESOURCE_TYPE, "quote", "A sentence."));

        final Evidence evidence = resource.adaptTo(Evidence.class);

        assertNull(evidence.getPage());
        assertNull(evidence.getHeader());
        assertNull(evidence.getSource(), "a run that read one document names no source per passage");
    }

    // A run that read several documents as one text records which one each quote came from
    @Test
    void namesTheDocumentItWasQuotedFrom() throws RepositoryException
    {
        this.context.create().resource(VERSION_PATH, "sling:resourceType", DocumentVersion.RESOURCE_TYPE);
        final Node version = Mockito.mock(Node.class);
        Mockito.when(version.getPath()).thenReturn(VERSION_PATH);
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getNodeByIdentifier(VERSION_ID)).thenReturn(version);
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);
        final Resource resource = this.context.create().resource(EVIDENCE_PATH, Map.of(
            "sling:resourceType", Evidence.RESOURCE_TYPE, "quote", "A sentence.", "source", VERSION_ID));

        assertEquals(VERSION_PATH, resource.adaptTo(Evidence.class).getSource().getPath());
    }
}

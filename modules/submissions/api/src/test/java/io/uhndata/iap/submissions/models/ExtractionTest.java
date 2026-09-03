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

import javax.jcr.ItemNotFoundException;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Extraction}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ExtractionTest
{
    private static final String EXTRACTION_PATH = "/Submissions/submission/participants/extraction0";

    private static final String VERSION_PATH = "/Submissions/submission/protocol/v0";

    private static final String VERSION_ID = "6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345";

    private static final String GONE_ID = "00000000-0000-0000-0000-000000000000";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Extraction.class, DocumentVersion.class,
            Evidence.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource(EXTRACTION_PATH,
            "sling:resourceType", Extraction.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(Extraction.class));
    }

    @Test
    void exposesWhatTheModelRead()
    {
        final Resource resource = this.context.create().resource(EXTRACTION_PATH, Map.of(
            "sling:resourceType", Extraction.RESOURCE_TYPE,
            "extractedAnswer", "42",
            "confidence", 0.87,
            "reasoning", "The recruitment table gives 42 in the final column"));
        final Extraction extraction = resource.adaptTo(Extraction.class);

        assertEquals("42", extraction.getExtractedAnswer());
        assertEquals(0.87, extraction.getConfidence());
        assertEquals("The recruitment table gives 42 in the final column", extraction.getReasoning());
        // Nobody has accepted or edited it yet, so there is nothing to compare against
        assertFalse(extraction.isActedOn());
        assertNull(extraction.getEditDistance());
        assertNull(extraction.getPercentageDistance());
    }

    @Test
    void recordsWhatItReadEvenWhenItFoundNothing()
        throws RepositoryException
    {
        this.registerVersion();
        final Resource resource = this.context.create().resource(EXTRACTION_PATH, Map.of(
            "sling:resourceType", Extraction.RESOURCE_TYPE,
            "sources", new String[]{ VERSION_ID }));
        final Extraction extraction = resource.adaptTo(Extraction.class);

        // The run's existence is the record that extraction happened; no answer means it found nothing
        assertNull(extraction.getExtractedAnswer());
        assertEquals(1, extraction.getSources().size());
        assertEquals("v0", extraction.getSources().get(0).getName());
    }

    @Test
    void skipsSourcesThatNoLongerResolve()
        throws RepositoryException
    {
        this.registerVersion();
        final Resource resource = this.context.create().resource(EXTRACTION_PATH, Map.of(
            "sling:resourceType", Extraction.RESOURCE_TYPE,
            "sources", new String[]{ VERSION_ID, GONE_ID }));
        final Extraction extraction = resource.adaptTo(Extraction.class);

        final List<DocumentVersion> sources = extraction.getSources();

        assertEquals(1, sources.size());
        assertEquals(VERSION_PATH, sources.get(0).getPath());
    }

    @Test
    void reportsTheDistanceOnceTheSubmitterActsOnIt()
    {
        final Resource resource = this.context.create().resource(EXTRACTION_PATH, Map.of(
            "sling:resourceType", Extraction.RESOURCE_TYPE,
            "extractedAnswer", "42 participants",
            "editDistance", 3L,
            "percentageDistance", 20.0));
        final Extraction extraction = resource.adaptTo(Extraction.class);

        assertTrue(extraction.isActedOn());
        assertEquals(3L, extraction.getEditDistance());
        assertEquals(20.0, extraction.getPercentageDistance());
    }

    @Test
    void listsTheEvidenceBackingTheAnswer()
    {
        final Resource resource = this.context.create().resource(EXTRACTION_PATH,
            "sling:resourceType", Extraction.RESOURCE_TYPE);
        this.context.create().resource(EXTRACTION_PATH + "/evidence0", Map.of(
            "sling:resourceType", Evidence.RESOURCE_TYPE, "quote", "42 participants will be recruited"));
        this.context.create().resource(EXTRACTION_PATH + "/evidence1", Map.of(
            "sling:resourceType", Evidence.RESOURCE_TYPE, "quote", "of whom 42 complete the protocol"));
        final Extraction extraction = resource.adaptTo(Extraction.class);

        final List<Evidence> evidence = extraction.getEvidence();

        assertEquals(2, evidence.size());
        assertEquals("42 participants will be recruited", evidence.get(0).getQuote());
    }

    @Test
    void toleratesARunThatRecordedNothing()
    {
        final Resource resource = this.context.create().resource(EXTRACTION_PATH,
            "sling:resourceType", Extraction.RESOURCE_TYPE);
        final Extraction extraction = resource.adaptTo(Extraction.class);

        assertNotNull(extraction);
        assertTrue(extraction.getSources().isEmpty());
        assertNull(extraction.getExtractedAnswer());
        assertNull(extraction.getConfidence());
        assertNull(extraction.getReasoning());
        assertNull(extraction.getEditDistance());
        assertNull(extraction.getPercentageDistance());
        assertFalse(extraction.isActedOn());
        assertTrue(extraction.getEvidence().isEmpty());
    }

    private void registerVersion()
        throws RepositoryException
    {
        this.context.create().resource(VERSION_PATH,
            "sling:resourceType", DocumentVersion.RESOURCE_TYPE);
        final Node targetNode = Mockito.mock(Node.class);
        Mockito.when(targetNode.getPath()).thenReturn(VERSION_PATH);
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getNodeByIdentifier(VERSION_ID)).thenReturn(targetNode);
        // A real session says so rather than answering null, which is what the model has to survive
        Mockito.when(session.getNodeByIdentifier(GONE_ID)).thenThrow(new ItemNotFoundException(GONE_ID));
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);
    }
}

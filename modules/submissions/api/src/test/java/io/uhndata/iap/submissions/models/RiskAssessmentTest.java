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
import io.uhndata.iap.schemas.models.Risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RiskAssessment}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class RiskAssessmentTest
{
    private static final String ASSESSMENT_PATH = "/Submissions/submission/review/privacy";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, RiskAssessment.class, Risk.class,
            Extraction.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource(ASSESSMENT_PATH,
            "sling:resourceType", RiskAssessment.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(RiskAssessment.class));
    }

    @Test
    void exposesRiskAssessmentProperties()
        throws RepositoryException
    {
        this.context.create().resource("/Schemas/schema/1.0/reb/privacy",
            "sling:resourceType", Risk.RESOURCE_TYPE, "text", "Participant privacy");
        final Node targetNode = Mockito.mock(Node.class);
        Mockito.when(targetNode.getPath()).thenReturn("/Schemas/schema/1.0/reb/privacy");
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getNodeByIdentifier("6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345")).thenReturn(targetNode);
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);

        final Resource resource = this.context.create().resource(ASSESSMENT_PATH, Map.of(
            "sling:resourceType", RiskAssessment.RESOURCE_TYPE,
            "risk", "6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345",
            "value", "Low: the data is de-identified before analysis"));
        final RiskAssessment assessment = resource.adaptTo(RiskAssessment.class);

        assertEquals("Participant privacy", assessment.getRisk().getText());
        assertEquals("Low: the data is de-identified before analysis", assessment.getValue());
    }

    @Test
    void toleratesMissingOptionalProperties()
    {
        final Resource resource = this.context.create().resource(ASSESSMENT_PATH,
            "sling:resourceType", RiskAssessment.RESOURCE_TYPE);
        final RiskAssessment assessment = resource.adaptTo(RiskAssessment.class);

        assertNotNull(assessment);
        assertNull(assessment.getRisk());
        assertNull(assessment.getValue());
        assertTrue(assessment.getExtractions().isEmpty());
        assertNull(assessment.getLatestExtraction());
    }

    @Test
    void listsExtractionRunsInTheOrderTheyRan()
    {
        final Resource resource = this.context.create().resource(ASSESSMENT_PATH,
            "sling:resourceType", RiskAssessment.RESOURCE_TYPE);
        this.context.create().resource(ASSESSMENT_PATH + "/extraction0", Map.of(
            "sling:resourceType", Extraction.RESOURCE_TYPE, "extractedAnswer", "High"));
        this.context.create().resource(ASSESSMENT_PATH + "/extraction1", Map.of(
            "sling:resourceType", Extraction.RESOURCE_TYPE, "extractedAnswer", "Low"));
        this.context.create().resource(ASSESSMENT_PATH + "/other",
            "sling:resourceType", "nt:unstructured");
        final RiskAssessment assessment = resource.adaptTo(RiskAssessment.class);

        final List<Extraction> extractions = assessment.getExtractions();

        assertEquals(2, extractions.size());
        assertEquals("High", extractions.get(0).getExtractedAnswer());
        assertEquals("Low", assessment.getLatestExtraction().getExtractedAnswer());
    }
}

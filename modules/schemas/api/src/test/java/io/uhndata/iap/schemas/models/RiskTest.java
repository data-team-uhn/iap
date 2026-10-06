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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Risk}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class RiskTest
{
    private static final String RISK_PATH = "/Schemas/schema/1.0/reb/privacy";

    private static final String QUESTION_PATH = "/Schemas/schema/1.0/form/q1";

    private static final String QUESTION_ID = "6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345";

    private static final String GONE_ID = "00000000-0000-0000-0000-000000000000";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Risk.class, Question.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource(RISK_PATH,
            "sling:resourceType", Risk.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(Risk.class));
    }

    @Test
    void exposesRiskProperties()
    {
        final Resource resource = this.context.create().resource(RISK_PATH, Map.of(
            "sling:resourceType", Risk.RESOURCE_TYPE,
            "text", "Participant privacy",
            "description", "Could a participant be identified?",
            "purpose", "Judge whether the data is safely de-identified",
            "assessmentPrompt", "Assess the privacy risk to participants"));
        final Risk risk = resource.adaptTo(Risk.class);

        assertEquals("Participant privacy", risk.getText());
        assertEquals("Could a participant be identified?", risk.getDescription());
        assertEquals("Judge whether the data is safely de-identified", risk.getPurpose());
        assertEquals("Assess the privacy risk to participants", risk.getAssessmentPrompt());
    }

    @Test
    void skipsQuestionsThatNoLongerResolve()
        throws RepositoryException
    {
        this.context.create().resource(QUESTION_PATH,
            "sling:resourceType", Question.RESOURCE_TYPE, "text", "Will you collect names?");
        final Node targetNode = Mockito.mock(Node.class);
        Mockito.when(targetNode.getPath()).thenReturn(QUESTION_PATH);
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getNodeByIdentifier(QUESTION_ID)).thenReturn(targetNode);
        // The link is weak, so the question may have been removed since
        Mockito.when(session.getNodeByIdentifier(GONE_ID)).thenThrow(new ItemNotFoundException(GONE_ID));
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);

        final Resource resource = this.context.create().resource(RISK_PATH, Map.of(
            "sling:resourceType", Risk.RESOURCE_TYPE,
            "questions", new String[]{ QUESTION_ID, GONE_ID }));
        final List<Question> questions = resource.adaptTo(Risk.class).getQuestions();

        assertEquals(1, questions.size());
        assertEquals("Will you collect names?", questions.get(0).getText());
    }

    @Test
    void toleratesMissingOptionalProperties()
    {
        final Resource resource = this.context.create().resource(RISK_PATH,
            "sling:resourceType", Risk.RESOURCE_TYPE);
        final Risk risk = resource.adaptTo(Risk.class);

        assertNotNull(risk);
        assertNull(risk.getDescription());
        assertNull(risk.getPurpose());
        assertNull(risk.getAssessmentPrompt());
        assertTrue(risk.getQuestions().isEmpty());
    }
}

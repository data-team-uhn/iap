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
import io.uhndata.iap.schemas.models.AssessmentCriteria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Assessment}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class AssessmentTest
{
    private static final String ASSESSMENT_PATH = "/Submissions/submission/review/privacy";

    private static final String ANSWER_PATH = "/Submissions/submission/a1";

    private static final String ANSWER_ID = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f00000001";

    private static final String GONE_ID = "00000000-0000-0000-0000-000000000000";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Assessment.class, AssessmentCriteria.class,
            Comment.class, Answer.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource(ASSESSMENT_PATH,
            "sling:resourceType", Assessment.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(Assessment.class));
    }

    @Test
    void exposesAssessmentProperties()
        throws RepositoryException
    {
        this.context.create().resource("/Schemas/schema/1.0/reb/privacy",
            "sling:resourceType", AssessmentCriteria.RESOURCE_TYPE, "text", "Participant privacy");
        final Node targetNode = Mockito.mock(Node.class);
        Mockito.when(targetNode.getPath()).thenReturn("/Schemas/schema/1.0/reb/privacy");
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getNodeByIdentifier("6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345")).thenReturn(targetNode);
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);

        final Resource resource = this.context.create().resource(ASSESSMENT_PATH, Map.of(
            "sling:resourceType", Assessment.RESOURCE_TYPE,
            "assessmentCriteria", "6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345",
            "value", "Looked at"));
        final Assessment assessment = resource.adaptTo(Assessment.class);

        assertEquals("Participant privacy", assessment.getAssessmentCriteria().getText());
        assertEquals("Looked at", assessment.getValue());
    }

    @Test
    void toleratesMissingOptionalProperties()
    {
        final Resource resource = this.context.create().resource(ASSESSMENT_PATH,
            "sling:resourceType", Assessment.RESOURCE_TYPE);
        final Assessment assessment = resource.adaptTo(Assessment.class);

        assertNotNull(assessment);
        assertNull(assessment.getAssessmentCriteria());
        assertNull(assessment.getValue());
        assertNull(assessment.getSummary());
        assertNull(assessment.getConfidence());
        assertTrue(assessment.getSources().isEmpty());
        assertTrue(assessment.getComments().isEmpty());
    }

    @Test
    void exposesWhatTheAssessmentRead() throws RepositoryException
    {
        this.context.create().resource(ANSWER_PATH, "sling:resourceType", Answer.RESOURCE_TYPE);
        final Session session = Mockito.mock(Session.class);
        final Node answer = Mockito.mock(Node.class);
        Mockito.when(answer.getPath()).thenReturn(ANSWER_PATH);
        Mockito.when(session.getNodeByIdentifier(ANSWER_ID)).thenReturn(answer);
        // The link is weak, so a source may have been removed since
        Mockito.when(session.getNodeByIdentifier(GONE_ID)).thenThrow(new ItemNotFoundException(GONE_ID));
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);

        final Assessment assessment = this.context.create().resource(ASSESSMENT_PATH, Map.of(
            "sling:resourceType", Assessment.RESOURCE_TYPE, "summary", "Consent is unclear", "confidence", 0.8,
            "sources", new String[] {GONE_ID, ANSWER_ID})).adaptTo(Assessment.class);

        assertEquals("Consent is unclear", assessment.getSummary());
        assertEquals(0.8, assessment.getConfidence());
        assertEquals(List.of(ANSWER_PATH),
            assessment.getSources().stream().map(EntityPart::getPath).collect(Collectors.toList()));
    }

    @Test
    void listsCommentsInTheOrderTheyWereRaised()
    {
        final Resource resource = this.context.create().resource(ASSESSMENT_PATH,
            "sling:resourceType", Assessment.RESOURCE_TYPE);
        this.context.create().resource(ASSESSMENT_PATH + "/comment0", Map.of(
            "sling:resourceType", Comment.RESOURCE_TYPE, "text", "No consent form is attached", "author", "ai"));
        this.context.create().resource(ASSESSMENT_PATH + "/comment1", Map.of(
            "sling:resourceType", Comment.RESOURCE_TYPE, "text", "The retention period is not stated",
            "author", "ai"));
        this.context.create().resource(ASSESSMENT_PATH + "/other",
            "sling:resourceType", "nt:unstructured");

        final List<Comment> comments = resource.adaptTo(Assessment.class).getComments();

        assertEquals(2, comments.size());
        assertEquals("No consent form is attached", comments.get(0).getText());
        assertEquals("The retention period is not stated", comments.get(1).getText());
    }
}

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Topic}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class TopicTest
{
    private static final String TOPIC_PATH = "/Schemas/schema/1.0/reb/privacy";

    private static final String QUESTION_PATH = "/Schemas/schema/1.0/form/q1";

    private static final String QUESTION_ID = "6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345";

    private static final String REQUIREMENT_PATH = "/Schemas/schema/1.0/protocol";

    private static final String REQUIREMENT_ID = "1b2c3d4e-0000-4a7e-8c3f-abcdef012345";

    private static final String GONE_ID = "00000000-0000-0000-0000-000000000000";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Topic.class, Question.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource(TOPIC_PATH,
            "sling:resourceType", Topic.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(Topic.class));
    }

    @Test
    void exposesItsProperties()
    {
        final Resource resource = this.context.create().resource(TOPIC_PATH, Map.of(
            "sling:resourceType", Topic.RESOURCE_TYPE,
            "text", "Participant privacy",
            "description", "Could a participant be identified?",
            "assessmentPrompt", "Assess the privacy risk to participants"));
        final Topic topic = resource.adaptTo(Topic.class);

        assertEquals("Participant privacy", topic.getText());
        assertEquals("Could a participant be identified?", topic.getDescription());
        assertEquals("Assess the privacy risk to participants", topic.getAssessmentPrompt());
    }

    @Test
    void readsRequirementsAndQuestionsAsSources()
        throws RepositoryException
    {
        this.context.create().resource(QUESTION_PATH,
            "sling:resourceType", Question.RESOURCE_TYPE, "text", "Will you collect names?");
        this.context.create().resource(REQUIREMENT_PATH,
            "sling:resourceType", DocumentRequirement.RESOURCE_TYPE, "label", "Study protocol");
        final Session session = Mockito.mock(Session.class);
        final Node question = Mockito.mock(Node.class);
        Mockito.when(question.getPath()).thenReturn(QUESTION_PATH);
        Mockito.when(session.getNodeByIdentifier(QUESTION_ID)).thenReturn(question);
        final Node requirement = Mockito.mock(Node.class);
        Mockito.when(requirement.getPath()).thenReturn(REQUIREMENT_PATH);
        Mockito.when(session.getNodeByIdentifier(REQUIREMENT_ID)).thenReturn(requirement);
        // The link is weak, so a source may have been removed since
        Mockito.when(session.getNodeByIdentifier(GONE_ID)).thenThrow(new ItemNotFoundException(GONE_ID));
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);

        final Resource resource = this.context.create().resource(TOPIC_PATH, Map.of(
            "sling:resourceType", Topic.RESOURCE_TYPE,
            "sources", new String[]{ REQUIREMENT_ID, GONE_ID, QUESTION_ID }));
        final List<EntityPart> sources = resource.adaptTo(Topic.class).getSources();

        assertEquals(List.of(REQUIREMENT_PATH, QUESTION_PATH),
            sources.stream().map(EntityPart::getPath).collect(Collectors.toList()));
    }

    @Test
    void toleratesMissingOptionalProperties()
    {
        final Resource resource = this.context.create().resource(TOPIC_PATH,
            "sling:resourceType", Topic.RESOURCE_TYPE);
        final Topic topic = resource.adaptTo(Topic.class);

        assertNotNull(topic);
        assertNull(topic.getDescription());
        assertNull(topic.getAssessmentPrompt());
        assertTrue(topic.getSources().isEmpty());
    }
}

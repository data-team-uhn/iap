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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ClassificationRequirement}: the question it references, whose options are the categories.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ClassificationRequirementTest
{
    private static final String VERSION = "/Schemas/schema/1.0";

    private static final String PATH = VERSION + "/study_kind";

    private static final String QUESTION_PATH = VERSION + "/classification/kind";

    private static final String QUESTION_ID = "6f1c2a3b-0000-4000-8000-000000000001";

    private static final String TYPE = "sling:resourceType";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp() throws RepositoryException
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Question.class, AnswerOption.class,
            ClassificationRequirement.class);
        final Node target = Mockito.mock(Node.class);
        Mockito.when(target.getPath()).thenReturn(QUESTION_PATH);
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getNodeByIdentifier(QUESTION_ID)).thenReturn(target);
        Mockito.when(session.getNodeByIdentifier(Mockito.argThat(id -> !QUESTION_ID.equals(id))))
            .thenThrow(new RepositoryException("No such node"));
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);
    }

    private ClassificationRequirement createRequirement(final Map<String, Object> properties)
    {
        return this.context.create().resource(PATH, properties).adaptTo(ClassificationRequirement.class);
    }

    private void createQuestion()
    {
        this.context.create().resource(QUESTION_PATH, Map.of(TYPE, Question.RESOURCE_TYPE,
            "sling:resourceSuperType", FormItem.RESOURCE_TYPE, "text", "Which kind of study is it?"));
        this.context.create().resource(QUESTION_PATH + "/prospective",
            Map.of(TYPE, AnswerOption.RESOURCE_TYPE, "value", "prospective", "label", "Prospective"));
        this.context.create().resource(QUESTION_PATH + "/retrospective",
            Map.of(TYPE, AnswerOption.RESOURCE_TYPE, "value", "retrospective", "label", "Retrospective"));
    }

    @Test
    void offersTheOptionsOfTheQuestionItReferences()
    {
        createQuestion();
        final ClassificationRequirement requirement =
            createRequirement(Map.of(TYPE, ClassificationRequirement.RESOURCE_TYPE, "question", QUESTION_ID));

        assertEquals(QUESTION_PATH, requirement.getQuestion().getPath());
        assertEquals(List.of("prospective", "retrospective"),
            requirement.getCategories().stream().map(OfferedOption::value).toList());
    }

    // Without its question, a requirement has no categories to pick from
    @Test
    void offersNothingWithoutAQuestion()
    {
        final ClassificationRequirement requirement =
            createRequirement(Map.of(TYPE, ClassificationRequirement.RESOURCE_TYPE));

        assertNull(requirement.getQuestion());
        assertTrue(requirement.getCategories().isEmpty());
    }

    // A reference to a question that is gone counts as no question
    @Test
    void offersNothingWhenTheQuestionIsGone()
    {
        final ClassificationRequirement requirement = createRequirement(
            Map.of(TYPE, ClassificationRequirement.RESOURCE_TYPE, "question", "6f1c2a3b-0000-4000-8000-0000000000ff"));

        assertNull(requirement.getQuestion());
        assertTrue(requirement.getCategories().isEmpty());
    }

    // The prompt, document, threshold and template come from DecisionRequirement, shared with a validation
    @Test
    void readsWhatEveryDecisionNames()
    {
        final ClassificationRequirement requirement = createRequirement(Map.of(
            TYPE, ClassificationRequirement.RESOURCE_TYPE,
            "prompt", "Decide which kind of study the protocol describes.",
            "document", "proposal",
            "confidenceThreshold", 0.8));

        assertEquals("Decide which kind of study the protocol describes.", requirement.getPrompt());
        assertEquals("proposal", requirement.getDocumentRequirementName());
        assertEquals(0.8, requirement.getConfidenceThreshold());
    }

    // It reads as a Requirement, so the schema version lists it with the others
    @Test
    void readsAsARequirement()
    {
        final Resource resource = this.context.create().resource(PATH, TYPE, ClassificationRequirement.RESOURCE_TYPE);

        assertTrue(resource.adaptTo(Requirement.class) instanceof ClassificationRequirement);
    }
}
